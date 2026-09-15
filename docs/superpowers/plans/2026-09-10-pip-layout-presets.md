# PIP Layout Presets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the PIP widget's "how many apps" setting with five named layout presets, chosen from picture thumbnails, covering one-pane, two-pane and three-pane arrangements on both axes.

**Architecture:** The presets are solved as **geometry, not as a view tree**. A pure function turns `(preset, size, two split fractions)` into one rectangle per pane; `PipWidget` keeps its three pane slots declared in fixed tree order and only moves their rectangles. This is forced by an existing constraint (see Global Constraints) and it is also what makes nested layouts like hero+grid possible without nesting anything. The same solver renders the Settings thumbnails, so a picture can never disagree with the layout it advertises.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2024.09.00), Gradle Kotlin DSL, JUnit 4 (added by Task 1 — the project currently has no test source set at all), DataStore Preferences, Gson.

**Spec:** `docs/pip-investigation/presets.md`

## Global Constraints

- **Never reorder pane slots in the Compose tree.** `PipWidget.kt:215-227` records that reordering slots inside a `Row` — even with matching `key()` — moves the underlying `SurfaceView` within its real parent `ViewGroup`, which detaches and reattaches it, causing a genuine `surfaceDestroyed`/`surfaceCreated` cycle that visibly reloads the embedded app. Slots must stay declared in fixed order and be positioned only via `offset()`/`size()`.
- **Do not modify `TaskEmbedder` or `PipPane`.** These carry the reflective hidden-API embedding that makes PIP work at all. This feature only changes the arrangement layer above them.
- Target device is a 1920×720 head unit (aspect 2.67) running **Android 9 / API 28**. `minSdk = 21`.
- Screen class **suggests a default and an ordering; it never hides a preset.** All five presets are always selectable.
- Preset owns **topology** (how many panes, along which axes). The two split fractions own **divider position**. Changing preset resets fractions to that preset's baseline; dragging afterwards persists.
- The installed variant is `aospDebug`, which must be re-signed with the AOSP platform key before installing. R8 is enabled on it with `isDebuggable = false`.
- Baseline ratios from the spec, verbatim: Primary+Companion `A : B ≈ 70 : 30`; Triple Wide `A : B : C ≈ 50 : 25 : 25`; Portrait Stack `A : B ≈ 65 : 35`; Portrait Hero+Grid `A : bottom-row ≈ 65 : 35` and `B : C ≈ 50 : 50`.

## File Structure

| File | Responsibility |
| --- | --- |
| `app/src/main/java/com/openlauncher/app/ui/widget/PipLayout.kt` | **Create.** `LayoutPreset`, `PaneRect`, `solveLayout`, per-preset baselines and pane counts, screen-class ordering. Pure Kotlin, no Compose or Android types, so it is unit-testable. |
| `app/src/test/java/com/openlauncher/app/ui/widget/PipLayoutTest.kt` | **Create.** Unit tests for the solver. |
| `app/build.gradle.kts` | **Modify.** Add JUnit test dependency. |
| `app/src/main/java/com/openlauncher/app/data/AppSettings.kt` | **Modify.** Add `pipLayoutPreset`, remove `pipAppCount`. |
| `app/src/main/java/com/openlauncher/app/data/SettingsRepository.kt` | **Modify.** Persist the preset; migrate old `pip_app_count`. |
| `app/src/main/java/com/openlauncher/app/viewmodel/LauncherViewModel.kt` | **Modify.** Replace `setPipAppCount` with `setPipLayoutPreset`. |
| `app/src/main/java/com/openlauncher/app/ui/widget/PipWidget.kt` | **Modify.** Consume `solveLayout`; give `PaneDivider` an orientation. |
| `app/src/main/java/com/openlauncher/app/ui/widget/PipPresetThumbnail.kt` | **Create.** Draws a preset as a small diagram using `solveLayout`. |
| `app/src/main/java/com/openlauncher/app/ui/screen/SettingsScreen.kt` | **Modify.** Preset picker row using the thumbnails. |
| `app/src/main/java/com/openlauncher/app/ui/screen/HomeScreen.kt` | **Modify.** Replace the "1 APP / 2 APPS / 3 APPS" context-menu rows; host swap-in-edit-mode. |
| `app/src/main/java/com/openlauncher/app/MainActivity.kt` | **Modify.** Rename the callback wiring. |

---
### Task 1: The layout solver

Pure geometry, no Compose. Everything later in this plan derives from it, and it is the only part with enough cases to be worth automated tests.

**Files:**
- Create: `app/src/main/java/com/openlauncher/app/ui/widget/PipLayout.kt`
- Create: `app/src/test/java/com/openlauncher/app/ui/widget/PipLayoutTest.kt`
- Modify: `app/build.gradle.kts` (dependencies block)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class LayoutPreset { FOCUS, LANDSCAPE_PRIMARY_COMPANION, ULTRAWIDE_TRIPLE, PORTRAIT_STACK, PORTRAIT_HERO_GRID }`
  - `data class PaneRect(val x: Float, val y: Float, val w: Float, val h: Float)`
  - `val LayoutPreset.paneCount: Int`
  - `val LayoutPreset.baselineSplit1: Float`, `val LayoutPreset.baselineSplit2: Float`
  - `fun solveLayout(preset: LayoutPreset, width: Float, height: Float, split1: Float, split2: Float, dividerThickness: Float, minPane: Float): List<PaneRect>`
  - `fun presetsForAspect(aspect: Float): List<LayoutPreset>`

All dimensions are **dp expressed as Float**. Keeping Compose's `Dp` out of this file is what lets it be tested without an Android runtime.

- [ ] **Step 1: Add the test dependency**

In `app/build.gradle.kts`, inside the existing `dependencies { }` block, after the `compileOnly(files("libs/framework-stubs.jar"))` line:

```kotlin
    // The PIP layout solver (ui/widget/PipLayout.kt) is pure Kotlin with no
    // Android or Compose types precisely so it can be tested here. This is the
    // project's first test source set.
    //
    // The tests run under `testDebugUnitTest`, not `testAospDebugUnitTest`:
    // AGP builds a unit-test component only for the build type named by
    // `testBuildType`, which defaults to `debug`, so no aospDebug test task
    // exists. That is fine here — the solver lives in `main`, so the debug
    // variant compiles it just the same.
    testImplementation("junit:junit:4.13.2")
```

- [ ] **Step 2: Write the failing test**

Create `app/src/test/java/com/openlauncher/app/ui/widget/PipLayoutTest.kt`:

```kotlin
package com.openlauncher.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class PipLayoutTest {

    // A 1920x720 board with a 10dp divider and a 120dp minimum pane, matching
    // the target head unit and DIVIDER_LAYOUT_WIDTH.
    private fun solve(
        preset: LayoutPreset,
        split1: Float = preset.baselineSplit1,
        split2: Float = preset.baselineSplit2,
        width: Float = 1920f,
        height: Float = 720f
    ) = solveLayout(preset, width, height, split1, split2, dividerThickness = 10f, minPane = 120f)

    @Test
    fun `focus fills the board with one pane`() {
        val r = solve(LayoutPreset.FOCUS)
        assertEquals(1, r.size)
        assertEquals(PaneRect(0f, 0f, 1920f, 720f), r[0])
    }

    @Test
    fun `primary companion splits x at seventy thirty`() {
        val r = solve(LayoutPreset.LANDSCAPE_PRIMARY_COMPANION)
        assertEquals(2, r.size)
        // 1920 - 10 divider = 1910 usable; 70% = 1337
        assertEquals(PaneRect(0f, 0f, 1337f, 720f), r[0])
        assertEquals(PaneRect(1347f, 0f, 573f, 720f), r[1])
        // panes plus dividers account for the whole width
        assertEquals(1920f, r[0].w + 10f + r[1].w)
    }

    @Test
    fun `ultrawide triple splits x twice at fifty twentyfive twentyfive`() {
        val r = solve(LayoutPreset.ULTRAWIDE_TRIPLE)
        assertEquals(3, r.size)
        // 1920 - 20 dividers = 1900 usable; 50% = 950, remainder 950 halved
        assertEquals(950f, r[0].w)
        assertEquals(475f, r[1].w)
        assertEquals(475f, r[2].w)
        assertEquals(0f, r[0].x)
        assertEquals(960f, r[1].x)
        assertEquals(1445f, r[2].x)
        r.forEach { assertEquals(720f, it.h) }
    }

    @Test
    fun `portrait stack splits y at sixtyfive thirtyfive`() {
        val r = solve(LayoutPreset.PORTRAIT_STACK)
        assertEquals(2, r.size)
        // 720 - 10 divider = 710 usable; 65% = 461.5
        assertEquals(PaneRect(0f, 0f, 1920f, 461.5f), r[0])
        assertEquals(PaneRect(0f, 471.5f, 1920f, 248.5f), r[1])
    }

    @Test
    fun `portrait hero grid splits y then x`() {
        val r = solve(LayoutPreset.PORTRAIT_HERO_GRID)
        assertEquals(3, r.size)
        // top row spans the full width
        assertEquals(PaneRect(0f, 0f, 1920f, 461.5f), r[0])
        // bottom row starts below the horizontal divider and halves on x
        assertEquals(471.5f, r[1].y)
        assertEquals(471.5f, r[2].y)
        assertEquals(248.5f, r[1].h)
        assertEquals(248.5f, r[2].h)
        assertEquals(955f, r[1].w)
        assertEquals(955f, r[2].w)
        assertEquals(0f, r[1].x)
        assertEquals(965f, r[2].x)
    }

    @Test
    fun `a split dragged past the end still leaves both panes the minimum`() {
        val r = solve(LayoutPreset.LANDSCAPE_PRIMARY_COMPANION, split1 = 0.999f)
        assertEquals(1790f, r[0].w)   // 1910 usable - 120 minimum
        assertEquals(120f, r[1].w)
    }

    @Test
    fun `a split dragged past the start still leaves both panes the minimum`() {
        val r = solve(LayoutPreset.LANDSCAPE_PRIMARY_COMPANION, split1 = 0.001f)
        assertEquals(120f, r[0].w)
        assertEquals(1790f, r[1].w)
    }

    @Test
    fun `triple keeps room for the two trailing panes`() {
        val r = solve(LayoutPreset.ULTRAWIDE_TRIPLE, split1 = 0.999f)
        // 1900 usable, two trailing panes need 120 each plus one 10 divider
        assertEquals(1650f, r[0].w)
        assertEquals(120f, r[1].w)
        assertEquals(120f, r[2].w)
    }

    @Test
    fun `a board too small for the minimums divides evenly instead of going negative`() {
        val r = solve(LayoutPreset.ULTRAWIDE_TRIPLE, width = 200f)
        assertEquals(3, r.size)
        r.forEach { assert(it.w > 0f) { "pane width must stay positive, was ${it.w}" } }
        assertEquals(60f, r[0].w)   // (200 - 20) / 3
    }

    @Test
    fun `pane counts match the preset topology`() {
        assertEquals(1, LayoutPreset.FOCUS.paneCount)
        assertEquals(2, LayoutPreset.LANDSCAPE_PRIMARY_COMPANION.paneCount)
        assertEquals(3, LayoutPreset.ULTRAWIDE_TRIPLE.paneCount)
        assertEquals(2, LayoutPreset.PORTRAIT_STACK.paneCount)
        assertEquals(3, LayoutPreset.PORTRAIT_HERO_GRID.paneCount)
    }

    @Test
    fun `every preset is offered on every aspect, ultrawide first on a wide board`() {
        val wide = presetsForAspect(2.67f)
        assertEquals(LayoutPreset.entries.size, wide.size)
        assertEquals(LayoutPreset.ULTRAWIDE_TRIPLE, wide.first())

        val portrait = presetsForAspect(0.56f)
        assertEquals(LayoutPreset.entries.size, portrait.size)
        assertEquals(LayoutPreset.PORTRAIT_STACK, portrait.first())
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.openlauncher.app.ui.widget.PipLayoutTest"`

Expected: FAIL — compilation errors, `Unresolved reference 'LayoutPreset'`.

- [ ] **Step 4: Write the solver**

Create `app/src/main/java/com/openlauncher/app/ui/widget/PipLayout.kt`:

```kotlin
package com.openlauncher.app.ui.widget

/**
 * The PIP pane arrangements offered to the user, from
 * docs/pip-investigation/presets.md.
 *
 * A preset owns *topology* — how many panes there are and which axes they are
 * split along. It does not own the divider positions; those live in settings as
 * two fractions and stay draggable, so a preset is a starting point rather than
 * a fixed shape.
 */
enum class LayoutPreset {
    /** One pane filling the board. */
    FOCUS,

    /** Two panes side by side. */
    LANDSCAPE_PRIMARY_COMPANION,

    /** Three panes side by side. */
    ULTRAWIDE_TRIPLE,

    /** Two panes stacked. */
    PORTRAIT_STACK,

    /** One pane across the top, two side by side beneath it. */
    PORTRAIT_HERO_GRID
}

/** How many panes [LayoutPreset] arranges. Replaces the old `pipAppCount`. */
val LayoutPreset.paneCount: Int
    get() = when (this) {
        LayoutPreset.FOCUS -> 1
        LayoutPreset.LANDSCAPE_PRIMARY_COMPANION -> 2
        LayoutPreset.ULTRAWIDE_TRIPLE -> 3
        LayoutPreset.PORTRAIT_STACK -> 2
        LayoutPreset.PORTRAIT_HERO_GRID -> 3
    }

/**
 * Fraction of the first split given to the first pane, from the spec's baseline
 * ratios. Applied when the preset is selected; the user's own drag replaces it
 * from then on.
 */
val LayoutPreset.baselineSplit1: Float
    get() = when (this) {
        LayoutPreset.FOCUS -> 1f
        LayoutPreset.LANDSCAPE_PRIMARY_COMPANION -> 0.70f
        LayoutPreset.ULTRAWIDE_TRIPLE -> 0.50f
        LayoutPreset.PORTRAIT_STACK -> 0.65f
        LayoutPreset.PORTRAIT_HERO_GRID -> 0.65f
    }

/**
 * Fraction of the *remainder* given to the second pane. Only the three-pane
 * presets have a second split; the others report 0.5 so callers never have to
 * special-case reading it.
 */
val LayoutPreset.baselineSplit2: Float
    get() = when (this) {
        LayoutPreset.ULTRAWIDE_TRIPLE -> 0.50f
        LayoutPreset.PORTRAIT_HERO_GRID -> 0.50f
        else -> 0.5f
    }

/** One pane's position and size. All values are dp, as Float. */
data class PaneRect(val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Every preset, ordered so the one most suited to this board comes first.
 *
 * Ordering only — nothing is hidden. The screen classes in the spec are
 * cumulative rather than exclusive (a 2.67 board is landscape *and* ultrawide),
 * and a user on a wide board may still deliberately want a stack. They will see
 * it does not suit the board and pick again, which is cheaper than us guessing
 * wrong and offering no way back.
 */
fun presetsForAspect(aspect: Float): List<LayoutPreset> {
    val preferred = when {
        aspect < 1f -> listOf(LayoutPreset.PORTRAIT_STACK, LayoutPreset.PORTRAIT_HERO_GRID)
        aspect > 2.1f -> listOf(LayoutPreset.ULTRAWIDE_TRIPLE)
        else -> listOf(LayoutPreset.LANDSCAPE_PRIMARY_COMPANION)
    }
    return preferred + LayoutPreset.entries.filterNot { it in preferred }
}

/**
 * Turns a preset plus two split fractions into one rectangle per pane.
 *
 * Deliberately returns rectangles rather than describing a tree. `PipWidget`
 * keeps its pane slots at fixed positions in the Compose tree and moves only
 * their geometry, because moving a slot within the tree reattaches the
 * SurfaceView underneath it and visibly reloads the embedded app (see
 * PipWidget.kt:215). Hero+grid needs no nesting to express: it is just three
 * rectangles.
 *
 * [minPane] is a floor in dp rather than a fraction. The old
 * `PANE_SPLIT_RANGE = 0.15f..0.85f` could not be reused once splits went
 * vertical, because 15% of a 720dp-tall board is 108dp — a pane too small to
 * put an app in — while 15% of a 1920dp-wide board is 288dp.
 */
fun solveLayout(
    preset: LayoutPreset,
    width: Float,
    height: Float,
    split1: Float,
    split2: Float,
    dividerThickness: Float,
    minPane: Float
): List<PaneRect> = when (preset) {

    LayoutPreset.FOCUS -> listOf(PaneRect(0f, 0f, width, height))

    LayoutPreset.LANDSCAPE_PRIMARY_COMPANION -> {
        val usable = width - dividerThickness
        val a = splitTwo(usable, split1, minPane)
        listOf(
            PaneRect(0f, 0f, a, height),
            PaneRect(a + dividerThickness, 0f, usable - a, height)
        )
    }

    LayoutPreset.ULTRAWIDE_TRIPLE -> {
        val usable = width - dividerThickness * 2
        val (a, rest) = splitThree(usable, split1, minPane, dividerThickness)
        val b = splitTwo(rest, split2, minPane)
        listOf(
            PaneRect(0f, 0f, a, height),
            PaneRect(a + dividerThickness, 0f, b, height),
            PaneRect(a + dividerThickness + b + dividerThickness, 0f, rest - b, height)
        )
    }

    LayoutPreset.PORTRAIT_STACK -> {
        val usable = height - dividerThickness
        val top = splitTwo(usable, split1, minPane)
        listOf(
            PaneRect(0f, 0f, width, top),
            PaneRect(0f, top + dividerThickness, width, usable - top)
        )
    }

    LayoutPreset.PORTRAIT_HERO_GRID -> {
        val usableY = height - dividerThickness
        val top = splitTwo(usableY, split1, minPane)
        val bottom = usableY - top
        val usableX = width - dividerThickness
        val b = splitTwo(usableX, split2, minPane)
        listOf(
            PaneRect(0f, 0f, width, top),
            PaneRect(0f, top + dividerThickness, b, bottom),
            PaneRect(b + dividerThickness, top + dividerThickness, usableX - b, bottom)
        )
    }
}

/**
 * Size of the first of two panes sharing [usable], honouring [minPane] on both
 * sides. Falls back to an even division when the board cannot hold two minimums
 * at all, so a cramped board yields small panes rather than negative ones.
 */
private fun splitTwo(usable: Float, fraction: Float, minPane: Float): Float {
    if (usable < minPane * 2) return usable / 2f
    return (usable * fraction).coerceIn(minPane, usable - minPane)
}

/**
 * Size of the first of three panes sharing [usable], plus what is left for the
 * other two. The first pane has to leave room for two minimums *and* the
 * divider between them, which is why this cannot just call [splitTwo].
 */
private fun splitThree(
    usable: Float,
    fraction: Float,
    minPane: Float,
    dividerThickness: Float
): Pair<Float, Float> {
    val needsForRest = minPane * 2 + dividerThickness
    if (usable < minPane + needsForRest) {
        val even = usable / 3f
        return even to usable - even
    }
    val a = (usable * fraction).coerceIn(minPane, usable - needsForRest)
    return a to usable - a
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.openlauncher.app.ui.widget.PipLayoutTest"`

Expected: PASS, 11 tests.

If `a board too small for the minimums divides evenly` fails on the trailing panes, the assertion to check is `r[0].w == 60f`: with `width = 200f`, `usable = 180f`, and `splitThree` taking the even branch, `a = 60f` and `rest = 120f`, which `splitTwo` then halves to `60f` each because `120f < 240f`.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts \
        app/src/main/java/com/openlauncher/app/ui/widget/PipLayout.kt \
        app/src/test/java/com/openlauncher/app/ui/widget/PipLayoutTest.kt
git commit -m "Add a pure solver for the PIP layout presets

Turns (preset, board size, two split fractions) into one rectangle per
pane. Rectangles rather than a split tree, because moving a pane slot
within the Compose tree reattaches the SurfaceView under it and visibly
reloads the embedded app (PipWidget.kt:215) — and hero+grid needs no
nesting to express anyway, being just three rectangles.

Minimum pane size is a dp floor rather than the old 0.15..0.85 fraction
clamp, which stops meaning anything once a split runs vertically: 15% of
a 720dp-tall board is 108dp.

No Compose or Android types in the file, so it comes with the project's
first unit tests."
```

---

### Task 2: Preset in settings, with migration

**Files:**
- Modify: `app/src/main/java/com/openlauncher/app/data/AppSettings.kt:111-124`
- Modify: `app/src/main/java/com/openlauncher/app/data/SettingsRepository.kt:51-56,132-145,217-222`
- Modify: `app/src/main/java/com/openlauncher/app/viewmodel/LauncherViewModel.kt:210`

**Interfaces:**
- Consumes: `LayoutPreset`, `paneCount`, `baselineSplit1`, `baselineSplit2` from Task 1.
- Produces: `AppSettings.pipLayoutPreset: LayoutPreset`; `LauncherViewModel.setPipLayoutPreset(preset: LayoutPreset)`. `AppSettings.pipAppCount` no longer exists — read `settings.pipLayoutPreset.paneCount` instead.

- [ ] **Step 1: Replace the field in `AppSettings.kt`**

Delete these two lines (currently at 118-119):

```kotlin
    // How many of pipAppPackages are actually shown (1-3)
    val pipAppCount: Int = 1,
```

and put in their place:

```kotlin
    // Pane topology. Replaces the old pipAppCount, which duplicated what a
    // preset already implies — keeping both invited the two to disagree. Read
    // the count as pipLayoutPreset.paneCount.
    val pipLayoutPreset: LayoutPreset = LayoutPreset.FOCUS,
```

Add the import at the top of the file, after `import androidx.compose.ui.graphics.toArgb`:

```kotlin
import com.openlauncher.app.ui.widget.LayoutPreset
```

- [ ] **Step 2: Persist it, and migrate the old key, in `SettingsRepository.kt`**

In the `Keys` object, replace the line `val PIP_APP_COUNT = intPreferencesKey("pip_app_count")` with:

```kotlin
        val PIP_LAYOUT_PRESET     = stringPreferencesKey("pip_layout_preset")
        // Read only, for the one-time migration in settingsFlow below. Installs
        // from before presets existed have this and no preset.
        val PIP_APP_COUNT_LEGACY  = intPreferencesKey("pip_app_count")
```

In the settings-reading block, replace the line `pipAppCount = prefs[Keys.PIP_APP_COUNT] ?: defaults.pipAppCount,` with:

```kotlin
                pipLayoutPreset  = prefs[Keys.PIP_LAYOUT_PRESET]
                    ?.let { runCatching { LayoutPreset.valueOf(it) }.getOrNull() }
                    ?: legacyPresetFor(prefs[Keys.PIP_APP_COUNT_LEGACY])
                    ?: defaults.pipLayoutPreset,
```

In the writing block, replace the line `prefs[Keys.PIP_APP_COUNT] = s.pipAppCount` with:

```kotlin
            prefs[Keys.PIP_LAYOUT_PRESET]  = s.pipLayoutPreset.name
```

Add this private helper at the bottom of the file, outside the class:

```kotlin
/**
 * Maps a pre-presets install's pane count onto the preset that arranges panes
 * the same way, so an existing setup keeps its layout and its split fractions
 * across the upgrade. Only the horizontal presets can be reached this way —
 * the old setting could not express a vertical split.
 */
private fun legacyPresetFor(count: Int?): LayoutPreset? = when (count) {
    1 -> LayoutPreset.FOCUS
    2 -> LayoutPreset.LANDSCAPE_PRIMARY_COMPANION
    3 -> LayoutPreset.ULTRAWIDE_TRIPLE
    else -> null
}
```

Add the import at the top of the file:

```kotlin
import com.openlauncher.app.ui.widget.LayoutPreset
```

- [ ] **Step 3: Replace the ViewModel setter**

In `LauncherViewModel.kt`, replace line 210:

```kotlin
    fun setPipAppCount(count: Int)    { updateSettings { copy(pipAppCount = count.coerceIn(1, 3)) } }
```

with:

```kotlin
    // Switching preset resets the fractions to that preset's baseline. Carrying
    // them over would reinterpret them: a 70:30 tuned for two panes becomes a
    // lopsided 70:15:15 as soon as a third appears.
    fun setPipLayoutPreset(preset: LayoutPreset) = updateSettings {
        copy(
            pipLayoutPreset = preset,
            pipPaneSplit    = preset.baselineSplit1,
            pipPaneSplit2   = preset.baselineSplit2
        )
    }
```

Add the imports at the top of `LauncherViewModel.kt`:

```kotlin
import com.openlauncher.app.ui.widget.LayoutPreset
import com.openlauncher.app.ui.widget.baselineSplit1
import com.openlauncher.app.ui.widget.baselineSplit2
```

- [ ] **Step 4: Fix the call sites so the project compiles**

Run: `./gradlew :app:compileAospDebugKotlin`

Expected: FAIL, with `Unresolved reference 'pipAppCount'` at these sites — change each to `settings.pipLayoutPreset.paneCount` (or the local equivalent):
- `MainActivity.kt:359` — `onSetPipAppCount = { vm.setPipAppCount(it) },` becomes `onSetPipLayoutPreset = { vm.setPipLayoutPreset(it) },`
- `HomeScreen.kt:122` and `HomeScreen.kt:611` — the parameter `onSetPipAppCount: (Int) -> Unit` becomes `onSetPipLayoutPreset: (LayoutPreset) -> Unit`
- `HomeScreen.kt:554` — forward the renamed parameter
- `HomeScreen.kt:705,713,721` — Task 5 replaces these rows entirely. For now, to keep the build green, replace all three `ContextRow` blocks with a single row that opens Settings is **not** wanted; instead leave three rows but point them at presets:

```kotlin
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "FOCUS",
                    icon    = Icons.Default.PictureInPicture,
                    tint    = if (pipLayoutPreset == LayoutPreset.FOCUS) accent else inactiveMenuTint,
                    onClick = { onSetPipLayoutPreset(LayoutPreset.FOCUS) },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "PRIMARY + COMPANION",
                    icon    = Icons.Default.PictureInPicture,
                    tint    = if (pipLayoutPreset == LayoutPreset.LANDSCAPE_PRIMARY_COMPANION) accent else inactiveMenuTint,
                    onClick = { onSetPipLayoutPreset(LayoutPreset.LANDSCAPE_PRIMARY_COMPANION) },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "TRIPLE WIDE",
                    icon    = Icons.Default.PictureInPicture,
                    tint    = if (pipLayoutPreset == LayoutPreset.ULTRAWIDE_TRIPLE) accent else inactiveMenuTint,
                    onClick = { onSetPipLayoutPreset(LayoutPreset.ULTRAWIDE_TRIPLE) },
                    isDayMode = isDayMode
                )
```

Wherever a `pipAppCount: Int` parameter was threaded into these composables, change it to `pipLayoutPreset: LayoutPreset` and pass `settings.pipLayoutPreset`.

- [ ] **Step 5: Verify it compiles and the tests still pass**

Run: `./gradlew :app:compileAospDebugKotlin :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Verify the migration on the device**

The emulator must be running. If it is not:

```bash
cd /media/dci-hongnv/Data/work/android/sdk/emulator
./emulator -avd dudulauncher_x64 -memory 5120 -gpu swiftshader_indirect \
  -skin 1920x720 -no-boot-anim -no-snapshot &
```

Then, **without clearing app data** (the point is to prove an existing install migrates):

```bash
cd /media/dci-hongnv/Data/work/android/openlauncher
./gradlew :app:assembleAospDebug
KEYS=/media/dci-hongnv/Data/work/android/StackLauncherPocStandalone-v2/StackLauncherPocStandaloneProject/signing/aosp-test-platform
OUT=app/build/outputs/apk/aospDebug
/media/dci-hongnv/Data/work/android/sdk/build-tools/36.0.0/apksigner sign \
  --key "$KEYS/platform.pk8" --cert "$KEYS/platform.x509.pem" \
  --out "$OUT/signed.apk" "$OUT/OpenLauncher-v0.0.9.apk"
adb install -r -d "$OUT/signed.apk"
adb shell am force-stop com.openlauncher.app
adb shell am start -n com.openlauncher.app/.MainActivity
```

Expected: the PIP widget shows the same number of panes as before the upgrade, with the same divider positions. A device that had 3 panes must still show 3.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/openlauncher/app/data/AppSettings.kt \
        app/src/main/java/com/openlauncher/app/data/SettingsRepository.kt \
        app/src/main/java/com/openlauncher/app/viewmodel/LauncherViewModel.kt \
        app/src/main/java/com/openlauncher/app/MainActivity.kt \
        app/src/main/java/com/openlauncher/app/ui/screen/HomeScreen.kt
git commit -m "Make the PIP pane topology a preset instead of a count

pipAppCount said how many panes to draw; a preset says that and which
axes they split along, so keeping both would have left two settings free
to disagree. Existing installs migrate by mapping 1/2/3 onto the three
horizontal presets, which preserves both the layout and the user's
divider positions.

Switching preset resets the split fractions to that preset's baseline —
carrying them over reinterprets them, since a 70:30 tuned for two panes
becomes a lopsided 70:15:15 the moment a third pane appears."
```

---

### Task 3: `PipWidget` lays out from the solver

Behaviour-preserving for the three horizontal presets. This is the task that proves the risky part: switching preset must not reload the embedded apps.

**Files:**
- Modify: `app/src/main/java/com/openlauncher/app/ui/widget/PipWidget.kt:112-128` (signature), `194-300` (arrangement)

**Interfaces:**
- Consumes: `solveLayout`, `PaneRect`, `LayoutPreset`, `paneCount` from Task 1; `AppSettings.pipLayoutPreset` from Task 2.
- Produces: `PipWidget(..., preset: LayoutPreset, ...)` replacing the `appCount: Int` parameter. `MIN_PANE_SIZE` constant.

- [ ] **Step 1: Replace the `appCount` parameter**

In the `PipWidget` signature, replace `appCount: Int,` with:

```kotlin
    preset: LayoutPreset,
```

At the call site in `HomeScreen.kt` (the `PipWidget(` block around line 491), replace `appCount = settings.pipAppCount,` with `preset = settings.pipLayoutPreset,`.

- [ ] **Step 2: Add the minimum pane size beside the other dimensions**

In `PipWidget.kt`, after `private val DIVIDER_GRIP_HEIGHT = 64.dp`:

```kotlin
// Floor for a pane along whichever axis it is being split on. A dp value rather
// than a fraction of the board: 15% of the 720dp height is 108dp, too small to
// put an app in, while 15% of the 1920dp width is 288dp. One number cannot mean
// both, which is why PANE_SPLIT_RANGE could not follow the splits vertical.
private val MIN_PANE_SIZE = 120.dp
```

- [ ] **Step 3: Replace the geometry block**

In `PipWidget`, the early-return for a single pane currently reads `if (appCount <= 1) {`. Change it to:

```kotlin
    if (preset.paneCount <= 1) {
```

Then replace everything from `val paneAreaWidth = maxWidth - DIVIDER_LAYOUT_WIDTH * dividerCount` through the `val positionWidths = listOf(width0, width1, width2)` line with:

```kotlin
        // Geometry comes from solveLayout so that the arrangement, the Settings
        // thumbnail and the unit tests all read from one definition and cannot
        // drift apart.
        val fraction1 = previewFraction.takeIf { previewDividerIndex == 0 } ?: localCommittedFraction
        val fraction2 = previewFraction.takeIf { previewDividerIndex == 1 } ?: localCommittedFraction2
        val rects = solveLayout(
            preset           = preset,
            width            = maxWidth.value,
            height           = maxHeight.value,
            split1           = fraction1,
            split2           = fraction2,
            dividerThickness = DIVIDER_LAYOUT_WIDTH.value,
            minPane          = MIN_PANE_SIZE.value
        )
```

Then replace each slot's positioning modifier. Where slot 0 currently has:

```kotlin
                    .offset(x = positionOffsets[pos0])
                    .width(positionWidths[pos0])
```

use:

```kotlin
                    .paneRect(rects[pos0])
```

and the same for `pos1` and `pos2`, with `rects[pos1]` and `rects[pos2]`.

Add this modifier extension at the bottom of `PipWidget.kt`:

```kotlin
/**
 * Positions and sizes a pane slot. Every slot is placed this way and never
 * moved within the Compose tree — see the note above `positionOf`: reordering
 * slots reattaches the SurfaceView underneath and visibly reloads the embedded
 * app, whereas changing only offset and size never touches its attachment.
 */
private fun Modifier.paneRect(rect: PaneRect): Modifier = this
    .offset(x = rect.x.dp, y = rect.y.dp)
    .size(width = rect.w.dp, height = rect.h.dp)
```

Add `import androidx.compose.foundation.layout.size` to the imports if `layout.*` is not already wildcard-imported (it is: `androidx.compose.foundation.layout.*`, so no new import is needed).

- [ ] **Step 4: Point the dividers at the solved rectangles**

The two divider `Box` blocks currently position themselves with `.offset(x = width0)` and `.offset(x = width0 + DIVIDER_LAYOUT_WIDTH + width1)`. Replace those with gaps derived from the rectangles, which works for both axes:

```kotlin
            // The gap after pane N starts where pane N ends. Reading it off the
            // solved rectangles keeps the divider on the boundary for any
            // preset, including one that splits vertically.
            val gap0Start = rects[0].let { if (preset.splitsVerticallyFirst) it.h else it.w }
            val gap1Start = if (rects.size > 2) {
                rects[1].let { if (preset.splitsVerticallyFirst) it.y + it.h else it.x + it.w }
            } else 0f
```

Add to `PipLayout.kt`:

```kotlin
/**
 * Whether this preset's *first* divider runs across the board rather than down
 * it. The second divider, where there is one, is always the other way round:
 * hero+grid splits the board horizontally and then splits its bottom row
 * vertically.
 */
val LayoutPreset.splitsVerticallyFirst: Boolean
    get() = this == LayoutPreset.PORTRAIT_STACK || this == LayoutPreset.PORTRAIT_HERO_GRID
```

Leave the `PaneDivider` calls themselves alone in this task — they stay horizontal-only, which is correct because only the horizontal presets are reachable until Task 5.

- [ ] **Step 5: Verify it builds and the tests pass**

Run: `./gradlew :app:compileAospDebugKotlin :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Verify on the device that switching preset does not reload the apps**

This is the step the whole design is arranged around. Install as in Task 2 Step 6, then:

```bash
adb logcat -c
# Assign apps to all three PIP slots through the UI first, and let them load.
# Then switch preset via the PIP widget's context menu: Triple -> Focus ->
# Triple -> Primary+Companion.
adb logcat -d -s TaskEmbedder | grep -cE "createVirtualDisplay|surfaceCreated"
```

Expected: **zero** new `createVirtualDisplay` lines after the apps first loaded. Each switch should move panes with the embedded apps staying on screen throughout. If you see `createVirtualDisplay` on a switch, a slot moved in the tree — recheck that all three slots are still declared unconditionally in fixed order and only their `paneRect` changed.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/openlauncher/app/ui/widget/PipWidget.kt \
        app/src/main/java/com/openlauncher/app/ui/widget/PipLayout.kt \
        app/src/main/java/com/openlauncher/app/ui/screen/HomeScreen.kt
git commit -m "Lay the PIP panes out from the solver

Replaces the hand-rolled offset/width arithmetic, which could only split
along x, with rectangles from solveLayout. Slots stay declared in fixed
tree order and receive only a new rectangle, so switching preset moves
panes without reattaching any SurfaceView — verified on device by
watching for createVirtualDisplay lines that never appear.

Divider positions now read off the solved rectangles rather than
recomputing widths, which is what lets a divider sit on a horizontal
boundary in a later task without special-casing."
```

---

### Task 4: A divider that works on either axis

**Files:**
- Modify: `app/src/main/java/com/openlauncher/app/ui/widget/PipWidget.kt` (`PaneDivider`, around 352-440)

**Interfaces:**
- Consumes: `LayoutPreset`, `splitsVerticallyFirst` from Tasks 1 and 3.
- Produces: `PaneDivider(..., isVertical: Boolean, gapStart: Dp, referenceLengthPx: Float, ...)` — `isVertical` meaning the divider itself is a vertical bar splitting the board along x. The old `gapStartX` and `referenceWidthPx` parameters are renamed to drop the axis from their names.

- [ ] **Step 1: Generalise the signature and the gesture**

Replace `PaneDivider`'s parameters `gapStartX: Dp,` and `referenceWidthPx: Float,` with:

```kotlin
    isVertical: Boolean,
    gapStart: Dp,
    referenceLengthPx: Float,
```

Replace the modifier chain's first three lines:

```kotlin
        modifier = Modifier
            .offset(x = gapStartX + DIVIDER_LAYOUT_WIDTH / 2 - DIVIDER_TOUCH_WIDTH / 2)
            .width(DIVIDER_TOUCH_WIDTH)
            .fillMaxHeight()
```

with:

```kotlin
        modifier = Modifier
            .then(
                if (isVertical) Modifier
                    .offset(x = gapStart + DIVIDER_LAYOUT_WIDTH / 2 - DIVIDER_TOUCH_WIDTH / 2)
                    .width(DIVIDER_TOUCH_WIDTH)
                    .fillMaxHeight()
                else Modifier
                    .offset(y = gapStart + DIVIDER_LAYOUT_WIDTH / 2 - DIVIDER_TOUCH_WIDTH / 2)
                    .height(DIVIDER_TOUCH_WIDTH)
                    .fillMaxWidth()
            )
```

In the drag handler, replace `.pointerInput(referenceWidthPx, committedFraction) {` with `.pointerInput(isVertical, referenceLengthPx, committedFraction) {` and replace the two places that read `dragAmount.x` and `referenceWidthPx`:

```kotlin
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (referenceLengthPx > 0f) {
                            totalDragPx += if (isVertical) dragAmount.x else dragAmount.y
                            onPreviewChange(dragStartFraction + totalDragPx / referenceLengthPx)
                        }
                    },
                    onDragEnd = {
                        onCommit(dragStartFraction + totalDragPx / referenceLengthPx.coerceAtLeast(1f))
                        onPreviewChange(null)
                        onDragEnd()
                    },
```

Note both `coerceIn(PANE_SPLIT_RANGE)` calls are gone: clamping now happens inside `solveLayout`, against a dp floor, so the fraction stored in settings is the raw user intent and the solver decides what is reachable. Delete the now-unused `private val PANE_SPLIT_RANGE = 0.15f..0.85f` and the four `.coerceIn(PANE_SPLIT_RANGE)` calls elsewhere in the file that referenced it.

- [ ] **Step 2: Reshape the grip for a horizontal bar**

In the grip `Box`, replace:

```kotlin
                .width(DIVIDER_GRIP_WIDTH)
                .height(DIVIDER_GRIP_HEIGHT)
```

with:

```kotlin
                .width(if (isVertical) DIVIDER_GRIP_WIDTH else DIVIDER_GRIP_HEIGHT)
                .height(if (isVertical) DIVIDER_GRIP_HEIGHT else DIVIDER_GRIP_WIDTH)
```

so the pill lies along the divider rather than across it.

- [ ] **Step 3: Update the two call sites**

Pass the new arguments. The first divider follows the preset's first axis; the second is always the other one:

```kotlin
            PaneDivider(
                isVertical        = !preset.splitsVerticallyFirst,
                gapStart          = gap0Start.dp,
                referenceLengthPx = if (preset.splitsVerticallyFirst) paneAreaHeightPx else paneAreaWidthPx,
                committedFraction = fraction1,
                ...
```

and for the second divider, `isVertical = true` and `referenceLengthPx = paneAreaWidthPx`, because in both three-pane presets the second split runs along x.

Define the two reference lengths next to `rects`:

```kotlin
        val paneAreaWidthPx  = with(density) { (maxWidth - DIVIDER_LAYOUT_WIDTH * dividerCount).toPx() }
        val paneAreaHeightPx = with(density) { (maxHeight - DIVIDER_LAYOUT_WIDTH).toPx() }
```

- [ ] **Step 4: Verify nothing changed for the horizontal presets**

Run: `./gradlew :app:compileAospDebugKotlin :app:testDebugUnitTest`, then install as in Task 2 Step 6.

Expected: dragging both dividers in Triple Wide behaves exactly as before, and dragging one to the far end now leaves the trailing pane 120dp wide rather than 15% of the board.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/openlauncher/app/ui/widget/PipWidget.kt
git commit -m "Let a PIP divider run along either axis

The divider was hard-wired to x: it offset on x, filled the height, and
read dragAmount.x. It now takes an axis, and the grip pill lies along the
bar rather than across it.

Fraction clamping moves out of the gesture and into solveLayout, where
the floor is a dp minimum instead of PANE_SPLIT_RANGE's 0.15..0.85. The
fraction in settings is now raw user intent and the solver decides what
is reachable, which is what makes one clamp correct on both axes."
```

---

### Task 5: The two portrait presets, live

**Files:**
- Modify: `app/src/main/java/com/openlauncher/app/ui/widget/PipWidget.kt` (divider count)
- Modify: `app/src/main/java/com/openlauncher/app/ui/screen/HomeScreen.kt` (context-menu rows)

**Interfaces:**
- Consumes: everything from Tasks 1-4.
- Produces: no new API. `PORTRAIT_STACK` and `PORTRAIT_HERO_GRID` become reachable.

- [ ] **Step 1: Derive the divider count from the preset**

`dividerCount` is currently computed from `appCount`. Replace its definition with:

```kotlin
        // One divider per split: two panes have one, three have two, on either
        // axis.
        val dividerCount = preset.paneCount - 1
```

- [ ] **Step 2: Add the two rows to the PIP context menu**

After the `TRIPLE WIDE` `ContextRow` added in Task 2 Step 4:

```kotlin
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "PORTRAIT STACK",
                    icon    = Icons.Default.PictureInPicture,
                    tint    = if (pipLayoutPreset == LayoutPreset.PORTRAIT_STACK) accent else inactiveMenuTint,
                    onClick = { onSetPipLayoutPreset(LayoutPreset.PORTRAIT_STACK) },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "HERO + GRID",
                    icon    = Icons.Default.PictureInPicture,
                    tint    = if (pipLayoutPreset == LayoutPreset.PORTRAIT_HERO_GRID) accent else inactiveMenuTint,
                    onClick = { onSetPipLayoutPreset(LayoutPreset.PORTRAIT_HERO_GRID) },
                    isDayMode = isDayMode
                )
```

- [ ] **Step 3: Verify on the device**

Install as in Task 2 Step 6, assign three apps, then switch to Portrait Stack and Hero + Grid.

Expected:
- Stack shows two full-width panes with a horizontal divider between them, draggable up and down.
- Hero + Grid shows one pane across the top and two beneath, with a horizontal divider and a vertical one, each draggable.
- No `createVirtualDisplay` in `adb logcat -d -s TaskEmbedder` on either switch.
- On this 1920×720 board the panes are extremely letterboxed (Stack gives two panes of about 1920×350, an aspect of 5.5:1). **This is expected and is not a bug** — the spec maps these presets to portrait screens, and the point of not hiding them is that a user can try one and pick again. Embedded apps stay forced landscape by the orientation guard in `PipWidget.kt:1143`, which is correct for a wide pane; it will need revisiting only if portrait hardware is ever targeted.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/openlauncher/app/ui/widget/PipWidget.kt \
        app/src/main/java/com/openlauncher/app/ui/screen/HomeScreen.kt
git commit -m "Make the two portrait PIP presets reachable

Divider count now follows the preset's pane count rather than a
horizontal app count, which is all that was left holding the vertical
layouts back.

On a 1920x720 board these give very letterboxed panes — Stack is two of
roughly 1920x350, 5.5:1 — which is why the spec maps them to portrait
screens. They are still offered rather than hidden: the screen classes
are cumulative, not exclusive, and a user who tries one on a wide board
can see it does not suit and pick again."
```

---

### Task 6: Pick a preset from a picture

**Files:**
- Create: `app/src/main/java/com/openlauncher/app/ui/widget/PipPresetThumbnail.kt`
- Modify: `app/src/main/java/com/openlauncher/app/ui/screen/SettingsScreen.kt:401` (inside the existing `SettingsSection("Picture-in-Picture")`)

**Interfaces:**
- Consumes: `solveLayout`, `LayoutPreset`, `presetsForAspect`, `baselineSplit1`, `baselineSplit2` from Task 1.
- Produces: `@Composable fun PipPresetThumbnail(preset: LayoutPreset, isSelected: Boolean, accent: Color, isDayMode: Boolean, modifier: Modifier)`.

- [ ] **Step 1: Draw the thumbnail from the solver**

Create `app/src/main/java/com/openlauncher/app/ui/widget/PipPresetThumbnail.kt`:

```kotlin
package com.openlauncher.app.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A preset drawn as a small diagram, so the picker shows the arrangement rather
 * than asking the user to decode a name like "ULTRAWIDE_TRIPLE".
 *
 * The panes are placed by [solveLayout] — the same function that lays out the
 * real thing — at thumbnail scale and at the preset's baseline ratios. That is
 * the point of routing it through the solver rather than hand-drawing five
 * pictures: a thumbnail cannot end up advertising a layout the app does not
 * actually produce.
 */
@Composable
fun PipPresetThumbnail(
    preset: LayoutPreset,
    isSelected: Boolean,
    accent: Color,
    isDayMode: Boolean,
    modifier: Modifier = Modifier
) {
    // Drawn on the board's own proportions rather than a square, so a wide
    // preset reads as wide.
    val boardW = 64f
    val boardH = 36f
    val gap = 2f

    val paneFill = when {
        isSelected -> accent
        isDayMode -> Color(0xFF9AA1AC)
        else -> Color(0xFF4A5158)
    }

    Box(
        modifier = modifier
            .size(width = 72.dp, height = 44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isDayMode) Color(0xFFE9EBEE) else Color(0xFF16181B))
            .padding(4.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val rects = solveLayout(
                preset = preset,
                width = boardW,
                height = boardH,
                split1 = preset.baselineSplit1,
                split2 = preset.baselineSplit2,
                dividerThickness = gap,
                // No floor at thumbnail scale: the baselines never approach it,
                // and a 120dp minimum would swamp a 64-unit board.
                minPane = 0f
            )
            val sx = size.width / boardW
            val sy = size.height / boardH
            rects.forEach { r ->
                drawRoundRect(
                    color = paneFill,
                    topLeft = Offset(r.x * sx, r.y * sy),
                    size = Size(r.w * sx, r.h * sy),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * sx, 2f * sx)
                )
            }
        }
    }
}
```

- [ ] **Step 2: Add the picker row to Settings**

In `SettingsScreen.kt`, immediately after `SettingsSection("Picture-in-Picture") {` and its `fun appLabel(...)` helper, insert:

```kotlin
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val boardAspect = configuration.screenWidthDp.toFloat() /
                configuration.screenHeightDp.toFloat().coerceAtLeast(1f)

            SettingsRow(
                label    = "Layout",
                sublabel = when (settings.pipLayoutPreset) {
                    LayoutPreset.FOCUS -> "One app"
                    LayoutPreset.LANDSCAPE_PRIMARY_COMPANION -> "Two side by side, 70 : 30"
                    LayoutPreset.ULTRAWIDE_TRIPLE -> "Three side by side, 50 : 25 : 25"
                    LayoutPreset.PORTRAIT_STACK -> "Two stacked, 65 : 35"
                    LayoutPreset.PORTRAIT_HERO_GRID -> "One over two, 65 : 35"
                },
                icon     = Icons.Default.PictureInPicture
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Ordered by how well each suits this board, but none are
                    // hidden — the screen classes overlap, and a user who picks
                    // an ill-suited one can see that and pick again.
                    presetsForAspect(boardAspect).forEach { preset ->
                        PipPresetThumbnail(
                            preset     = preset,
                            isSelected = settings.pipLayoutPreset == preset,
                            accent     = accent,
                            isDayMode  = isDayMode,
                            modifier   = Modifier.clickable {
                                onUpdate {
                                    copy(
                                        pipLayoutPreset = preset,
                                        pipPaneSplit    = preset.baselineSplit1,
                                        pipPaneSplit2   = preset.baselineSplit2
                                    )
                                }
                            }
                        )
                    }
                }
            }

            SettingsDivider()
```

Add these imports to `SettingsScreen.kt`:

```kotlin
import com.openlauncher.app.ui.widget.LayoutPreset
import com.openlauncher.app.ui.widget.PipPresetThumbnail
import com.openlauncher.app.ui.widget.baselineSplit1
import com.openlauncher.app.ui.widget.baselineSplit2
import com.openlauncher.app.ui.widget.presetsForAspect
```

- [ ] **Step 3: Verify on the device**

Install as in Task 2 Step 6, open Settings, scroll to Picture-in-Picture.

Expected: five thumbnails, the one matching the current preset filled in the accent colour, Triple Wide first on this 1920×720 board. Tapping one changes the layout on Home and moves the divider to that preset's baseline.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/openlauncher/app/ui/widget/PipPresetThumbnail.kt \
        app/src/main/java/com/openlauncher/app/ui/screen/SettingsScreen.kt
git commit -m "Pick a PIP preset from a diagram rather than a name

Names like ULTRAWIDE_TRIPLE ask the user to decode the arrangement. The
picker now shows each one drawn at thumbnail scale, on the board's own
proportions so a wide preset reads as wide.

The diagram is drawn by solveLayout, the same function that lays out the
real panes, which is the reason not to hand-draw five pictures: a
thumbnail cannot advertise a layout the app does not actually produce.

Thumbnails are ordered by how well each suits the current board and none
are hidden."
```

---

### Task 7: Swap moves into edit mode

**Files:**
- Modify: `app/src/main/java/com/openlauncher/app/ui/widget/PipWidget.kt` (`PaneDivider` tap handler, `PipPane` overlay)
- Modify: `app/src/main/java/com/openlauncher/app/ui/screen/HomeScreen.kt` (pass `editMode` through)

**Interfaces:**
- Consumes: `LayoutPreset`, `paneCount`; the existing `isEditing` parameter and `paneOrder`/`onSwap` plumbing.
- Produces: `onSwap` changes signature from `(dividerIndex: Int) -> Unit` to `(slotA: Int, slotB: Int) -> Unit`.

- [ ] **Step 1: Delete tap-to-swap from the divider**

Remove this whole block from `PaneDivider`'s modifier chain, along with the `onTap: () -> Unit` parameter and the argument at both call sites:

```kotlin
            // Separate from the drag detector above: a plain tap (no
            // touch-slop movement) never triggers detectDragGestures'
            // callbacks, so it's free to swap this divider's two adjacent
            // apps' slots instead — a quick way to re-order without a drag.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            },
```

Two reasons it has to go rather than move. It cannot express hero+grid: the horizontal divider there separates one pane from a *pair*, so "swap the two adjacent panes" has no referent. And it shares one touch target with the drag, so a nudge that never crosses the touch slop registers as a tap and silently swaps two apps.

- [ ] **Step 2: Change `onSwap` to name both slots**

In `PipWidget`'s signature, replace `onSwap: (dividerIndex: Int) -> Unit = {},` with:

```kotlin
    onSwap: (slotA: Int, slotB: Int) -> Unit = { _, _ -> },
```

In `LauncherViewModel.kt`, replace `swapPipApps`'s body so it permutes by slot rather than by divider. Find the existing `fun swapPipApps(dividerIndex: Int)` and replace it with:

```kotlin
    /**
     * Swaps where two slots display. Only pipPaneOrder changes — never
     * pipAppPackages — so no embedded app is torn down or relaunched; the panes
     * just trade rectangles.
     */
    fun swapPipApps(slotA: Int, slotB: Int) = updateSettings {
        val order = pipPaneOrder.toMutableList()
        val posA = order.indexOf(slotA)
        val posB = order.indexOf(slotB)
        if (posA < 0 || posB < 0) return@updateSettings this
        order[posA] = slotB
        order[posB] = slotA
        copy(pipPaneOrder = order)
    }
```

Update `MainActivity.kt`'s `onSwapPipApps` wiring and `HomeScreen.kt`'s parameter to the two-argument form.

- [ ] **Step 3: Add the edit-mode swap overlay**

In `PipPane`, at the end of the pane's `Box`, add an overlay shown only while editing. `isEditing` already disables input forwarding (`inputEnabled = isActive && !isEditing`), which is what frees these taps from the embedded app:

```kotlin
        // Only reachable while editing, because outside edit mode every touch
        // in a pane is forwarded to the app inside it.
        if (isEditing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { onEditTap() }
                    .then(
                        if (isSwapCandidate) Modifier.border(2.dp, accent, PaneShape)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = paneLabel,
                    color = if (isSwapCandidate) accent else Color.White,
                    fontSize = 20.sp,
                    letterSpacing = 2.sp
                )
            }
        }
```

Add the three parameters to `PipPane`: `paneLabel: String`, `isSwapCandidate: Boolean`, `onEditTap: () -> Unit`.

The label makes the swap comprehensible: slots are an invisible concept today, so a user has no way to tell which pane they are trading. Pass `"A"`, `"B"`, `"C"` by visual position.

- [ ] **Step 4: Hold the first tap in `PipWidget`**

Beside the other `remember`s in `PipWidget`:

```kotlin
    // First tap selects, second swaps. Tap-then-tap rather than dragging one
    // pane onto another: dragging a live SurfaceView leaves nothing sensible to
    // show under the finger.
    var pendingSwapSlot by remember { mutableStateOf<Int?>(null) }
```

Each pane's `onEditTap` becomes:

```kotlin
                onEditTap = {
                    val first = pendingSwapSlot
                    when {
                        first == null -> pendingSwapSlot = SLOT
                        first == SLOT -> pendingSwapSlot = null
                        else -> {
                            onSwap(first, SLOT)
                            pendingSwapSlot = null
                        }
                    }
                },
                isSwapCandidate = pendingSwapSlot == SLOT,
```

with `SLOT` being the literal `0`, `1` or `2` for that slot. Reset the selection when edit mode ends:

```kotlin
    LaunchedEffect(isEditing) { if (!isEditing) pendingSwapSlot = null }
```

- [ ] **Step 5: Verify on the device**

Install as in Task 2 Step 6. Assign three apps, enter edit mode with the widget-edit button in the Home header.

Expected:
- Each pane dims and shows A / B / C.
- Tapping one outlines it in the accent colour; tapping a second swaps the two; tapping the same one again deselects.
- The embedded apps stay running throughout — check `adb logcat -d -s TaskEmbedder | grep -c createVirtualDisplay` does not increase.
- Leaving edit mode clears any selection.
- Dragging a divider still resizes, and a small nudge no longer swaps anything.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/openlauncher/app/ui/widget/PipWidget.kt \
        app/src/main/java/com/openlauncher/app/ui/screen/HomeScreen.kt \
        app/src/main/java/com/openlauncher/app/MainActivity.kt \
        app/src/main/java/com/openlauncher/app/viewmodel/LauncherViewModel.kt
git commit -m "Move PIP pane swapping into edit mode

Tapping a divider to swap its two neighbours cannot express hero+grid,
where the horizontal divider separates one pane from a pair and 'the two
adjacent panes' has no referent. It also shared a touch target with the
resize drag, so a nudge that never crossed the touch slop registered as a
tap and silently traded two apps.

Swapping is now a pane-level action inside the existing edit mode, which
is the only place pane taps are available at all — outside it every touch
is forwarded to the embedded app. Tap one pane then another; panes are
labelled A/B/C because slots were otherwise an invisible concept.

Still only permutes pipPaneOrder, so panes trade rectangles and no
embedded app is torn down."
```

---

## Self-Review

**Spec coverage.** All five presets in the spec are implemented in Task 1 and reachable by Task 5. The spec's screen-class mapping is Task 1's `presetsForAspect`, as ordering rather than filtering per the decision recorded in Global Constraints. Every baseline ratio in the spec has a matching assertion in Task 1's tests. The spec's `LayoutPreset` enum is reproduced with the spec's own member names.

**Deliberate additions beyond the spec.** The spec does not discuss resize or swap; Tasks 4 and 7 cover them because the presets break both. It also does not discuss a minimum pane size, which Task 1 adds because the existing fraction clamp is unusable on a vertical split. Thumbnails in Task 6 are the user's request, not the spec's.

**Known gap, deliberately deferred.** The landscape orientation guard at `PipWidget.kt:1143` forces every embedded app landscape. On this 1920×720 board that is correct for all five presets, since even the portrait presets produce wide panes. It becomes wrong only on portrait hardware, where a stack yields tall panes; no task addresses it, and Task 5 Step 3 records why.

**Type consistency.** `solveLayout`'s parameter list is identical in Task 1's definition, Task 3's call and Task 6's call. `PaneRect`'s field names `x, y, w, h` are used unchanged in Tasks 1, 3 and 6. `onSwap` is `(dividerIndex: Int)` before Task 7 and `(slotA: Int, slotB: Int)` after, changed in one place along with both its call sites and the ViewModel. `paneCount`, `baselineSplit1`, `baselineSplit2` and `splitsVerticallyFirst` are all defined in Task 1 or Task 3 Step 4 before first use.
