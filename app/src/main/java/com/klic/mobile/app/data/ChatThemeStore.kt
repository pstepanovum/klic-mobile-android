package com.klic.mobile.app.data

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Chat theme (§12.3) — a purely local, per-device setting applied to ALL conversations.
 * The chat screen renders: background color → gradient layer → low-opacity pattern
 * layer → messages. Own bubbles (and the primary accents around them, e.g. the send
 * button) take the selected bubble color.
 */
object ChatThemeStore {
    private val Context.chatThemeDataStore by preferencesDataStore(name = "klic_chat_theme")

    const val PATTERN_COUNT = 10
    const val DEFAULT_PATTERN = 1              // the default/base pattern is ALWAYS 1.svg
    const val MIN_PATTERN_OPACITY = 0.02f      // subtle by design — the pattern must
    const val MAX_PATTERN_OPACITY = 0.10f      // never overpower content
    const val DEFAULT_PATTERN_OPACITY = 0.04f
    const val DEFAULT_GRADIENT = "none"        // default = flat (no gradient)
    const val DEFAULT_GRADIENT_INTENSITY = 0.5f
    const val DEFAULT_BUBBLE = "klic"

    /** Asset path for a pattern id (1-based). */
    fun patternAsset(id: Int): String = "file:///android_asset/chat_themes/$id.svg"

    /** Curated own-bubble palette — every color keeps white text readable. */
    data class BubblePalette(val id: String, val color: Color)

    val bubblePalettes: List<BubblePalette> = listOf(
        BubblePalette("klic", Color(0xFFED122B)),      // brand red (default)
        BubblePalette("ocean", Color(0xFF1565C0)),
        BubblePalette("forest", Color(0xFF2E7D32)),
        BubblePalette("violet", Color(0xFF6A3AB2)),
        BubblePalette("sunset", Color(0xFFE65100)),
        BubblePalette("graphite", Color(0xFF455A64)),
        BubblePalette("rose", Color(0xFFC2185B)),
    )

    /** A two-stop background gradient with per-mode variants, kept subtle via alpha. */
    data class GradientPreset(
        val id: String,
        val light: Pair<Color, Color>,
        val dark: Pair<Color, Color>,
    )

    val gradientPresets: List<GradientPreset> = listOf(
        GradientPreset(
            "dawn",
            light = Color(0xFFFFC371) to Color(0xFFFF5F6D),
            dark = Color(0xFF5C4A72) to Color(0xFF2E1F3A),
        ),
        GradientPreset(
            "ocean",
            light = Color(0xFF74EBD5) to Color(0xFF9FACE6),
            dark = Color(0xFF16455C) to Color(0xFF0E2233),
        ),
        GradientPreset(
            "meadow",
            light = Color(0xFFC5E1A5) to Color(0xFF80CBC4),
            dark = Color(0xFF1E4633) to Color(0xFF10281E),
        ),
        GradientPreset(
            "blush",
            light = Color(0xFFF8BBD0) to Color(0xFFB39DDB),
            dark = Color(0xFF4A2A3D) to Color(0xFF231430),
        ),
        GradientPreset(
            "dusk",
            light = Color(0xFF90A4AE) to Color(0xFF546E7A),
            dark = Color(0xFF2C3E50) to Color(0xFF10171E),
        ),
    )

    data class Snapshot(
        val patternId: Int = DEFAULT_PATTERN,
        val patternOpacity: Float = DEFAULT_PATTERN_OPACITY,
        /** Gradient preset id, or "none" for a flat background. */
        val gradientId: String = DEFAULT_GRADIENT,
        /** 0..1, mapped to a subtle alpha range at render time. */
        val gradientIntensity: Float = DEFAULT_GRADIENT_INTENSITY,
        val bubbleId: String = DEFAULT_BUBBLE,
    ) {
        val isDefault: Boolean
            get() = patternId == DEFAULT_PATTERN &&
                patternOpacity == DEFAULT_PATTERN_OPACITY &&
                gradientId == DEFAULT_GRADIENT &&
                gradientIntensity == DEFAULT_GRADIENT_INTENSITY &&
                bubbleId == DEFAULT_BUBBLE

        val bubbleColor: Color
            get() = bubblePalettes.firstOrNull { it.id == bubbleId }?.color
                ?: bubblePalettes.first().color

        fun gradientColors(isDark: Boolean): Pair<Color, Color>? {
            val preset = gradientPresets.firstOrNull { it.id == gradientId } ?: return null
            return if (isDark) preset.dark else preset.light
        }

        /** Gradient layer alpha — intensity mapped into a deliberately subtle band. */
        val gradientAlpha: Float
            get() = 0.06f + gradientIntensity.coerceIn(0f, 1f) * 0.22f
    }

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot

    private lateinit var appContext: Context

    /** Idempotent; call once from Application.onCreate. */
    fun init(context: Context, scope: CoroutineScope) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        scope.launch {
            appContext.chatThemeDataStore.data.collect { prefs ->
                _snapshot.value = parse(prefs)
            }
        }
    }

    suspend fun setPattern(id: Int) = edit {
        it[PATTERN] = id.coerceIn(1, PATTERN_COUNT)
    }

    suspend fun setPatternOpacity(value: Float) = edit {
        it[PATTERN_OPACITY] = value.coerceIn(MIN_PATTERN_OPACITY, MAX_PATTERN_OPACITY)
    }

    suspend fun setGradient(id: String) = edit { it[GRADIENT] = id }

    suspend fun setGradientIntensity(value: Float) = edit {
        it[GRADIENT_INTENSITY] = value.coerceIn(0f, 1f)
    }

    suspend fun setBubble(id: String) = edit { it[BUBBLE] = id }

    /** "Reset theme" — back to pattern 1, default opacity, no gradient, Klic red. */
    suspend fun reset() = edit {
        it.remove(PATTERN)
        it.remove(PATTERN_OPACITY)
        it.remove(GRADIENT)
        it.remove(GRADIENT_INTENSITY)
        it.remove(BUBBLE)
    }

    private val PATTERN = intPreferencesKey("pattern_id")
    private val PATTERN_OPACITY = floatPreferencesKey("pattern_opacity")
    private val GRADIENT = stringPreferencesKey("gradient_id")
    private val GRADIENT_INTENSITY = floatPreferencesKey("gradient_intensity")
    private val BUBBLE = stringPreferencesKey("bubble_id")

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        appContext.chatThemeDataStore.edit(block)
    }

    private fun parse(prefs: Preferences): Snapshot = Snapshot(
        patternId = (prefs[PATTERN] ?: DEFAULT_PATTERN).coerceIn(1, PATTERN_COUNT),
        patternOpacity = (prefs[PATTERN_OPACITY] ?: DEFAULT_PATTERN_OPACITY)
            .coerceIn(MIN_PATTERN_OPACITY, MAX_PATTERN_OPACITY),
        gradientId = prefs[GRADIENT] ?: DEFAULT_GRADIENT,
        gradientIntensity = (prefs[GRADIENT_INTENSITY] ?: DEFAULT_GRADIENT_INTENSITY).coerceIn(0f, 1f),
        bubbleId = prefs[BUBBLE] ?: DEFAULT_BUBBLE,
    )
}
