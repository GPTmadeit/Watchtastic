package com.watchtastic.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.MotionScheme

/**
 * Meshtastic's brand anchors, extended into a full Wear Material 3 scheme.
 *
 * Two constraints drive the palette:
 *
 *  - **True black background.** The Pixel Watch panel is OLED, so `#000000` costs no
 *    power and gives the green maximum apparent contrast. Surfaces step up from black
 *    rather than down from grey.
 *  - **Green is for signal, not decoration.** `MeshGreen` marks live, connected,
 *    delivered, in-range. Anything neutral stays in the slate scale so a glance at the
 *    wrist reads state without reading words.
 */
object MeshPalette {
    /** Meshtastic brand green. */
    val MeshGreen = Color(0xFF67EA94)
    val MeshGreen600 = Color(0xFF3FB86D)
    val MeshGreen700 = Color(0xFF2D8F52)
    val MeshGreenDeep = Color(0xFF16452B)
    val MeshGreenPale = Color(0xFFC9FBDC)

    /** Meshtastic brand ink, and the neutral scale built around it. */
    val Ink = Color(0xFF2C2D3C)
    val InkDeep = Color(0xFF13141B)
    val Slate = Color(0xFFA8B0C8)
    val SlateDim = Color(0xFF7C86A2)

    /** Reserved for telemetry and "heard a while ago" states. */
    val Amber = Color(0xFFF0B559)
    val AmberDeep = Color(0xFF4A3413)

    val Danger = Color(0xFFFF6B6B)
    val DangerDim = Color(0xFFC94F4F)
    val DangerDeep = Color(0xFF4A1620)
}

private val WatchtasticColors = ColorScheme(
    primary = MeshPalette.MeshGreen,
    primaryDim = MeshPalette.MeshGreen600,
    primaryContainer = MeshPalette.MeshGreenDeep,
    onPrimary = Color(0xFF05150C),
    onPrimaryContainer = MeshPalette.MeshGreenPale,

    secondary = MeshPalette.Slate,
    secondaryDim = MeshPalette.SlateDim,
    secondaryContainer = MeshPalette.Ink,
    onSecondary = Color(0xFF14151C),
    onSecondaryContainer = Color(0xFFD6DCEC),

    tertiary = MeshPalette.Amber,
    tertiaryDim = Color(0xFFC08F3F),
    tertiaryContainer = MeshPalette.AmberDeep,
    onTertiary = Color(0xFF231704),
    onTertiaryContainer = Color(0xFFFFE0B0),

    // Cards and list rows: three steps above true black, so stacked surfaces stay
    // legible without any of them glowing on a dark wrist at night.
    surfaceContainerLow = Color(0xFF101119),
    surfaceContainer = Color(0xFF171821),
    surfaceContainerHigh = Color(0xFF23252F),
    onSurface = Color(0xFFE6E9F2),
    onSurfaceVariant = Color(0xFFA7AEC1),

    outline = Color(0xFF5A6076),
    outlineVariant = Color(0xFF383D4D),

    background = Color.Black,
    onBackground = Color(0xFFE6E9F2),

    error = MeshPalette.Danger,
    errorDim = MeshPalette.DangerDim,
    errorContainer = MeshPalette.DangerDeep,
    onError = Color(0xFF2A0004),
    onErrorContainer = Color(0xFFFFD9DC),
)

@Composable
fun WatchtasticTheme(content: @Composable () -> Unit) {
    // Wear Material 3's default typography is already tuned per screen size and is
    // deliberately left alone; overriding it would break its responsive scaling.
    //
    // The motion scheme is not left alone. `expressive()` swaps Material's duration-based
    // easing for spring physics across every component at once — buttons, cards, dialogs,
    // the edge button, and the morphing in TransformingLazyColumn. One line changes how
    // the whole app moves, and springs are what make a watch UI feel like it has weight
    // rather than like it is fading between slides.
    MaterialTheme(
        colorScheme = WatchtasticColors,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
