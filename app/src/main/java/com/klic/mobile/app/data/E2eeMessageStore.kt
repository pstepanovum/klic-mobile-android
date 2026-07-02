package com.klic.mobile.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Locally persisted decrypted content of one CIPHERTEXT message. The content
 * JSON is AES-encrypted with the Keystore key ([KeystoreCrypto]) before it hits
 * the row — a device dump exposes routing metadata the server already had, but
 * never message content. The database is excluded from backups.
 *
 * This is the on-device source of truth for E2EE messages: the sender receives
 * no envelope for itself, so without this store its own sent messages would
 * vanish on restart.
 */
@Entity(tableName = "e2ee_messages", indices = [Index("conversationId")])
data class E2eeMessageRow(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val senderId: String,
    val senderDeviceId: Int,
    val contentEnc: String, // KeystoreCrypto-encrypted E2eeContent JSON
    val createdAt: String,
)

@Dao
interface E2eeMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: E2eeMessageRow)

    @Query("SELECT * FROM e2ee_messages WHERE messageId = :messageId")
    suspend fun byId(messageId: String): E2eeMessageRow?

    @Query("SELECT * FROM e2ee_messages WHERE conversationId = :conversationId")
    suspend fun forConversation(conversationId: String): List<E2eeMessageRow>

    @Query("DELETE FROM e2ee_messages WHERE messageId = :messageId")
    suspend fun delete(messageId: String)

    @Query("DELETE FROM e2ee_messages")
    suspend fun clear()
}

@Database(entities = [E2eeMessageRow::class], version = 1, exportSchema = false)
abstract class E2eeDatabase : RoomDatabase() {
    abstract fun messages(): E2eeMessageDao
}

/** Decrypted-content store over Room + [KeystoreCrypto] per-row encryption. */
class E2eeMessageStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val db = Room.databaseBuilder(context, E2eeDatabase::class.java, DB_NAME).build()
    private val dao get() = db.messages()

    suspend fun save(
        messageId: String,
        conversationId: String,
        senderId: String,
        senderDeviceId: Int,
        content: E2eeContent,
        createdAt: String,
    ) {
        dao.upsert(
            E2eeMessageRow(
                messageId = messageId,
                conversationId = conversationId,
                senderId = senderId,
                senderDeviceId = senderDeviceId,
                contentEnc = KeystoreCrypto.encrypt(json.encodeToString(content)),
                createdAt = createdAt,
            ),
        )
    }

    suspend fun get(messageId: String): E2eeContent? =
        dao.byId(messageId)?.let(::decode)

    suspend fun forConversation(conversationId: String): Map<String, E2eeContent> =
        dao.forConversation(conversationId)
            .mapNotNull { row -> decode(row)?.let { row.messageId to it } }
            .toMap()

    suspend fun delete(messageId: String) = dao.delete(messageId)

    suspend fun clear() = dao.clear()

    private fun decode(row: E2eeMessageRow): E2eeContent? =
        KeystoreCrypto.decrypt(row.contentEnc)
            ?.let { runCatching { json.decodeFromString<E2eeContent>(it) }.getOrNull() }

    companion object {
        const val DB_NAME = "klic_e2ee_messages.db"
    }
}
