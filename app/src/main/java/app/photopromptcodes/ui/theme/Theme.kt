package app.photopromptcodes.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Darkroom palette: near-black, warm paper text, one tungsten-amber accent.
val NearBlack = Color(0xFF0D0C0A)
val SurfaceDark = Color(0xFF161310)
val SurfaceRaised = Color(0xFF1E1A15)
val Paper = Color(0xFFF5EFE4)
val PaperDim = Color(0xFFB3A893)
val Amber = Color(0xFFE8A33D)
val AmberDeep = Color(0xFF9A6420)
val Danger = Color(0xFFE06C5B)
val Success = Color(0xFF8FC36A)

val Mono = FontFamily.Monospace

private val DarkScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1A1206),
    secondary = PaperDim,
    background = NearBlack,
    onBackground = Paper,
    surface = SurfaceDark,
    onSurface = Paper,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = PaperDim,
    outline = Color(0x29FFFFFF),
    outlineVariant = Color(0x14FFFFFF),
    error = Danger,
    onError = Color(0xFF1A0B08)
)

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = (-0.25).sp,
        color = Paper
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        color = Paper
    ),
    bodyMedium = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        color = Paper
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = PaperDim
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
        color = PaperDim
    )
)

@Composable
fun PhotoPromptCodesTheme(content: @Composable () -> Unit) {
    androidx.compose.material3.MaterialTheme(
        colorScheme = DarkScheme,
        typography = AppTypography,
        content = content
    )
}
