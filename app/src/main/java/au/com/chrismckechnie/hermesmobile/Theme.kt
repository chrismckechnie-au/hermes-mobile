package au.com.chrismckechnie.hermesmobile

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Hermes Precision Utility palette: compact, calm and operationally clear.
 * Material components provide the platform grammar while these tokens keep
 * the Command Deck recognisably Hermes. All normal-sized accent text and
 * solid accent controls meet WCAG AA contrast in both themes.
 *
 * Abyss/Cream remain as compatibility names while call sites migrate to the
 * semantic Canvas/Accent* roles below.
 */
class HermesPalette(
    val isDark: Boolean,
    // Ground
    val Abyss: Color, // canvas (iOS systemGroupedBackground)
    val AbyssTop: Color, // vertical gradient start (equal = flat)
    // Raised surfaces, weakest to strongest (iOS secondary/tertiary fills)
    val SurfaceLow: Color,
    val SurfaceOne: Color,
    val SurfaceTwo: Color,
    val SurfaceHigh: Color,
    val BubbleUser: Color, // iMessage-style user bubble
    // Accent (iOS system blue)
    val Cream: Color,
    val CreamSoft: Color,
    val CreamDim: Color,
    val OnAccent: Color, // text/icons on solid accent fills
    // Status green (connection OK)
    val Ok: Color,
    // Text (iOS label/secondaryLabel)
    val TextPrimary: Color,
    val TextSoft: Color,
    val Muted: Color, // >=4.5:1 on Abyss
    // Lines: decorative hairlines; interactive borders use FocusRing (3:1+)
    val Line: Color,
    val LineStrong: Color,
    val FocusRing: Color,
    // Status (iOS system orange/red/green)
    val Warn: Color,
    val Error: Color,
    val ErrorSoft: Color, // error text over the tinted error banner
    val Tool: Color,
    // Atmosphere (kept for the backdrop pipeline; Apple look = off)
    val WarmGlow: Color,
    val GrainAlpha: Float,
    val Scrim: Color,
    val ColorScheme: ColorScheme,
) {
    val Canvas: Color get() = Abyss
    val AccentFill: Color get() = Cream
    val AccentText: Color get() = CreamSoft
    val AccentSubtle: Color get() = CreamDim

    val Mono = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    )

    // Text styles — every Text/BasicTextField call site uses one of these.
    val ScreenTitle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.35).sp, color = TextPrimary)
    val SheetTitle = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, color = TextPrimary)
    val CardTitle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
    val Body = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, color = TextSoft)
    val BodyMuted = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, color = Muted)
    val Label = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
    val Micro = TextStyle(fontSize = 9.sp, letterSpacing = 1.2.sp, color = Muted)
    val MicroBold = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = AccentText)
    val Action = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = AccentText)
    val Status = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = TextSoft)
    val MonoBody = TextStyle(fontFamily = Mono, fontSize = 11.sp, color = Muted)
    val MonoSmall = TextStyle(fontFamily = Mono, fontSize = 9.sp, color = Muted)

    // Shapes — iOS-like continuous rounding
    val RadiusSmall = 8.dp
    val RadiusCard = 12.dp
    val RadiusSheet = 16.dp

    // Shared density and interaction scale.
    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 12.dp
    val SpaceLg = 16.dp
    val SpaceXl = 24.dp
    val ControlMin = 48.dp
    val ControlComfortable = 56.dp
    val IconSm = 16.dp
    val IconMd = 20.dp
    val IconLg = 24.dp
}

/** iOS dark: true black, elevated grays, system blue. Every M3 role explicit. */
val HermesDark = HermesPalette(
    isDark = true,
    Abyss = Color(0xFF000000),
    AbyssTop = Color(0xFF000000),
    SurfaceLow = Color(0xFF1C1C1E),
    SurfaceOne = Color(0xFF1C1C1E),
    SurfaceTwo = Color(0xFF2C2C2E),
    SurfaceHigh = Color(0xFF3A3A3C),
    BubbleUser = Color(0xFF409CFF),
    Cream = Color(0xFF409CFF),
    CreamSoft = Color(0xFF64B5FF),
    CreamDim = Color(0xFF5EA2EF),
    OnAccent = Color(0xFF000000),
    Ok = Color(0xFF30D158),
    TextPrimary = Color(0xFFFFFFFF),
    TextSoft = Color(0xFFEBEBF0),
    Muted = Color(0xFF98989F),
    Line = Color(0x26FFFFFF),
    LineStrong = Color(0x40FFFFFF),
    FocusRing = Color(0x660A84FF),
    Warn = Color(0xFFFF9F0A),
    Error = Color(0xFFFF453A),
    ErrorSoft = Color(0xFFFFB3AD),
    Tool = Color(0xFF30D158),
    WarmGlow = Color(0x00000000),
    GrainAlpha = 0f,
    Scrim = Color(0xB8000000),
    ColorScheme = darkColorScheme(
        primary = Color(0xFF409CFF),
        onPrimary = Color(0xFF000000),
        primaryContainer = Color(0xFF123B63),
        onPrimaryContainer = Color(0xFFB8D9FF),
        inversePrimary = Color(0xFF007AFF),
        secondary = Color(0xFF5EA2EF),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFF2C2C2E),
        onSecondaryContainer = Color(0xFFEBEBF0),
        tertiary = Color(0xFF30D158),
        onTertiary = Color(0xFF000000),
        tertiaryContainer = Color(0xFF11331C),
        onTertiaryContainer = Color(0xFFA8E8BC),
        background = Color(0xFF000000),
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF1C1C1E),
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF2C2C2E),
        onSurfaceVariant = Color(0xFF98989F),
        surfaceTint = Color(0xFF409CFF),
        inverseSurface = Color(0xFFF2F2F7),
        inverseOnSurface = Color(0xFF1C1C1E),
        error = Color(0xFFFF453A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFF3A1512),
        onErrorContainer = Color(0xFFFFB3AD),
        outline = Color(0xFF48484A),
        outlineVariant = Color(0xFF2C2C2E),
        scrim = Color.Black,
        surfaceBright = Color(0xFF3A3A3C),
        surfaceDim = Color(0xFF000000),
        surfaceContainer = Color(0xFF1C1C1E),
        surfaceContainerHigh = Color(0xFF2C2C2E),
        surfaceContainerHighest = Color(0xFF3A3A3C),
        surfaceContainerLow = Color(0xFF141416),
        surfaceContainerLowest = Color(0xFF000000),
    ),
)

/** iOS light: white cards on grouped gray, system blue. */
val HermesLight = HermesPalette(
    isDark = false,
    Abyss = Color(0xFFF2F2F7),
    AbyssTop = Color(0xFFF2F2F7),
    SurfaceLow = Color(0xFFFFFFFF),
    SurfaceOne = Color(0xFFFFFFFF),
    SurfaceTwo = Color(0xFFE5E5EA),
    SurfaceHigh = Color(0xFFD1D1D6),
    BubbleUser = Color(0xFF005FCC),
    Cream = Color(0xFF005FCC),
    CreamSoft = Color(0xFF0057B8),
    CreamDim = Color(0xFF2F7CD6),
    OnAccent = Color(0xFFFFFFFF),
    Ok = Color(0xFF248A3D),
    TextPrimary = Color(0xFF000000),
    TextSoft = Color(0xFF1C1C1E),
    Muted = Color(0xFF6D6D72),
    Line = Color(0x1F000000),
    LineStrong = Color(0x33000000),
    FocusRing = Color(0x66007AFF),
    Warn = Color(0xFFC93400), // darkened system orange for text contrast on white
    Error = Color(0xFFC62828),
    ErrorSoft = Color(0xFF99271D),
    Tool = Color(0xFF248A3D), // darkened system green for text contrast
    WarmGlow = Color(0x00000000),
    GrainAlpha = 0f,
    Scrim = Color(0x66000000),
    ColorScheme = lightColorScheme(
        primary = Color(0xFF005FCC),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD6E8FF),
        onPrimaryContainer = Color(0xFF00325E),
        inversePrimary = Color(0xFF0A84FF),
        secondary = Color(0xFF2F7CD6),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFE5E5EA),
        onSecondaryContainer = Color(0xFF1C1C1E),
        tertiary = Color(0xFF248A3D),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFD3F2DC),
        onTertiaryContainer = Color(0xFF0E3D1D),
        background = Color(0xFFF2F2F7),
        onBackground = Color(0xFF000000),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF000000),
        surfaceVariant = Color(0xFFE5E5EA),
        onSurfaceVariant = Color(0xFF6D6D72),
        surfaceTint = Color(0xFF005FCC),
        inverseSurface = Color(0xFF1C1C1E),
        inverseOnSurface = Color(0xFFF2F2F7),
        error = Color(0xFFFF3B30),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF5E1A12),
        outline = Color(0xFFC7C7CC),
        outlineVariant = Color(0xFFE5E5EA),
        scrim = Color.Black,
        surfaceBright = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFE5E5EA),
        surfaceContainer = Color(0xFFFFFFFF),
        surfaceContainerHigh = Color(0xFFF2F2F7),
        surfaceContainerHighest = Color(0xFFE5E5EA),
        surfaceContainerLow = Color(0xFFFFFFFF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
    ),
)

/** Active palette; HermesMobileApp provides it from ThemeMode + system setting. */
val LocalHermes = staticCompositionLocalOf { HermesDark }
