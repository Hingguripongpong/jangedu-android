package com.example.janggiai.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.janggiai.data.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B5D50), onPrimary = Color.White,
    primaryContainer = Color(0xFFBFE1D1), onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF8A4B2E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCB), onSecondaryContainer = Color(0xFF341100),
    surface = Color(0xFFFBF8F3), onSurface = Color(0xFF1C1B19), surfaceVariant = Color(0xFFE4E1DA),
    onSurfaceVariant = Color(0xFF474540), background = Color(0xFFFBF8F3), onBackground = Color(0xFF1C1B19),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA3C5B5), onPrimary = Color(0xFF0B3629),
    primaryContainer = Color(0xFF244D40), onPrimaryContainer = Color(0xFFBFE1D1),
    secondary = Color(0xFFFFB68F), onSecondary = Color(0xFF552100),
    secondaryContainer = Color(0xFF6E3419), onSecondaryContainer = Color(0xFFFFDBCB),
    surface = Color(0xFF131312), onSurface = Color(0xFFE5E2DD), surfaceVariant = Color(0xFF474540),
    onSurfaceVariant = Color(0xFFC8C5BE), background = Color(0xFF131312), onBackground = Color(0xFFE5E2DD),
)

/** Colours of the board itself (kept out of the M3 scheme so the wood look survives dynamic colour). */
data class BoardPalette(
    val background: Color, val line: Color, val cho: Color, val han: Color, val pieceFace: Color, val pieceEdge: Color,
    val selected: Color, val legal: Color, val lastMove: Color, val check: Color,
    val candidateBest: Color, val candidateGood: Color, val candidateOther: Color, val candidateText: Color, val arrow: Color,
)

val LightBoard = BoardPalette(
    background = Color(0xFFE9CFA0), line = Color(0xFF5B4327), cho = Color(0xFF1F6F3D), han = Color(0xFFB2262B),
    pieceFace = Color(0xFFFFF6E5), pieceEdge = Color(0xFF6B4E2A), selected = Color(0xFF1E88E5), legal = Color(0x991E88E5),
    lastMove = Color(0x88FFB300), check = Color(0xFFE53935), candidateBest = Color(0xFF2E7D32), candidateGood = Color(0xFF558B2F),
    candidateOther = Color(0xFF8D6E63), candidateText = Color.White, arrow = Color(0xCC1565C0),
)

val DarkBoard = BoardPalette(
    background = Color(0xFF6B5335), line = Color(0xFFE8D8B8), cho = Color(0xFF4CAF7A), han = Color(0xFFEF6B6B),
    pieceFace = Color(0xFF2B2622), pieceEdge = Color(0xFFD9C7A5), selected = Color(0xFF64B5F6), legal = Color(0x9964B5F6),
    lastMove = Color(0x88FFD54F), check = Color(0xFFFF5252), candidateBest = Color(0xFF66BB6A), candidateGood = Color(0xFF9CCC65),
    candidateOther = Color(0xFFBCAAA4), candidateText = Color(0xFF111111), arrow = Color(0xCC90CAF9),
)

val LocalBoardPalette = staticCompositionLocalOf { LightBoard }

@Composable
fun JanggiTheme(mode: ThemeMode, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> DarkColors
        else -> LightColors
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalBoardPalette provides if (dark) DarkBoard else LightBoard) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
