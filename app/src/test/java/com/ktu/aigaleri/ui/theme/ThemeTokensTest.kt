package com.ktu.aigaleri.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-022: tek tema dosyasındaki (Theme.kt) token'lar DESIGN.md ile eşleşir; açık ve koyu şemalar tutarlıdır;
 * tipografi serif değildir; metin/zemin çiftleri okunaklı kontrasttadır (WCAG AA 4.5:1).
 */
class ThemeTokensTest {
    private val appDir = File(System.getProperty("user.dir")!!)
    private val designMd = File(appDir.parentFile, "docs/design/stitch_ai_galeri_on_device_search_app/authentic_system_utility/DESIGN.md")

    private fun hex(c: Color): String {
        val argb = (c.red * 255f + 0.5f).toInt() shl 16 or ((c.green * 255f + 0.5f).toInt() shl 8) or (c.blue * 255f + 0.5f).toInt()
        return "#%06x".format(argb)
    }

    private fun lin(v: Float): Double = if (v <= 0.04045f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)

    private fun luminance(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** DESIGN.md YAML başlığındaki `colors:` bloğu (ad -> #rrggbb). */
    private fun designColors(): Map<String, String> {
        val lines = designMd.readLines()
        val start = lines.indexOfFirst { it.trim() == "colors:" }
        val map = linkedMapOf<String, String>()
        for (line in lines.drop(start + 1)) {
            val m = Regex("""^\s{2}([a-z-]+): '(#[0-9a-fA-F]{6})'""").find(line) ?: break
            map[m.groupValues[1]] = m.groupValues[2].lowercase()
        }
        return map
    }

    private fun roles(s: ColorScheme): Map<String, Color> = mapOf(
        "primary" to s.primary, "on-primary" to s.onPrimary,
        "primary-container" to s.primaryContainer, "on-primary-container" to s.onPrimaryContainer,
        "inverse-primary" to s.inversePrimary,
        "secondary" to s.secondary, "on-secondary" to s.onSecondary,
        "secondary-container" to s.secondaryContainer, "on-secondary-container" to s.onSecondaryContainer,
        "tertiary" to s.tertiary, "on-tertiary" to s.onTertiary,
        "tertiary-container" to s.tertiaryContainer, "on-tertiary-container" to s.onTertiaryContainer,
        "background" to s.background, "on-background" to s.onBackground,
        "surface" to s.surface, "on-surface" to s.onSurface,
        "surface-variant" to s.surfaceVariant, "on-surface-variant" to s.onSurfaceVariant,
        "surface-tint" to s.surfaceTint,
        "inverse-surface" to s.inverseSurface, "inverse-on-surface" to s.inverseOnSurface,
        "error" to s.error, "on-error" to s.onError,
        "error-container" to s.errorContainer, "on-error-container" to s.onErrorContainer,
        "outline" to s.outline, "outline-variant" to s.outlineVariant,
        "surface-bright" to s.surfaceBright, "surface-dim" to s.surfaceDim,
        "surface-container-lowest" to s.surfaceContainerLowest, "surface-container-low" to s.surfaceContainerLow,
        "surface-container" to s.surfaceContainer, "surface-container-high" to s.surfaceContainerHigh,
        "surface-container-highest" to s.surfaceContainerHighest,
    )

    @Test
    fun lightScheme_matchesEveryColorTokenInDesignMdHeader() {
        val design = designColors()
        assertTrue("DESIGN.md renkleri okunamadı", design.size > 30)
        val light = roles(LightColors)
        // Başlıktaki Material rollerinin hepsi (fixed tonları ColorScheme'de yok) açık şemada aynen bulunmalı.
        val mapped = design.filterKeys { it in light.keys }
        assertTrue("eşleşen rol sayısı az: ${mapped.keys}", mapped.size >= 34)
        for ((name, value) in mapped) assertEquals("açık tema rolü $name", value, hex(light.getValue(name)))
    }

    @Test
    fun darkScheme_matchesDesignMdDarkRoles() {
        val d = DarkColors
        assertEquals("#80d5e3", hex(d.primary))
        assertEquals("#00363d", hex(d.onPrimary))
        assertEquals("#004f58", hex(d.primaryContainer))
        assertEquals("#97f0ff", hex(d.onPrimaryContainer))
        assertEquals("#121312", hex(d.background))
        assertEquals("#1a1c1a", hex(d.surface))
        assertEquals("#212321", hex(d.surfaceContainer))
        assertEquals("#2b2d2b", hex(d.surfaceContainerHigh))
        assertEquals("#e2e3de", hex(d.onSurface))
        assertEquals("#c5c7c3", hex(d.onSurfaceVariant))
        assertEquals("#8f918d", hex(d.outline))
        assertEquals("#454744", hex(d.outlineVariant))
        assertEquals("#ffb4ab", hex(d.error))
    }

    @Test
    fun lightAndDark_defineTheSameRoles_andDifferOnTheKeyOnes() {
        val light = roles(LightColors)
        val dark = roles(DarkColors)
        assertEquals(light.keys, dark.keys)
        for (name in listOf("primary", "on-primary", "background", "surface", "on-surface", "on-surface-variant", "surface-container", "error", "outline")) {
            assertNotEquals("$name açık ve koyuda aynı", light.getValue(name), dark.getValue(name))
        }
        // Koyu tema gerçekten koyu, açık tema gerçekten açık.
        assertTrue(luminance(DarkColors.background) < 0.05)
        assertTrue(luminance(LightColors.background) > 0.8)
        // Tek vurgu: teal (kırmızı kanalı yeşil/maviden belirgin düşük).
        for (c in listOf(LightColors.primary, DarkColors.primary)) assertTrue(c.red < c.green && c.red < c.blue)
    }

    private fun textPairs(s: ColorScheme): Map<String, Pair<Color, Color>> = mapOf(
        "onSurface/surface" to (s.onSurface to s.surface),
        "onSurfaceVariant/surface" to (s.onSurfaceVariant to s.surface),
        "onSurface/surfaceContainer" to (s.onSurface to s.surfaceContainer),
        "onSurfaceVariant/surfaceContainer" to (s.onSurfaceVariant to s.surfaceContainer),
        "onSurface/surfaceContainerLow" to (s.onSurface to s.surfaceContainerLow),
        "onSurfaceVariant/surfaceContainerLow" to (s.onSurfaceVariant to s.surfaceContainerLow),
        "onSurfaceVariant/surfaceContainerHigh" to (s.onSurfaceVariant to s.surfaceContainerHigh),
        "primary/surface" to (s.primary to s.surface),
        "primary/surfaceContainer" to (s.primary to s.surfaceContainer),
        "primary/surfaceContainerHigh" to (s.primary to s.surfaceContainerHigh),
        "secondary/surfaceContainerLow" to (s.secondary to s.surfaceContainerLow),
        "onPrimary/primary" to (s.onPrimary to s.primary),
        "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
        "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
        "onError/error" to (s.onError to s.error),
        "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
        "error/surface" to (s.error to s.surface),
        "onSurface/background" to (s.onSurface to s.background),
    )

    @Test
    fun textAndBackgroundPairsUsedByScreens_meetWcagAa_inBothThemes() {
        for ((theme, scheme) in listOf("açık" to LightColors, "koyu" to DarkColors)) {
            for ((name, pair) in textPairs(scheme)) {
                val ratio = contrast(pair.first, pair.second)
                assertTrue("$theme tema $name kontrast %.2f < 4.5".format(ratio), ratio >= 4.5)
            }
        }
    }

    @Test
    fun uiComponentBoundaries_haveAtLeast3to1Contrast() {
        // WCAG 1.4.11: çerçeve/ayırıcı olarak gerçek sınır işlevi gören outline.
        for ((theme, scheme) in listOf("açık" to LightColors, "koyu" to DarkColors)) {
            val ratio = contrast(scheme.outline, scheme.surface)
            assertTrue("$theme tema outline kontrastı %.2f < 3".format(ratio), ratio >= 3.0)
        }
    }

    @Test
    fun typography_isSansSerif_andFollowsDesignScale() {
        val t = AiGaleriTypography
        val all = listOf(
            t.headlineLarge, t.headlineMedium, t.headlineSmall, t.titleLarge, t.titleMedium, t.titleSmall,
            t.bodyLarge, t.bodyMedium, t.bodySmall, t.labelLarge, t.labelMedium, t.labelSmall,
        )
        for (style in all) {
            assertEquals(FontFamily.SansSerif, style.fontFamily)
            assertNotEquals(FontFamily.Serif, style.fontFamily)
        }
        fun check(style: androidx.compose.ui.text.TextStyle, size: Int, line: Int, weight: Int, spacing: Float) {
            assertEquals(size.sp, style.fontSize)
            assertEquals(line.sp, style.lineHeight)
            assertEquals(FontWeight(weight), style.fontWeight)
            assertEquals(spacing.sp, style.letterSpacing)
        }
        check(t.headlineLarge, 32, 40, 400, 0f)
        check(t.headlineSmall, 24, 32, 400, 0f)
        check(t.titleLarge, 22, 28, 500, 0f)
        check(t.titleMedium, 16, 24, 500, 0.15f)
        check(t.titleSmall, 14, 20, 500, 0.1f)
        check(t.bodyLarge, 16, 24, 400, 0.5f)
        check(t.bodyMedium, 14, 20, 400, 0.25f)
        check(t.bodySmall, 12, 16, 400, 0.4f)
        check(t.labelLarge, 14, 20, 500, 0.1f)
        check(t.labelMedium, 12, 16, 500, 0.5f)
        check(t.labelSmall, 11, 16, 500, 0.5f)
        // Boyutlar sp (yazı ölçeğine uyar), px/dp değil.
        for (style in all) assertTrue(style.fontSize.isSp && style.lineHeight.isSp)
    }

    private fun corner(shape: androidx.compose.ui.graphics.Shape): Float =
        (shape as CornerBasedShape).topStart.toPx(Size(1000f, 1000f), Density(1f))

    @Test
    fun shapes_followDesignRadii() {
        val s = AiGaleriShapes
        assertEquals(4f, corner(s.extraSmall), 0.01f)
        assertEquals(8f, corner(s.small), 0.01f)
        assertEquals(12f, corner(s.medium), 0.01f)
        assertEquals(16f, corner(s.large), 0.01f)
        assertEquals(24f, corner(s.extraLarge), 0.01f)
    }

    @Test
    fun dimens_followDesignMd() {
        assertEquals(2.dp, AiGaleriDimens.Gutter)
        assertEquals(16.dp, AiGaleriDimens.Margin)
        assertEquals(4.dp, AiGaleriDimens.SpaceXs)
        assertEquals(8.dp, AiGaleriDimens.SpaceSm)
        assertEquals(16.dp, AiGaleriDimens.SpaceMd)
        assertEquals(24.dp, AiGaleriDimens.SpaceLg)
        assertEquals(32.dp, AiGaleriDimens.SpaceXl)
        assertEquals(3, AiGaleriDimens.GridColumns)
        assertEquals(56.dp, AiGaleriDimens.SearchBarHeight)
        assertEquals(28.dp, AiGaleriDimens.SearchBarCorner)
        assertEquals(20.dp, AiGaleriDimens.ButtonCorner)
        assertEquals(64.dp, AiGaleriDimens.TopBarHeight)
        assertTrue(AiGaleriDimens.MinTouchTarget >= 48.dp)
    }

    @Test
    fun extras_viewerScrimIs60PercentBlack() {
        assertEquals(0.6f, AiGaleriExtras.ViewerScrim.alpha, 0.01f)
        assertEquals(0f, AiGaleriExtras.ViewerScrim.red, 0f)
        assertEquals("#0d0e0d", hex(AiGaleriExtras.ViewerCanvas))
    }

    @Test
    fun designTokensAreOnlyInTheThemeFile() {
        val uiDir = File(appDir, "src/main/java/com/ktu/aigaleri/ui")
        // AppIcons.kt: simge vektörlerinin dolgusu yalnızca maske (Color.Black); gerçek renk Icon(tint) ile temadan gelir.
        val offenders = uiDir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "Theme.kt" && it.name != "AppIcons.kt" }
            .filter { f ->
                val t = f.readText()
                Regex("""Color\(\s*0x""").containsMatchIn(t) ||
                    Regex("""Color\.(Black|White|Red|Green|Blue|Gray|Yellow|Cyan|Magenta)\b""").containsMatchIn(t) ||
                    t.contains("FontFamily.Serif") || t.contains("Font(")
            }.map { it.name }.toList()
        assertEquals("tema dışında renk/yazı tipi tanımı: $offenders", emptyList<String>(), offenders)
        val theme = File(uiDir, "theme/Theme.kt").readText()
        assertFalse(theme.contains("FontFamily.Serif"))
        assertTrue(theme.contains("FontFamily.SansSerif"))
    }

    @Test
    fun darkThemeFollowsSystem_noThemeSwitchInApp() {
        val uiDir = File(appDir, "src/main/java/com/ktu/aigaleri/ui")
        val main = File(uiDir, "MainScreen.kt").readText()
        assertTrue(main.contains("AiGaleriTheme {"))
        assertFalse("tema anahtarı yok: darkTheme geçirilmez", main.contains("darkTheme"))
        assertTrue(File(uiDir, "theme/Theme.kt").readText().contains("isSystemInDarkTheme()"))
        val night = File(appDir, "src/main/res/values-night/themes.xml").readText()
        assertTrue(night.contains("Theme.AiGaleri") && night.contains("android:Theme.Material.NoActionBar"))
        assertFalse(night.contains("Light"))
    }
}
