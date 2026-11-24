package io.voltset.sm8850.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

@Immutable
internal data class VoltSetSemanticColors(
    val goodContainer: Color,
    val onGoodContainer: Color,
    val goodAccent: Color,
    val warnContainer: Color,
    val onWarnContainer: Color,
    val warnBorder: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    val dangerAccent: Color,
    val subtleBorder: Color,
    val trackBackground: Color,
)

private val LightSemanticColors = VoltSetSemanticColors(
    goodContainer = Color(0xFFE6F6EE),
    onGoodContainer = Color(0xFF0A5C38),
    goodAccent = Color(0xFF15803D),
    warnContainer = Color(0xFFFFF4DE),
    onWarnContainer = Color(0xFF7A4100),
    warnBorder = Color(0xFFE5A93B),
    dangerContainer = Color(0xFFFDECEC),
    onDangerContainer = Color(0xFF8C1D18),
    dangerAccent = Color(0xFFC62828),
    subtleBorder = Color(0xFFE2E6EC),
    trackBackground = Color(0xFFEAF0F6),
)

private val DarkSemanticColors = VoltSetSemanticColors(
    goodContainer = Color(0xFF0F3524),
    onGoodContainer = Color(0xFF8CE2B3),
    goodAccent = Color(0xFF4ADE80),
    warnContainer = Color(0xFF3A280A),
    onWarnContainer = Color(0xFFFCD38D),
    warnBorder = Color(0xFFD97706),
    dangerContainer = Color(0xFF3F1616),
    onDangerContainer = Color(0xFFFCA5A5),
    dangerAccent = Color(0xFFF87171),
    subtleBorder = Color(0xFF2B313B),
    trackBackground = Color(0xFF252A34),
)

internal val LocalVoltSetColors = staticCompositionLocalOf { LightSemanticColors }

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1E40AF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBE7FF),
    onPrimaryContainer = Color(0xFF0F2557),
    secondary = Color(0xFF0F766E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF0B4641),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEDF1F7),
    onSurfaceVariant = Color(0xFF4B5563),
    outline = Color(0xFFD1D8E3),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8BB2FF),
    onPrimary = Color(0xFF072259),
    primaryContainer = Color(0xFF173678),
    onPrimaryContainer = Color(0xFFDBE7FF),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF063733),
    secondaryContainer = Color(0xFF115E59),
    onSecondaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF0E1218),
    onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF212731),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF333B48),
)

internal val TabularStyle = TextStyle(fontFeatureSettings = "tnum")

@Composable
internal fun VoltSetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val semantic = if (darkTheme) DarkSemanticColors else LightSemanticColors
    CompositionLocalProvider(LocalVoltSetColors provides semantic) {
        MaterialTheme(
            colorScheme = scheme,
            content = content,
        )
    }
}
