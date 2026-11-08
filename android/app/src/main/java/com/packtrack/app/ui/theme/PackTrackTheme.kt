package com.packtrack.app.ui.theme

import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class AccentPreset(
    val key: String,
    val label: String,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color
) {
    EMERALD(
        key = "emerald",
        label = "Emerald",
        primary = Color(0xFF10B981),
        onPrimary = Color(0xFF042F2E),
        primaryContainer = Color(0xFF064E3B),
        onPrimaryContainer = Color(0xFFA7F3D0)
    ),
    SKY(
        key = "sky",
        label = "Sky",
        primary = Color(0xFF0EA5E9),
        onPrimary = Color(0xFF082F49),
        primaryContainer = Color(0xFF0C4A6E),
        onPrimaryContainer = Color(0xFFE0F2FE)
    ),
    VIOLET(
        key = "violet",
        label = "Violet",
        primary = Color(0xFF8B5CF6),
        onPrimary = Color(0xFF1E1B4B),
        primaryContainer = Color(0xFF4C1D95),
        onPrimaryContainer = Color(0xFFEDE9FE)
    ),
    AMBER(
        key = "amber",
        label = "Amber",
        primary = Color(0xFFF59E0B),
        onPrimary = Color(0xFF271700),
        primaryContainer = Color(0xFF78350F),
        onPrimaryContainer = Color(0xFFFEF3C7)
    ),
    ROSE(
        key = "rose",
        label = "Rose",
        primary = Color(0xFFF43F5E),
        onPrimary = Color(0xFF310711),
        primaryContainer = Color(0xFF881337),
        onPrimaryContainer = Color(0xFFFFE4E6)
    );

    companion object {
        fun fromKey(key: String?): AccentPreset {
            return entries.firstOrNull { it.key == key } ?: EMERALD
        }
    }
}

object PackTrackThemeState {
    var currentAccent by mutableStateOf(AccentPreset.EMERALD)
    var accentHue by mutableStateOf(160f)
    var useCelsius by mutableStateOf(false)
    var customCategories by mutableStateOf<List<String>>(emptyList())

    val primaryColor: Color
        get() = Color.hsv(accentHue.coerceIn(0f, 360f), 0.78f, 0.90f)

    val onPrimaryColor: Color
        get() = Color(0xFF061318)

    val primaryContainerColor: Color
        get() = Color.hsv(accentHue.coerceIn(0f, 360f), 0.85f, 0.32f)

    val onPrimaryContainerColor: Color
        get() = Color.hsv(accentHue.coerceIn(0f, 360f), 0.28f, 0.96f)

    fun loadFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences("packtrack_prefs", Context.MODE_PRIVATE)
        currentAccent = AccentPreset.fromKey(prefs.getString("accent_theme", "emerald"))
        val defaultHue = when (currentAccent) {
            AccentPreset.EMERALD -> 160f
            AccentPreset.SKY -> 199f
            AccentPreset.VIOLET -> 258f
            AccentPreset.AMBER -> 38f
            AccentPreset.ROSE -> 350f
        }
        accentHue = prefs.getFloat("accent_hue", defaultHue).coerceIn(0f, 360f)
        useCelsius = prefs.getString("temp_unit", "F").equals("C", ignoreCase = true)
        val rawCats = prefs.getString("custom_categories", "") ?: ""
        customCategories = rawCats.split("|").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun setAccentHue(context: Context, hue: Float) {
        val clamped = hue.coerceIn(0f, 360f)
        accentHue = clamped
        context.getSharedPreferences("packtrack_prefs", Context.MODE_PRIVATE)
            .edit()
            .putFloat("accent_hue", clamped)
            .apply()
    }

    fun addCustomCategory(context: Context, rawCategory: String): String {
        val clean = rawCategory.trim()
        if (clean.isEmpty()) return "Others"
        val defaultNames = com.packtrack.app.domain.PackingCategory.orderedDisplayNames
        val existingMatch = (defaultNames + customCategories).firstOrNull { it.equals(clean, ignoreCase = true) }
        if (existingMatch != null) return existingMatch
        val updated = (customCategories + clean).distinct()
        customCategories = updated
        context.getSharedPreferences("packtrack_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("custom_categories", updated.joinToString("|"))
            .apply()
        return clean
    }

    fun allCategories(extraCategories: Iterable<String> = emptyList()): List<String> {
        val base = com.packtrack.app.domain.PackingCategory.orderedDisplayNames
        val result = mutableListOf<String>()
        result.addAll(base)
        for (cat in customCategories + extraCategories) {
            val clean = com.packtrack.app.domain.PackingCategory.canonicalize(cat)
            if (clean.isNotEmpty() && result.none { it.equals(clean, ignoreCase = true) }) {
                result.add(clean)
            }
        }
        return result
    }

    fun selectAccent(context: Context, preset: AccentPreset) {
        currentAccent = preset
        context.getSharedPreferences("packtrack_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("accent_theme", preset.key)
            .apply()
    }

    fun setTemperatureUnit(context: Context, celsius: Boolean) {
        useCelsius = celsius
        context.getSharedPreferences("packtrack_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("temp_unit", if (celsius) "C" else "F")
            .apply()
    }

    fun formatTempRange(lowF: Double, highF: Double): String {
        return if (useCelsius) {
            val lowC = kotlin.math.round((lowF - 32.0) * 5.0 / 9.0).toInt()
            val highC = kotlin.math.round((highF - 32.0) * 5.0 / 9.0).toInt()
            "${lowC}°–${highC}°C"
        } else {
            "${lowF.toInt()}°–${highF.toInt()}°F"
        }
    }
}

object PackTrackPalette {
    val Background = Color(0xFF0B0F17)
    val Surface = Color(0xFF131A26)
    val SurfaceElevated = Color(0xFF1A2333)
    val Border = Color(0xFF232F42)
    val Secondary = Color(0xFF38BDF8)
    val SecondaryContainer = Color(0xFF0C4A6E)
    val OnSecondaryContainer = Color(0xFFE0F2FE)
    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val Error = Color(0xFFF43F5E)
}

private val MinimalTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontSize = 19.sp,
        lineHeight = 25.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp
    ),
    titleSmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp
    )
)

private val MinimalShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun PackTrackTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = PackTrackThemeState.primaryColor,
        onPrimary = PackTrackThemeState.onPrimaryColor,
        primaryContainer = PackTrackThemeState.primaryContainerColor,
        onPrimaryContainer = PackTrackThemeState.onPrimaryContainerColor,
        secondary = PackTrackPalette.Secondary,
        onSecondary = Color(0xFF082F49),
        secondaryContainer = PackTrackPalette.SecondaryContainer,
        onSecondaryContainer = PackTrackPalette.OnSecondaryContainer,
        background = PackTrackPalette.Background,
        onBackground = PackTrackPalette.TextPrimary,
        surface = PackTrackPalette.Surface,
        onSurface = PackTrackPalette.TextPrimary,
        surfaceVariant = PackTrackPalette.SurfaceElevated,
        onSurfaceVariant = PackTrackPalette.TextSecondary,
        outline = PackTrackPalette.Border,
        error = PackTrackPalette.Error,
        onError = Color.White
    )

    MaterialTheme(
        colorScheme = scheme,
        typography = MinimalTypography,
        shapes = MinimalShapes,
        content = content
    )
}
