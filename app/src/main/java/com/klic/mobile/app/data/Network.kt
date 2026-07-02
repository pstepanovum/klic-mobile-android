package com.klic.mobile.app.data

import com.klic.mobile.app.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.Route
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface KlicApi {
    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): AuthResponse

    @PATCH("me")
    suspend fun updateProfile(@Body body: kotlinx.serialization.json.JsonObject): User

    @POST("me/avatar-upload")
    suspend fun avatarUpload(@Body body: AvatarUploadRequest): UploadTicket

    @GET("users/{id}")
    suspend fun userProfile(@Path("id") id: String): UserProfile

    @GET("conversations")
    suspend fun conversations(): List<Conversation>

    @POST("conversations")
    suspend fun createConversation(@Body body: CreateConversationRequest): Conversation

    @GET("conversations/{id}/messages")
    suspend fun messages(
        @Path("id") id: String,
        @Query("limit") limit: Int = 50,
        @Query("before") before: String? = null,
    ): List<Message>

    @POST("conversations/{id}/messages")
    suspend fun send(@Path("id") id: String, @Body body: SendMessageRequest): Message

    @POST("conversations/{id}/messages")
    suspend fun sendMessage(@Path("id") id: String, @Body body: SendWithAttachmentsRequest): Message

    @POST("uploads")
    suspend fun requestUpload(@Body body: UploadRequest): UploadTicket

    @POST("me/devices")
    suspend fun registerDevice(@Body body: Map<String, String>): Response<ResponseBody>

    // E2EE key distribution (E2EE.md §6.2)
    @PUT("keys")
    suspend fun publishKeys(@Body body: PublishKeysRequest): PublishKeysResponse

    @GET("keys/count")
    suspend fun preKeyCount(@Query("installId") installId: String): PreKeyCountResponse

    @POST("keys/prekeys")
    suspend fun topUpPreKeys(@Body body: TopUpPreKeysRequest): Response<ResponseBody>

    @PUT("keys/signed-prekey")
    suspend fun rotateSignedPreKey(@Body body: RotateSignedPreKeyRequest): Response<ResponseBody>

    @GET("users/{id}/keys")
    suspend fun userKeys(@Path("id") id: String): UserKeysResponse

    @GET("conversations/{id}/devices")
    suspend fun conversationDevices(@Path("id") id: String): DeviceDirectoryResponse

    @POST("conversations/{id}/messages")
    suspend fun sendCiphertext(@Path("id") id: String, @Body body: CipherSendRequest): Message

    @POST("diagnostics/mobile-event")
    suspend fun mobileDiagnostic(@Body body: MobileDiagnosticRequest): Response<ResponseBody>

    @POST("calls/{id}/media-joined")
    suspend fun mediaJoined(@Path("id") id: String): Response<ResponseBody>

    @POST("calls/{id}/decline")
    suspend fun declineCall(@Path("id") id: String): Response<ResponseBody>

    @POST("calls/{id}/cancel")
    suspend fun cancelCall(@Path("id") id: String): Response<ResponseBody>

    @POST("calls/{id}/fail")
    suspend fun failCall(@Path("id") id: String): Response<ResponseBody>

    @POST("calls/{id}/end")
    suspend fun endCall(@Path("id") id: String): Response<ResponseBody>

    @GET("users")
    suspend fun findUser(@Query("username") username: String): List<User>

    @GET("friends")
    suspend fun friends(): List<User>

    @GET("friends/requests")
    suspend fun friendRequests(): List<FriendRequest>

    @POST("friends/requests")
    suspend fun sendFriendRequest(@Body body: Map<String, String>): Response<ResponseBody>

    @POST("friends/requests/{id}/accept")
    suspend fun acceptFriendRequest(@Path("id") id: String): Response<ResponseBody>

    @POST("friends/requests/{id}/decline")
    suspend fun declineFriendRequest(@Path("id") id: String): Response<ResponseBody>

    @POST("calls")
    suspend fun startCall(@Body body: StartCallRequest): CallSession

    @POST("calls/{id}/token")
    suspend fun joinToken(@Path("id") id: String): CallSession

    @GET("conversations/{id}/active-call")
    suspend fun activeCall(@Path("id") id: String): ActiveCallInfo

    @GET("calls")
    suspend fun recentCalls(): List<RecentCall>

    @GET("stickers")
    suspend fun stickers(): StickerCatalog

    @POST("conversations/{id}/messages")
    suspend fun sendSticker(@Path("id") id: String, @Body body: SendStickerRequest): Message

    @POST("conversations/{id}/messages/{messageId}/reactions")
    suspend fun react(
        @Path("id") id: String,
        @Path("messageId") messageId: String,
        @Body body: ReactionRequest,
    ): ReactionResponse

    @DELETE("conversations/{id}/messages/{messageId}")
    suspend fun deleteMessage(
        @Path("id") id: String,
        @Path("messageId") messageId: String,
        @Query("scope") scope: String = "everyone",
    ): Response<ResponseBody>
}

/** Bare, synchronous refresh used by the Authenticator (no auth header, no authenticator → no recursion). */
private interface AuthApi {
    @POST("auth/refresh")
    fun refresh(@Body body: RefreshRequest): Call<AuthResponse>
}

object Network {
    // Klic-specific host override, usually supplied from Gradle property `KLIC_API_ORIGIN`.
    val BASE_HTTP = BuildConfig.KLIC_API_ORIGIN
    private val API = "$BASE_HTTP/api/v1/"

    /** Public, stable avatar URL for any user id (404s → UI falls back to initials). */
    fun avatarUrl(userId: String): String = "$BASE_HTTP/api/v1/users/$userId/avatar"

    private val json = Json { ignoreUnknownKeys = true }

    fun create(tokenStore: TokenStore, onSessionExpired: () -> Unit): KlicApi {
        val converter = json.asConverterFactory("application/json".toMediaType())

        // Plain client (no interceptors) so refresh never recurses through itself.
        val authApi = Retrofit.Builder()
            .baseUrl(API)
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(converter)
            .build()
            .create(AuthApi::class.java)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                tokenStore.cachedAccess?.let { builder.header("Authorization", "Bearer $it") }
                chain.proceed(builder.build())
            }
            .authenticator(TokenAuthenticator(tokenStore, authApi, onSessionExpired))
            .build()

        return Retrofit.Builder()
            .baseUrl(API)
            .client(client)
            .addConverterFactory(converter)
            .build()
            .create(KlicApi::class.java)
    }
}

/**
 * Refreshes the access token when a request comes back `401`, then replays it. A lock
 * serializes concurrent 401s so a burst triggers a single rotation rather than many.
 *
 * Only a `401` from the refresh endpoint itself is a genuine sign-out (clear tokens +
 * notify). Any other failure (network/5xx) is transient — we keep the tokens and just
 * give up on this request, so the user is never logged out by a hiccup.
 */
private class TokenAuthenticator(
    private val tokenStore: TokenStore,
    private val authApi: AuthApi,
    private val onSessionExpired: () -> Unit,
) : Authenticator {
    private val lock = Any()

    override fun authenticate(route: Route?, response: okhttp3.Response): Request? {
        // Stop after one retry to avoid loops.
        if (responseCount(response) >= 2) return null
        val refreshToken = tokenStore.cachedRefresh ?: return null
        val sentToken = response.request.header("Authorization")?.removePrefix("Bearer ")

        synchronized(lock) {
            // Another thread may have already refreshed while we waited on the lock.
            val current = tokenStore.cachedAccess
            if (current != null && current != sentToken) {
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $current")
                    .build()
            }

            val result = runCatching { authApi.refresh(RefreshRequest(refreshToken)).execute() }
            val http = result.getOrNull()
            val body = http?.takeIf { it.isSuccessful }?.body()
            if (body != null) {
                tokenStore.saveBlocking(body.accessToken, body.refreshToken)
                return response.request.newBuilder()
                    .header("Authorization", "Bearer ${body.accessToken}")
                    .build()
            }
            if (http?.code() == 401) {
                tokenStore.clearBlocking()
                onSessionExpired()
            }
            return null
        }
    }

    private fun responseCount(response: okhttp3.Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
