package io.github.taxray.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF047857), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF3E9), onPrimaryContainer = Color(0xFF07513E),
    secondary = Color(0xFF47665C), secondaryContainer = Color(0xFFE8EFEB),
    background = Color(0xFFF8F9FA), onBackground = Color(0xFF0F172A),
    surface = Color.White, onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF0F3F4), onSurfaceVariant = Color(0xFF586A7F),
    outline = Color(0xFF718093), outlineVariant = Color(0xFFE2E8F0)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF34D399), onPrimary = Color(0xFF003826),
    primaryContainer = Color(0xFF133C30), onPrimaryContainer = Color(0xFFB3F5D6),
    secondary = Color(0xFFA8CBBB), secondaryContainer = Color(0xFF263C33),
    background = Color(0xFF09090B), onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF18181B), onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF24272C), onSurfaceVariant = Color(0xFFABBACC),
    outline = Color(0xFF8896A7), outlineVariant = Color(0xFF343940)
)
val AmountStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")

@Composable
fun TaxyRayTheme(content: @Composable () -> Unit) {
    val typography = Typography(
        headlineLarge = AmountStyle.copy(fontSize = 36.sp, lineHeight = 44.sp),
        headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    )
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, typography = typography, content = content)
}
