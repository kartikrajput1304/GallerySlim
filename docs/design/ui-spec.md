# GallerySlim — UI Design Spec (Phase 1)

Scope: Permission/onboarding, Scanning, Dashboard (+ empty state), Phase 2 sketch.
Stack: Jetpack Compose + Material 3. All dp/sp values final. Dynamic color is **off by default**
(brand palette below); a "Use system colors" toggle may ship later — nothing in this spec depends on it.

Design stance: quiet warm-neutral surfaces, one deep-teal brand voice, green reserved for
"storage you get back". No Material purple anywhere. Depth comes from tonal layering +
hairline borders, not drop shadows. Exactly one loud thing per screen.

---

## 1. Color tokens

Validated: breakdown/segment palette passes CVD + contrast checks in both modes
(dataviz validator, adjacent pairs); every text token below ≥ 4.5:1 on its surface (AA).

| Token | Light | Dark | Role |
|---|---|---|---|
| `background` | `#F7F7F4` | `#121413` | Window background (warm off-white / near-black) |
| `surface` | `#FCFCFA` | `#1C1E1D` | Cards, sheets |
| `surfaceElevated` | `#FFFFFF` | `#242726` | Dialogs, pinned app bar, bottom sheet |
| `outlineHairline` | `#1A1C1B` @ 8% | `#FFFFFF` @ 10% | 1dp card/divider strokes |
| `primary` | `#0B6B5F` | `#53D6C0` | Brand teal: CTAs, active ring, links |
| `onPrimary` | `#FFFFFF` | `#06211D` | Text/icon on primary |
| `primaryContainer` | `#D7EEE9` | `#0E3A33` | Tinted chips, icon containers |
| `accentSavings` | `#157A33` | `#5BD57F` | Savings numbers, "−X GB" deltas, positive only |
| `savingsContainer` | `#DFF2E2` | `#153B22` | Wash behind savings highlights |
| `warning` | `#9A5B00` | `#F0B45A` | Skipped-with-reason notes, sync warnings |
| `textPrimary` | `#1A1C1B` | `#ECEEEC` | Headlines, values |
| `textSecondary` | `#54565A` | `#B4B8B4` | Supporting copy |
| `textTertiary` | `#71747A` | `#8C908C` | Captions, timestamps (AA at ≥ 14sp) |
| `seg1` JPEG | `#2A78D6` | `#3987E5` | Breakdown segment: JPEG photos |
| `seg2` PNG | `#EB6834` | `#D95926` | Breakdown segment: PNG screenshots |
| `seg3` Video | `#1BAF7A` | `#199E70` | Breakdown segment: H.264 video |
| `seg4` Motion | `#EDA100` | `#C98500` | Breakdown segment: motion-photo bytes |
| `segSkipped` | `#C9CBC7` | `#3A3D3B` | "Safe/skipped" segment (neutral, recedes) |

Note: Segment colors are **mode-paired** (switch hex with
theme, never tint one hex). Light-mode `seg3`/`seg4` sit below 3:1 vs surface — permitted because
every segment is always accompanied by its labeled breakdown row (relief rule); never use segment
colors as text. Map to M3: `primary→primary`, `accentSavings→tertiary`, `surfaceElevated→surfaceContainerHigh`.

Status bar / nav bar: transparent, edge-to-edge (`enableEdgeToEdge()`); icons dark-on-light /
light-on-dark per theme. Content respects `WindowInsets.safeDrawing`.

## 2. Type scale (Roboto; use Roboto Flex if available)

| Style | Size/Line | Weight | Tracking | Use |
|---|---|---|---|---|
| `heroNumber` | 64/68sp | 500 | −1.5% | Dashboard savings figure (digits + decimal only) |
| `heroUnit` | 24/32sp | 500 | 0 | "GB" beside hero, baseline-aligned |
| `displayCount` | 40/44sp | 500 | −0.5% | Scanning counter |
| `title` | 22/28sp | 500 | 0 | Screen titles, card headers |
| `bodyLarge` | 16/24sp | 400 | +0.15sp | Onboarding copy, row labels |
| `body` | 14/20sp | 400 | +0.1sp | Row sublabels, captions in cards |
| `label` | 14/20sp | 500 | +0.1sp | Buttons, chips |
| `caption` | 12/16sp | 400 | +0.3sp | Timestamps, legal, per-segment counts |
| `rowValue` | 16/24sp | 600 | 0 | Per-row "−2.1 GB" values |

Numbers: hero and row values use default **proportional figures** at rest; apply
`fontFeatureSettings = "tnum"` only while a number is actively ticking (scan counter, hero
count-up) so digits don't jitter; drop it on settle. Row values in the breakdown column also
use `tnum` so the minus-GB column aligns.

## 3. Spacing, shape, elevation

- Grid: 4dp base. Screen side margin **20dp**. Vertical rhythm: 8/12/16/24/32/48.
- Corner radii: cards **20dp**, buttons **full** (pill), small chips 12dp, bottom sheet top 28dp,
  segmented bar 6dp (outer ends only).
- Elevation: cards are `surface` + 1dp `outlineHairline` stroke, **0dp shadow**. Elevated layer
  (`surfaceElevated`) gets 1dp tonal lift + 8% scrim shadow only for dialogs/sheets. Pinned top
  app bar: `background` → `surfaceElevated` crossfade on scroll (120ms).
- Buttons: height **56dp** primary CTA, 48dp secondary; horizontal padding 24dp.

## 4. Iconography

Material Symbols **Rounded**, weight 400, fill 0 (outlined), 24dp default; optical size 40 inside
icon containers. One accent exception: the permission screen's shield uses fill 1. Per-category:
JPEG `photo_camera`, PNG `screenshot_frame_2` (fallback `fullscreen`), video `movie`, motion photo
`motion_photos_on`, skipped/safe `verified` (shield-check), scan `search`/`photo_library`. Never
multicolor icons; tint = row's segment color inside a 10%-alpha container of the same hue.

## 5. Motion tokens

| Token | Value |
|---|---|
| `easeStandard` | cubic-bezier(0.2, 0, 0, 1) — most transitions |
| `easeEmphDecel` | cubic-bezier(0.05, 0.7, 0.1, 1) — things arriving |
| `easeEmphAccel` | cubic-bezier(0.3, 0, 0.8, 0.15) — things leaving |
| `durShort` | 150ms (state changes, ripples, crossfades) |
| `durMedium` | 300ms (card/stagger entrances) |
| `durLong` | 500ms (screen transitions) |
| `durCountUp` | 900ms (hero number count-up) |

All animations respect `Settings.Global.ANIMATOR_DURATION_SCALE` and reduce-motion: when animator
scale is 0, numbers snap to final value, transitions become 90ms fades.

---

## 6. Screen 1 — Permission / onboarding

Route `onboarding`. Shown until `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO` granted. No app bar.

Layout (Column, 20dp side margins, edge-to-edge):
1. **Top spacer** `Spacer(weight 0.25f)` — content sits slightly above center.
2. **Illustration**: 96dp circle of `primaryContainer`, centered, containing `photo_library`
   icon 48dp tinted `primary`. No mascots, no stock art.
3. 24dp gap. **Title** (`title`, textPrimary, centered): "Your photos, just lighter".
4. 12dp gap. **Body** (`bodyLarge`, textSecondary, centered, max width 320dp):
   "GallerySlim scans your gallery to show how much space you could reclaim — without deleting
   a single photo or video."
5. 24dp gap. **Trust list** — three rows, left-aligned in a `surface` card (radius 20dp, 16dp
   inner padding, 12dp between rows). Each row: 20dp icon tinted `primary` + 12dp gap +
   `body` textSecondary.
   - `lock` "Everything stays on your phone. No cloud, no account."
   - `visibility` "This version only looks — it never changes or deletes anything."
   - `tune` "You review every suggestion before anything happens (future update)."
6. `Spacer(weight 0.35f)`.
7. **Primary CTA**: full-width `Button`, 56dp, `primary`/`onPrimary`, `label` text
   "Allow access to photos". Tapping fires the system permission dialog.
8. 8dp gap. **Caption** (`caption`, textTertiary, centered): "Android will ask you to confirm.
   You can change this anytime in Settings." 24dp bottom padding above nav bar inset.

States: **Partial access** (Android 14 "Select photos"): proceed normally; dashboard later shows
an info row "Showing selected items only — allow all for a full estimate" (`warning` icon, tap →
app settings). **Denied**: body swaps to "GallerySlim can't see your gallery yet." CTA label →
"Continue"; after `shouldShowRequestPermissionRationale` is false, CTA → "Open settings" (deep-link).
Never a red/scary treatment — same calm layout.

Entrance: items 2–5 fade+slide up 16dp, staggered 40ms, `easeEmphDecel`, `durMedium`.

## 7. Screen 2 — Scanning

Route `scanning`, entered immediately on grant. System back → exits app (scan resumes on return;
persist partial scan in ViewModel). No app bar; centered Column.

1. **Progress ring**, centered, container 160dp:
   - Track: 160dp circle, stroke 10dp, round caps, color `primaryContainer`.
   - Indicator: `primary`, same stroke. Phase A (first ~400ms while MediaStore count is unknown):
     M3 indeterminate arc. Phase B: determinate; sweep animates to `processed/total` with
     `easeStandard` 300ms per update, **never regresses**. Switch A→B by morphing the
     indeterminate arc into the determinate sweep (no crossfade flash).
   - Center: **count** (`displayCount`, textPrimary, `tnum`) ticking up, e.g. "1,482", with
     caption below inside ring: "of 2,400" (`caption`, textTertiary) once total known.
2. 32dp gap. **Status line** (`bodyLarge`, textSecondary, centered), crossfade 150ms between
   phases: "Looking through your gallery…" → "Measuring photos…" → "Measuring videos…" →
   "Checking for hidden video…". Fixed 24sp-height box so layout never jumps.
3. 8dp gap. **Found-so-far line** (`body`, `accentSavings`): "Found 4.1 GB to free so far" —
   appears (fade+slide 8dp) once estimate > 0, updates at most every 500ms.

No cancel button (scan is seconds-fast, read-only); back exits. Keep screen on during scan
(`FLAG_KEEP_SCREEN_ON`). If scan < 1.5s total, hold screen min 1.5s so it never strobes.

**Exit transition (scan → dashboard), 500ms total:** ring sweep completes to 100% and holds 120ms;
ring + text scale to 0.92 and fade (`easeEmphAccel`, 200ms); dashboard enters with hero area
fading/sliding up 24dp (`easeEmphDecel`, 350ms, 100ms overlap). Then hero number **counts up
0 → X over 900ms** (`easeEmphDecel` on the animated value, `tnum` during tick), unit "GB" fixed in
place from frame 0; the segmented bar's segments grow left-to-right in the same 900ms window,
staggered 60ms. This count-up is the app's signature moment — it runs only on first entry after
a scan, not on re-navigation.

## 8. Screen 3 — Dashboard (the hero)

Route `dashboard`. `LargeTopAppBar`-style header collapses to pinned 64dp bar (title "GallerySlim",
`title` 22sp; pinned state shows compact "9.4 GB" trailing in `accentSavings` `label`).
Content: `LazyColumn`, 20dp side margins, 24dp bottom inset padding.

**A. Hero card** — `surface`, radius 20dp, hairline stroke, padding 24dp:
1. Eyebrow (`body`, textSecondary): "You can free up to".
2. 4dp gap. Hero figure, baseline row: "9.4" (`heroNumber`, `accentSavings`) + 6dp gap + "GB"
   (`heroUnit`, `accentSavings`). Exactly one hero per view; nothing else on this screen may be
   green at this size.
3. 4dp gap. Context line (`body`, textTertiary): "of 31.2 GB scanned · 2,314 photos · 86 videos".
4. 20dp gap. **Segmented savings bar** (the one chart): full-width, 12dp tall, radius 6dp outer
   corners. Segments in fixed order JPEG `seg1` → PNG `seg2` → Video `seg3` → Motion `seg4`,
   widths proportional to each category's reclaimable bytes, separated by **2dp gaps in `surface`**
   (never strokes). No remainder/track segment — the bar represents savings only, and the rows
   below are its legend (do not repeat labels under the bar). Min rendered segment 4dp; categories
   under 1% fold visually but keep their row.
5. 12dp gap. Footnote (`caption`, textTertiary): "Estimate at visually identical quality.
   Nothing is changed in this version."

**B. Breakdown list** — one `surface` card, radius 20dp, rows divided by hairline insets
(start 72dp). Row anatomy (min height 64dp, 16dp horizontal padding):
- **Leading**: 40dp container, radius 12dp, segment color @ 10% alpha; 24dp icon tinted with the
  row's segment color (dark-mode hex in dark).
- **Center** (12dp from leading): label (`bodyLarge`, textPrimary) + sublabel (`body`,
  textTertiary).
- **Trailing**: value (`rowValue`, `accentSavings`, `tnum`) e.g. "−3.8 GB", then 4dp,
  `chevron_right` 20dp textTertiary. Whole row ripples; tap → read-only category detail
  (grid of affected items, Coil thumbnails — out of scope here but the chevron is honest:
  it navigates even in Phase 1).

Rows (fixed order = bar order):
| Label | Sublabel pattern | Value |
|---|---|---|
| JPEG photos | "1,204 photos → about 35% smaller" | −3.8 GB |
| PNG screenshots | "642 screenshots → about 60% smaller" | −2.6 GB |
| H.264 videos | "71 videos → about 45% smaller" | −2.4 GB |
| Hidden motion-photo video | "412 photos carry 0.6 GB of hidden video" | −0.6 GB |

A category with zero items omits its row and bar segment entirely (never "0 B" rows).

**C. Safe & skipped card** — separate `surface` card, 16dp below B, deliberately quieter:
- Header row: `verified` icon 24dp tinted textTertiary + "Already efficient or protected"
  (`bodyLarge`, textPrimary) + trailing total "4.9 GB" (`rowValue`, **textSecondary** — not green:
  this is not savings).
- Collapsed by default; chevron rotates 180° (`durShort`) to expand rows (48dp, no leading
  containers, `body` textSecondary + trailing count `caption`):
  "HEIC / HEVC — already efficient · 3,420 items", "Ultra HDR — kept untouched · 214 photos",
  "Favorites & last 30 days — excluded · 182 items".
- Footer caption inside expanded state: "GallerySlim never touches these without asking."

**D. Rescan row**: text button, left-aligned under cards — `refresh` 20dp + "Rescan gallery"
(`label`, primary) + trailing caption "Scanned 2 min ago" (textTertiary). Tap → Scanning screen
(no count-up replay unless numbers changed).

**Empty / nothing-to-save state** (estimate < 100 MB): hero card is replaced by: 72dp
`primaryContainer` circle with `check` icon 36dp `primary`; title "Your gallery is already slim"
(`title`); body "Everything is stored efficiently — there's less than 100 MB to reclaim."
(`bodyLarge`, textSecondary); the Safe & skipped card (expanded by default) and Rescan row remain.
Tone: congratulate, don't apologize; no green hero (nothing to claim).

## 9. Phase 2 sketch — Review & confirm (anticipated, not built)

Reuses every token above: a category detail grid where each item card shows a Coil thumbnail with
a `caption` size badge ("4.2 MB → 1.5 MB", arrow in `accentSavings`); a pinned bottom bar
(`surfaceElevated`, top radius 28dp) carrying a live selected-savings total (`rowValue`, green,
`tnum`) and a 56dp primary CTA "Shrink 214 photos"; per-item checkbox 24dp top-right over the
thumbnail. Progress reuses the Scanning ring at 48dp inline; the morning batch-confirm sheet
reuses the breakdown-row anatomy with checkboxes. The only new tokens Phase 2 needs: a
`danger` red for "restore/undo" affordances — reserve `#B3261E` light / `#F2B8B5` dark now.

## 10. Accessibility

- Touch targets ≥ 48×48dp everywhere (rows are 64dp; chevron is part of the row target, not its
  own). CTA 56dp.
- All text tokens meet WCAG AA (verified ≥ 4.5:1; see §1). Segment colors never carry text; each
  segment's identity is its labeled row (color + icon + text — never color alone).
- TalkBack: hero card is one semantic node: "You can free up to 9.4 gigabytes, of 31.2 gigabytes
  scanned." Segmented bar: `contentDescription` enumerating "JPEG photos 3.8 gigabytes, PNG
  screenshots 2.6 gigabytes, …" — the bar itself is not focusable separately from this summary.
  Breakdown rows: "JPEG photos, 1,204 photos, save 3.8 gigabytes, button." Scanning ring:
  `liveRegion = polite`, announce every 10%: "Scanning, 40 percent, 960 of 2,400 items."
  Count-ups announce final values only.
- Font scale: layouts tolerate 200%; hero number may wrap unit to its own line at ≥ 150%;
  rows grow vertically, never truncate labels (sublabels ellipsize at 2 lines).
- RTL: all paddings as start/end; chevron auto-mirrors; segmented bar fills end-to-start.
- Reduce motion: see §5.
