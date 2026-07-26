package uk.co.cinema.splmeter.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Same zinc/blue palette as the HTML reports, so the app and the report look
// like one thing.
private val Zinc950 = Color(0xFF0A0A0B)
private val Zinc900 = Color(0xFF18181B)
private val Zinc800 = Color(0xFF27272A)
private val Zinc400 = Color(0xFFA1A1AA)
private val Zinc200 = Color(0xFFE4E4E7)
private val Blue500 = Color(0xFF3B82F6)
private val Amber500 = Color(0xFFF59E0B)
private val Red500 = Color(0xFFEF4444)

val LevelGreen = Color(0xFF22C55E)
val LevelLime = Color(0xFF4ADE80)
val LevelYellow = Color(0xFFFACC15)
val LevelOrange = Color(0xFFF97316)
val LevelRed = Red500

fun levelColour(spl: Float): Color = when {
    spl.isNaN() -> Zinc400
    spl >= 100f -> LevelRed
    spl >= 90f -> LevelOrange
    spl >= 80f -> LevelYellow
    spl >= 70f -> LevelLime
    else -> LevelGreen
}

private val DarkColours = darkColorScheme(
    primary = Blue500,
    onPrimary = Color.White,
    secondary = Amber500,
    background = Zinc950,
    onBackground = Zinc200,
    surface = Zinc900,
    onSurface = Zinc200,
    surfaceVariant = Zinc800,
    onSurfaceVariant = Zinc400,
    error = Red500
)

@Composable
fun SplTheme(content: @Composable () -> Unit) {
    // The app is used in dark rooms; light mode would be actively unhelpful,
    // but respect the system setting for everything else.
    val colours = if (isSystemInDarkTheme()) DarkColours else DarkColours
    MaterialTheme(colorScheme = colours, content = content)
}
