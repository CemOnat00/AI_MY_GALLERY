---
name: Authentic System Utility
colors:
  surface: '#f9faf5'
  surface-dim: '#d9dad6'
  surface-bright: '#f9faf5'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f3f4ef'
  surface-container: '#edeee9'
  surface-container-high: '#e8e9e4'
  surface-container-highest: '#e2e3de'
  on-surface: '#1a1c19'
  on-surface-variant: '#3f484a'
  inverse-surface: '#2f312e'
  inverse-on-surface: '#f0f1ec'
  outline: '#6f797b'
  outline-variant: '#bec8ca'
  surface-tint: '#016874'
  primary: '#004e58'
  on-primary: '#ffffff'
  primary-container: '#006874'
  on-primary-container: '#97e4f2'
  inverse-primary: '#85d2e0'
  secondary: '#4a6267'
  on-secondary: '#ffffff'
  secondary-container: '#cde7ed'
  on-secondary-container: '#50686d'
  tertiary: '#3a4664'
  on-tertiary: '#ffffff'
  tertiary-container: '#525e7d'
  on-tertiary-container: '#ccd8fd'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#a2effd'
  primary-fixed-dim: '#85d2e0'
  on-primary-fixed: '#001f24'
  on-primary-fixed-variant: '#004f58'
  secondary-fixed: '#cde7ed'
  secondary-fixed-dim: '#b1cbd1'
  on-secondary-fixed: '#051f23'
  on-secondary-fixed-variant: '#334b4f'
  tertiary-fixed: '#d9e2ff'
  tertiary-fixed-dim: '#bac6ea'
  on-tertiary-fixed: '#0e1b36'
  on-tertiary-fixed-variant: '#3a4664'
  background: '#f9faf5'
  on-background: '#1a1c19'
  surface-variant: '#e2e3de'
typography:
  headline-lg:
    fontFamily: Roboto Flex
    fontSize: 32px
    fontWeight: '400'
    lineHeight: 40px
  headline-md:
    fontFamily: Roboto Flex
    fontSize: 28px
    fontWeight: '400'
    lineHeight: 36px
  headline-sm:
    fontFamily: Roboto Flex
    fontSize: 24px
    fontWeight: '400'
    lineHeight: 32px
  title-lg:
    fontFamily: Roboto Flex
    fontSize: 22px
    fontWeight: '500'
    lineHeight: 28px
  title-md:
    fontFamily: Roboto Flex
    fontSize: 16px
    fontWeight: '500'
    lineHeight: 24px
    letterSpacing: 0.15px
  title-sm:
    fontFamily: Roboto Flex
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 20px
    letterSpacing: 0.1px
  body-lg:
    fontFamily: Roboto Flex
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
    letterSpacing: 0.5px
  body-md:
    fontFamily: Roboto Flex
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
    letterSpacing: 0.25px
  body-sm:
    fontFamily: Roboto Flex
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
    letterSpacing: 0.4px
  label-lg:
    fontFamily: Roboto Flex
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 20px
    letterSpacing: 0.1px
  label-md:
    fontFamily: Roboto Flex
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.5px
  label-sm:
    fontFamily: Roboto Flex
    fontSize: 11px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.5px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  gutter: 0.125rem
  margin: 1rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2rem
---

## Brand & Style

This design system embodies the understated, high-efficiency functionalism of first-party Android system utilities. Built around the physical ergonomic realities of modern handheld devices (specifically calibrated for a 412x915 Pixel viewport), the interface intentionally demotes itself to a quiet, invisible frame that prioritizes personal media content above software chrome.

Key principles:
- **Utilitarian & Restrained:** Eliminates decorative gradients, glassmorphism, decorative blurs, and metaphorical AI embellishments (such as wands, stars, or brains). Capabilities are presented matter-of-factly through standard Android system primitives.
- **Warm & Organic Neutrality:** Replaces sterile, cold blue-grays with paper-warm undertones (`#FDFCF7` / `#121312`), giving the gallery an archival, tactile document feel without sacrificing modern digital clarity.
- **Compose-First Ergonomics:** Touch targets strictly adhere to standard 48dp minimum hit areas, thumb-zone bottom navigation, and fluid scroll physics that preserve visual orientation across dense image hierarchies.

## Colors

The palette is derived from Material Design 3 tonal mechanics, anchored by an authoritative, non-distracting Muted Teal accent paired with balanced warm-neutral surfaces.

### Light Mode Roles
- **Primary:** `#006874` (Deep Muted Teal) — applied to key interactive states, floating action buttons, and active bottom navigation indicators. On-Primary: `#FFFFFF`.
- **Primary Container:** `#97F0FF` — subtle emphasis for selected states or high-priority contextual pills. On-Primary Container: `#001F24`.
- **Background:** `#FDFCF7` — warm foundational canvas.
- **Surface:** `#FFFFFF` — base app bars, contextual menus, and floating sheets.
- **Surface Container:** `#F2EFE9` — foundational card tiers and search bar backdrops.
- **Surface Container High:** `#EBE8E1` — elevated dialog surfaces and hover/pressed structural states.
- **On-Surface:** `#1B1C1A` — primary text and high-emphasis icons.
- **On-Surface-Variant:** `#454744` — secondary metadata, timestamp labels, and inactive icon states.
- **Outline:** `#757773` — active borders and unselected check rings.
- **Outline-Variant:** `#C5C7C3` — structural 1px dividers, grid separations, and passive borders.
- **Error:** `#BA1A1A` — destructive alerts and missing media indicators.

### Dark Mode Roles
- **Primary:** `#80D5E3` — high-visibility light teal for dark backgrounds. On-Primary: `#00363D`.
- **Primary Container:** `#004F58` — muted teal bed for active selections. On-Primary Container: `#97F0FF`.
- **Background:** `#121312` — warm near-black.
- **Surface:** `#1A1C1A` — standard dark base.
- **Surface Container:** `#212321` — secondary surfaces and search docks.
- **Surface Container High:** `#2B2D2B` — elevated containers and overlays.
- **On-Surface:** `#E2E3DE` — primary text.
- **On-Surface-Variant:** `#C5C7C3` — secondary details.
- **Outline:** `#8F918D` — accessible boundaries.
- **Outline-Variant:** `#454744` — low-contrast structural dividers.
- **Error:** `#FFB4AB` — alert states.

## Typography

Text is structured for extreme legibility during rapid thumbnail scanning, batch-selection actions, and metadata inspection.

- **Type Scale Rules:** Sentence case is used strictly across all levels, including action chips, button labels, and system dialogs. Never use full-uppercase styling.
- **Hierarchy Mapping:**
  - `title-lg` handles section day/month group headers ("September 2024", "Trip to Kyoto").
  - `title-md` formats album cards, search suggestion titles, and settings list labels.
  - `body-lg` / `body-md` are used for detail panels, EXIF metadata overlays, and system toast notifications.
  - `body-sm` displays inline photo details (e.g., "RAW • 24 MP • 4.2 MB").
  - `label-md` is standard for thumbnail badges (burst counters, video durations) and bottom navigation icon titles.

## Layout & Spacing

Metrics are tuned to Google Pixel form factors using an 8dp structural grid with a 4dp baseline subgrid for fine text and icon alignment.

### Grid & Canvas
- **Viewport Reference:** 412dp screen width (Google Pixel baseline).
- **Page Margins:** Fixed 16dp (`margin`) lateral margins for structural layouts, bottom sheets, search surfaces, and settings lists.
- **Primary Media Grid:** 3-column fluid grid with 2dp (`gutter`) separation. Media cells maintain a strict 1:1 aspect ratio. The media grid bleeds edge-to-edge across the horizontal viewport without outer padding.
- **Scaffold Padding:** Bottom system bars require 80dp baseline navigation clearances plus Android WindowInsets (`navigationBars`). Top toolbars allocate 64dp plus `statusBars`.

## Elevation & Depth

Visual hierarchy uses Material 3 tonal elevation and strict low-contrast borders rather than deep directional shadows.

- **Level 0 (Flat / Canvas):** Primary photo grid, fullscreen media canvas, and structural dividers. 0px blur, relies strictly on `#C5C7C3` (Light) or `#454744` (Dark) 1px strokes.
- **Level 1 (Tonal Surface Elevation):** Surface Container (`#F2EFE9` in light, `#212321` in dark) applied to the docked bottom navigation, top search pill, and album folders.
- **Level 2 (Active Sheets / Contextual Menus):** Surface Container High (`#EBE8E1` in light, `#2B2D2B` in dark) combined with a soft, diffused ambient drop shadow: `0px 2px 6px rgba(0, 0, 0, 0.08)`.
- **Level 3 (Modal Dialogs):** Highest system tier for confirmation sheets and permission prompts. Accompanied by a 32% black backdrop scrim. No colored glows or gradient shadows are permitted.

## Shapes

Corner radii maintain crisp, structural rhythm across media items and fluid containment across controls.

- **Thumbnails & Media Cells:** 0dp within full edge-to-edge grids. When placed in floating collections or album lists, thumbnails use 8dp radii.
- **Search Dock & Standard Cards:** 12dp rounded corners (`rounded-lg`).
- **Interactive Buttons:** 20dp pill-like forms for filled action buttons.
- **Filter Chips & Quick Selectors:** 8dp to 12dp gentle rounding for compact system tag layout.

## Components

### Top Search Anchor
- Fixed height: 56dp, horizontal margin: 16dp, corner radius: 28dp (fully rounded pill).
- Background: Surface Container (`#F2EFE9` light / `#212321` dark).
- Leading element: Search icon in On-Surface-Variant (`#454744`).
- Label: Body Large in On-Surface-Variant ("Search photos, places, dates").
- Trailing element: 32dp circular profile or system menu avatar.

### Photo Grid Cells & Badges
- Thumbnail Aspect: 1:1 square.
- Video / Motion indicator: Positioned at top-right, 4dp inset, using `label-sm` with a semi-transparent dark pill background (`rgba(0, 0, 0, 0.60)`) and white text.
- Multi-selection Checkbox: Positioned top-left, 6dp inset. Default state: invisible. Selection state: 24dp circular check filled with Primary (`#006874`), displaying a 2dp white check icon.

### Filter & Context Chips
- Height: 32dp, corner radius: 8dp.
- Inactive: Outline-Variant 1px stroke, transparent background, text `label-md` in On-Surface-Variant.
- Selected: Background filled with Primary Container (`#97F0FF` light / `#004F58` dark), zero border, text `label-md` in On-Primary Container.

### Buttons
- **Filled Button:** Height 40dp, horizontal padding 24dp, corner radius 20dp. Background: Primary (`#006874` light / `#80D5E3` dark), text: `label-lg` in On-Primary.
- **Tonal Button:** Height 40dp, corner radius 20dp. Background: Surface Container High, text: `label-lg` in On-Surface.
- **Text Button:** Height 40dp, padding 12dp. Transparent background, text: Primary color.

### Bottom Navigation Bar
- Total Height: 80dp (excluding system gesture bar).
- Background: Surface Container (`#F2EFE9` light / `#212321` dark).
- Divider: Top 1px solid Outline-Variant border.
- Active Indicator: 64dp x 32dp pill container filled with Primary Container, containing the active icon tinted with On-Primary Container. Label directly below in `label-md` medium weight.