package com.ktu.aigaleri.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * T-022: Stitch tasarımının (mobile/docs/design/.../authentic_system_utility/DESIGN.md) TEK tema dosyası.
 * Renk, tipografi, şekil ve boşluk değerleri yalnızca burada tanımlanır; ekranlar MaterialTheme.colorScheme /
 * typography / shapes ile [AiGaleriDimens] üzerinden okur (dağınık renk veya sihirli sayı yok).
 *
 * Kaynak notu: DESIGN.md'nin YAML başlığı ile "Colors" düzyazısı açık temada çelişir (başlık: primary #004E58,
 * background #F9FAF5; düzyazı: primary #006874, background #FDFCF7). Ekran görüntüleri ve Stitch'in Tailwind
 * yapılandırması başlıktaki değerlerle üretilmiştir; açık tema onlardan alındı. Koyu tema yalnızca düzyazıda
 * tanımlıdır; düzyazıda olmayan roller (ikincil/üçüncül, hata kapsayıcısı, container-lowest/low/highest, bright)
 * başlıktaki "fixed" tonlarından ve Material 3 koyu tonal eşlemesinden TÜRETİLDİ (aşağıda "türetildi").
 * Tema anahtarı yoktur: koyu tema sistem ayarını izler.
 */

// ---- Açık tema (DESIGN.md YAML başlığı) ----
internal val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF004E58),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF006874),
    onPrimaryContainer = Color(0xFF97E4F2),
    inversePrimary = Color(0xFF85D2E0),
    secondary = Color(0xFF4A6267),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE7ED),
    onSecondaryContainer = Color(0xFF50686D),
    tertiary = Color(0xFF3A4664),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF525E7D),
    onTertiaryContainer = Color(0xFFCCD8FD),
    background = Color(0xFFF9FAF5),
    onBackground = Color(0xFF1A1C19),
    surface = Color(0xFFF9FAF5),
    onSurface = Color(0xFF1A1C19),
    surfaceVariant = Color(0xFFE2E3DE),
    onSurfaceVariant = Color(0xFF3F484A),
    surfaceTint = Color(0xFF016874),
    inverseSurface = Color(0xFF2F312E),
    inverseOnSurface = Color(0xFFF0F1EC),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF6F797B),
    outlineVariant = Color(0xFFBEC8CA),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF9FAF5),
    surfaceDim = Color(0xFFD9DAD6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F4EF),
    surfaceContainer = Color(0xFFEDEEE9),
    surfaceContainerHigh = Color(0xFFE8E9E4),
    surfaceContainerHighest = Color(0xFFE2E3DE),
)

// ---- Koyu tema (DESIGN.md "Dark Mode Roles"; "türetildi" işaretliler düzyazıda yok) ----
internal val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF80D5E3),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF97F0FF),
    inversePrimary = Color(0xFF004E58), // türetildi: açık temanın primary'si
    secondary = Color(0xFFB1CBD1), // türetildi: başlıktaki secondary-fixed-dim
    onSecondary = Color(0xFF1C3438), // türetildi
    secondaryContainer = Color(0xFF334B4F), // türetildi: on-secondary-fixed-variant
    onSecondaryContainer = Color(0xFFCDE7ED), // türetildi: secondary-fixed
    tertiary = Color(0xFFBAC6EA), // türetildi: tertiary-fixed-dim
    onTertiary = Color(0xFF242F4C), // türetildi
    tertiaryContainer = Color(0xFF3A4664), // türetildi: on-tertiary-fixed-variant
    onTertiaryContainer = Color(0xFFD9E2FF), // türetildi: tertiary-fixed
    background = Color(0xFF121312),
    onBackground = Color(0xFFE2E3DE),
    surface = Color(0xFF1A1C1A),
    onSurface = Color(0xFFE2E3DE),
    surfaceVariant = Color(0xFF454744), // türetildi: outline-variant ile aynı
    onSurfaceVariant = Color(0xFFC5C7C3),
    surfaceTint = Color(0xFF80D5E3),
    inverseSurface = Color(0xFFE2E3DE), // türetildi
    inverseOnSurface = Color(0xFF2F312E), // türetildi
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005), // türetildi (Material 3 koyu hata eşlemesi)
    errorContainer = Color(0xFF93000A), // türetildi
    onErrorContainer = Color(0xFFFFDAD6), // türetildi
    outline = Color(0xFF8F918D),
    outlineVariant = Color(0xFF454744),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF383A37), // türetildi
    surfaceDim = Color(0xFF121312), // türetildi: background ile aynı
    surfaceContainerLowest = Color(0xFF0D0F0D), // türetildi
    surfaceContainerLow = Color(0xFF1D1F1D), // türetildi: surface ile container arası
    surfaceContainer = Color(0xFF212321),
    surfaceContainerHigh = Color(0xFF2B2D2B),
    surfaceContainerHighest = Color(0xFF363836), // türetildi
)

/** ColorScheme dışı, tasarımdan gelen sabitler (tam ekran görüntü tuvali). Her iki temada aynıdır. */
object AiGaleriExtras {
    /** Büyük görünüm tuvali (Stitch viewer: #0d0e0d). */
    val ViewerCanvas = Color(0xFF0D0E0D)
    val OnViewerCanvas = Color(0xFFFFFFFF)

    /** Tuval üstündeki yüzen kontrollerin arka planı (DESIGN.md rozet: rgba(0,0,0,0.60)). */
    val ViewerScrim = Color(0x99000000)
}

// ---- Tipografi: Roboto / sistem sans (Roboto Flex cihazda yoksa Roboto); SERİF DEĞİL ----
private val AppFontFamily: FontFamily = FontFamily.SansSerif

private fun type(sizeSp: Int, lineSp: Int, weight: Int, letterSpacingSp: Float = 0f) = TextStyle(
    fontFamily = AppFontFamily,
    fontSize = sizeSp.sp,
    lineHeight = lineSp.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = letterSpacingSp.sp,
)

internal val AiGaleriTypography = Typography(
    headlineLarge = type(32, 40, 400),
    headlineMedium = type(28, 36, 400),
    headlineSmall = type(24, 32, 400),
    titleLarge = type(22, 28, 500),
    titleMedium = type(16, 24, 500, 0.15f),
    titleSmall = type(14, 20, 500, 0.1f),
    bodyLarge = type(16, 24, 400, 0.5f),
    bodyMedium = type(14, 20, 400, 0.25f),
    bodySmall = type(12, 16, 400, 0.4f),
    labelLarge = type(14, 20, 500, 0.1f),
    labelMedium = type(12, 16, 500, 0.5f),
    labelSmall = type(11, 16, 500, 0.5f),
)

// ---- Şekil (DESIGN.md "rounded": sm 4, DEFAULT 8, md 12, lg 16, xl 24) ----
internal val AiGaleriShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Boşluk ve bileşen ölçüleri (DESIGN.md "spacing", "Layout & Spacing", "Components"). */
object AiGaleriDimens {
    val Gutter: Dp = 2.dp
    val Margin: Dp = 16.dp
    val SpaceXs: Dp = 4.dp
    val SpaceSm: Dp = 8.dp
    val SpaceMd: Dp = 16.dp
    val SpaceLg: Dp = 24.dp
    val SpaceXl: Dp = 32.dp

    /** Erişilebilirlik: asgari dokunma hedefi. */
    val MinTouchTarget: Dp = 48.dp

    const val GridColumns: Int = 3

    val TopBarHeight: Dp = 64.dp
    val SearchBarHeight: Dp = 56.dp
    val SearchBarCorner: Dp = 28.dp
    val ButtonCorner: Dp = 20.dp
    val ProgressLineThin: Dp = 2.dp
    val ProgressLine: Dp = 4.dp
    val StatusDot: Dp = 8.dp
    val IconSmall: Dp = 18.dp
    val IconChevron: Dp = 16.dp
    val IconCircleLarge: Dp = 96.dp
    val IconCircleLargeGlyph: Dp = 48.dp
    val IconCircleMedium: Dp = 64.dp
    val IconCircleMediumGlyph: Dp = 32.dp
    val IconCircleSmall: Dp = 40.dp

    /** Tek sütun içerik (izin, boş durum) için okunaklı azami genişlik. */
    val ContentMaxWidth: Dp = 384.dp
}

/** Uygulama teması: açık/koyu sistem ayarını izler; tema anahtarı yoktur. */
@Composable
fun AiGaleriTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AiGaleriTypography,
        shapes = AiGaleriShapes,
        content = content,
    )
}
