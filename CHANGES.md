# Ronaq divergences from upstream KuiklyUI

Every change this fork carries, why it exists, and which PRD clause drove it.
本 fork 相对上游的全部改动、原因，以及对应的 PRD 依据。

The rules that govern this file are in the client's `CLAUDE.md`:

- Only **framework-level general capability** lives here. Business bridges — SVGA,
  VAP, Agora, Tencent IM — stay in `bridge/` and must never sink into the fork.
- Each upstream file changes in its own commit, unmixed, so future merges stay cheap.
- Prefer upstreaming. Once a change is merged upstream its maintenance cost is gone.
- 仅框架级通用能力入 fork；业务桥接留在 bridge/。每个上游文件单独成 commit。优先回流上游。

---

## 1. `KNode` delivers `LocalLayoutDirection` to the node — RTL

**Files** · `compose/.../ui/node/KNode.kt`
**Driven by** · Charter C-5 (Arabic is a launch language, not a later addition)
**Date** · 2026-08-07

### What upstream Compose does

`LayoutNode.compositionLocalMap`'s setter is the single point where a node learns the
three locals that decide how it measures:

```kotlin
density          = value[LocalDensity]
layoutDirection  = value[LocalLayoutDirection]
viewConfiguration = value[LocalViewConfiguration]
```

`Row`, `Arrangement`, `Alignment` and `padding(start/end)` all read
`layoutDirection` back out through the measure scope.

### What goes wrong here

`KNode` — the `LayoutNode` subclass this renderer instantiates for *every* composed
layout, via `ComposeUiNode.LayoutConstructor` / `ShadowLayoutConstructor` — overrides
`compositionLocalMap` with its own setter that forwards **only** `density`.
`layoutDirection` and `viewConfiguration` are dropped on the floor.

Because the override shadows the base property, `LayoutNode`'s setter never runs for a
KNode at all. So:

- `CompositionLocalProvider(LocalLayoutDirection provides Rtl)` is inert. Every node in
  the tree measures `Ltr` no matter what the application provides, and an Arabic build
  renders Arabic copy in a left-to-right layout.
- A direction *change* has no path whatsoever. Switching language inside a running app
  swapped the strings and left the layout untouched — see the before screenshot in the
  client's `docs/issue/kuikly-rtl-layout.md`.

This also explains an earlier misdiagnosis on this fork: a probe placed in
`LayoutNode`'s setter "never fired", which was read as *nothing sets the map*. The map
is set on every node; the base setter is simply shadowed.

### The change

```kotlin
override var compositionLocalMap = CompositionLocalMap.Empty
    set(value) {
        field = value
        density = value[LocalDensity]
        // Ronaq: also deliver LocalLayoutDirection, as LayoutNode's own setter does.
        layoutDirection = value[LocalLayoutDirection]
        …
    }
```

`viewConfiguration` is deliberately **not** added. It is a separate upstream omission
with its own blast radius — touch slop, long-press and double-tap timings for the whole
app — and belongs in its own change with its own gesture evidence, not in an RTL fix.

### Superseded

This replaces the earlier divergence *"Layout direction is inherited at attach"* in
`ui/node/LayoutNode.kt` (`layoutDirection = parent?.layoutDirection ?: owner.layoutDirection`),
which was a workaround for the symptom above. It has been removed: it only ever
propagated the scene's create-time direction, so a runtime change still reached nothing,
and it masked the real defect. Verified redundant — with it removed, the Arabic Home, Me
and Settings screens are pixel-identical to the build that still carried it.

`ui/layout/LookaheadScope.kt` is now the only node factory in the module that does not
set `SetResolvedCompositionLocals`. Its node is virtual (`isVirtual = true`, asserted to
carry no modifier) so nothing measures with its direction, and upstream androidx omits it
there too. Left as-is.

## 2. `TextAlign.Start` / `End` resolve against the layout direction — RTL

**Files** · `compose/.../foundation/text/KuiklyTextExtension.kt`,
`compose/.../foundation/text/modifiers/TextStringRichNode.kt`,
`compose/.../foundation/text/BasicTextField.kt`,
`compose/.../foundation/text/CoreTextField.kt`
**Driven by** · Charter C-5
**Date** · 2026-08-07

### What goes wrong here

The native text view understands only absolute `left` / `center` / `right`, so the
Compose `TextAlign` has to be resolved before it is handed over. Both places that do
this collapsed the relative values onto the left:

```kotlin
// KuiklyTextExtension.applyTextAlign
TextAlign.Left, TextAlign.Unspecified, null -> LEFT
TextAlign.Center -> CENTER
TextAlign.Right  -> RIGHT
else             -> LEFT      // Start, End, Justify
```

Compose's contract (`resolveParagraphStyleDefaults`) is that `Unspecified` resolves to
`Start`, and `Start` / `End` resolve against the layout direction. Two consequences:

- **RTL**: text always hugged the left edge. Invisible for a wrap-content label, wrong
  for any text that fills its box — a `weight(1f)` row label, a `fillMaxWidth` title. On
  the Arabic Me and Settings screens the rows mirrored correctly (icon and chevron
  swapped sides) while every label stayed pinned to the left, which reads as a broken
  layout rather than an RTL one.
- **LTR**: `TextAlign.End` rendered *left*. Wrong in every locale.

### The change

`applyTextAlign` and `TextAreaAttr.setTextStyle` take the layout direction and resolve
`Start` / `End` against it; `Justify` and unresolved values follow `Start`, which is what
the old `else` branch intended. Callers supply the direction they already hold:
`TextStringRichNode` from `requireLayoutDirection()`, `CoreTextField` from
`LocalLayoutDirection.current` (it already read it for `resolveDefaults`).

Two propagation points were needed because a direction change is not a content change:

- `TextStringRichNode` records the direction its alignment was resolved against and
  re-resolves in `measure` when it differs. A direction change reaches a modifier node
  only as a measurement invalidation — `updateLayoutProperties()` does not re-run — so
  without this a text whose content did not change (a number, a Latin name) would keep
  the previous direction's alignment after an in-app language switch.
- `CoreTextField` adds `set(layoutDirection)` alongside `set(textStyle)`, for the same
  reason: the style is unchanged when only the language changes.

Behaviour in LTR is unchanged by construction, and confirmed by pixel-identical English
screenshots across the change (see Verification).

## Verification (§1 and §2)

Device: emulator `emulator-5560`, android-35, 1080x2400, `gp` debug, mock server on
:8099. Screenshots in the client's `build/rtl-evidence/`.

- **Arabic at launch** (`--es lang ar`) — Home, Me, Square, Chats, Room and Settings are
  structural mirrors of their English counterparts: grid start edge, row order, tab
  order, chip order, mic-seat numbering, composer, back chevrons, icon/label/value
  columns, and now the labels themselves.
- **Arabic by in-app switch** (Settings → العربية, no restart) — the Settings screen is
  pixel-identical to the launched-in-Arabic build apart from which language row carries
  the checkmark. Before this change the same action produced Arabic text in an
  unmirrored left-to-right layout.
- **English** — Home, Me and Settings are pixel-identical to the pre-change build
  (0 pixels differing above threshold, status bar excluded).
- `scripts/check-rules.sh`: `RESULT: PASS gates=9`.
- `scripts/walk-flow.sh`: **not established — SKIPPED, not passed.** Another process was
  driving the same emulator throughout (`com.android.commands.monkey` in logcat, eight
  launches in a 45-second sample), and the script's own `app was not in front; bringing it
  back` notice accompanied every failed step. The flows it reported failing — wallet,
  tasks, rank — were then exercised by hand with a foreground guard and behaved normally.
  This gate needs re-running on an uncontended device before the change is accepted.

### Upstream

Both are worth offering, and both restore documented Compose semantics rather than
adding a Ronaq concept:

- §1 is one line and repairs an override that silently diverges from its own base class.
  A maintainer may prefer to delete the `KNode` override entirely and inherit
  `LayoutNode`'s — that is the better shape, but it also starts forwarding
  `viewConfiguration`, which is a gesture-timing change and needs its own evidence.
- §2 is a plain bug for `TextAlign.End` even outside RTL markets.

## 3. The iOS text renderer stops forcing left-to-right — RTL

**Files** · `core-render-ios/Extension/AdvancedComps/KRRichTextView.m`
**Driven by** · Charter C-5
**Date** · 2026-08-07

### What goes wrong here

On iOS every Arabic string rendered with its characters in **reversed order** — «رونق» as
«قنور», «الإعدادات» as «تاداعإلا» — while the layout around it mirrored correctly and
Latin text was untouched. Android and Web render the same shared Kotlin correctly, so
nothing above the renderer was reversing the string.

`KRRichTextShadow` builds the `NSAttributedString` for every text node and stamps two
"force LTR" decisions onto it:

```objc
// p_createSpanAttributedStringWithAttributes — every span
[attributedString addAttribute:NSWritingDirectionAttributeName
                         value:@[@(NSWritingDirectionLeftToRight | NSWritingDirectionOverride)]
                         range:range];
// p_applyTextAttributeWithAttr — every paragraph style
style.baseWritingDirection = NSWritingDirectionLeftToRight;
```

The first one is the defect. `LeftToRight | Override` is the Unicode **LRO** control
(U+202D): it does not merely pick a base direction, it *overrides the bidi class of every
character in the range*, so strong right-to-left characters are resolved to an even
(left-to-right) embedding level and laid out in logical order from the left. Arabic then
reads backwards. Latin is unaffected because LTR is where the algorithm already put it.

Measured directly against CoreText, `اللغة` with a system font:

| attributes | run direction | character indices in visual order |
| --- | --- | --- |
| `LRO` + base LTR (as shipped) | LTR | `0, 1, 2, 3, 3, 4` — logical order, drawn leftward |
| base LTR, no `LRO` | RTL | `4, 3, 3, 2, 1, 0` — correct |
| natural base, no `LRO` | RTL | `4, 3, 3, 2, 1, 0` — correct |

The same probe explains why `Google` stayed readable inside an Arabic line: under `LRO`
every run is left-to-right, which happens to be right for the Latin one. That also rules
out "reverse the string to compensate" as a fix — it would break exactly that case.

The second one, `baseWritingDirection`, is a real but separate defect: it does not reverse
anything, it decides where a *mixed* line's runs sit. With a hardcoded left-to-right base,
`المتابعة عبر Google` put `Google` against the right edge; the Web renderer puts it on the
left, which is what an Arabic reader expects.

### The change

- Drop the `NSWritingDirectionAttributeName` override from text spans and from the
  attachment placeholder span. U+FFFC is bidi-neutral and should take its neighbours'
  direction like any inline object.
- `style.baseWritingDirection = NSWritingDirectionNatural` — the TextKit default: the
  paragraph takes the direction of its first strong character.

Alignment is not affected: §2 already resolves `TextAlign.Start` / `End` in Kotlin and
hands the native view an absolute `left` / `right`.

### Verification (§3)

Device: simulator iPhone 16 `0E856C63-3808-4174-B808-4999C982DFAB`, iOS 18.6, Debug,
`useLocalKuikly=true`, mock server on :8099. Screenshots in the client's
`build/rtl-evidence/ios/`, `ar-launch-*.png` / `en-*.png` before, `after-*.png` after.

- **Arabic at launch** — Login, Home, Me and Settings read correctly:
  «رونق», «الإعدادات», «اللغة», «سياسة الخصوصية», «من يمكنه مراسلتي», «مركز VIP».
- **Mixed line** — «المتابعة عبر Google» is correct end to end, with `Google` on the left
  as the Web capture `build/rtl-evidence/web/ar-launch-login.png` draws it. Before the
  change the Arabic was reversed around a correctly-ordered `Google`.
- **English regression** — Login, Home and Me are **pixel-identical** to the pre-change
  build (0 pixels differing above threshold, status bar excluded). English Settings
  differs in exactly one 104x39 region: the «العربية» row of the language list, which is
  the fix.
- **CoreText unit check** — 12 Latin/Turkish strings (including `Türkçe`,
  `Sign in (recommended)`, `1,234 coins`, `Room #12 — Lv.7`) produce byte-identical glyph
  IDs and positions before and after. Only the string containing Arabic differs.
- `RonaqAppUITests/RonaqFlowTests` — `testEnglishBaselineScreens`,
  `testArabicLaunchScreens`, `testArabicByInAppLanguageSwitch`,
  `testGuestReachesHomeAndTheTabsRespond`: all pass.

### Known limitation

Base direction is inferred per paragraph from the first strong character rather than taken
from `LocalLayoutDirection`. A string that *starts* with Latin in an Arabic build therefore
gets a left-to-right base. Carrying the real direction across the bridge means a new text
prop in the Kotlin core plus all four renderers, and would need Android and Web evidence
this change does not have. Not attempted here.

`core-render-android`'s `KRRichTextView.createStaticLayoutBuilder` hardcodes
`setTextDirection(TextDirectionHeuristics.LTR)`, which is the same class of defect as the
`baseWritingDirection` half — Android has no equivalent of the `LRO` override, which is why
Arabic glyph order is right there. Left alone: it cannot be verified without an Arabic
emulator run, and per the client's `CLAUDE.md` an unverified RTL change does not ship.

### Upstream

Worth offering, and narrowly scoped. Neither line has a test or a stated reason behind it —
both arrive with the comment «强制使用LTR文本方向» in the initial open-source drop
(`b35568f4`), and `62432216` only renamed the deprecated constant. Removing the `LRO`
override is not a behaviour trade-off: it makes every right-to-left script render at all,
and provably changes nothing for Latin.

## 4. The Compose DSL stops capping font weight at 700

**Files** · `compose/.../foundation/text/KuiklyTextExtension.kt`,
`compose/.../foundation/text/BasicTextField.kt`
**Driven by** · `prototype/` — the presentation baseline (parent `CLAUDE.md` §4). The
design declares 800 or 900 on the labels that carry a screen; `:shared` already asks for
a weight above 700 at 260 call sites (136 of them `FontWeight.Black` or `ExtraBold` by
name), and every one of them rendered 700.
**Date** · 2026-08-09

### What goes wrong here

Every label the design draws at 800 or 900 rendered at 700, on every screen at once. The
cap is one `when` in the Compose text path:

```kotlin
// KuiklyTextExtension.applyFontWeight — before
FontWeight.W100 -> "300"
…
FontWeight.W700, FontWeight.W800, FontWeight.W900 -> "700"
```

`FontWeight.Black` and `FontWeight.Bold` were therefore indistinguishable, and `W100`
came out two rungs heavier than asked. A second, independent table in
`TextAreaAttr.setTextStyle` did the same to text fields, and folded 600 onto bold as
well:

```kotlin
if (it.weight <= 400) fontWeightNormal()
else if (it.weight == 500) fontWeightMedium()
else if (it.weight == 600) fontWeightBold()   // 600 → "700"
else if (it.weight >= 700) fontWeightBold()   // 800, 900 → "700"
```

**Nothing below this layer required either.** The cap is a mapping, not a renderer
limit, and the evidence is in the renderers themselves:

| layer | what it does with the `fontWeight` prop |
| --- | --- |
| `core` `TextAttr` | already offers `fontWeightExtraBold()` = `"800"`, `fontWeightBlack()` = `"900"` |
| iOS `KRConvertUtil` | `gFontWeightMap` maps `"800"` → `UIFontWeightHeavy`, `"900"` → `UIFontWeightBlack` |
| Android `KRRichTextBuilder.FontWeightSpan` | keys on `"800"` / `"900"` for its extra-bold and black stroke widths |
| Web `KuiklyRenderCSSKTX` / `KRRichTextView` | assigns the string straight to CSS `font-weight` |

Every one of them has understood 100..900 all along; only the DSL narrowed it.

### The change

Both tables now go through one function, `FontWeight?.toKuiklyFontWeight()`, which snaps
the weight to the nearest hundred in 100..900 and emits it. Snapping is deliberate: the
iOS and Android tables key on those exact strings and fall back to *regular* for anything
else, so forwarding an arbitrary `FontWeight(650)` verbatim would render it lighter than
the 600 beneath it. `TextAreaAttr` has only three weight setters, so the text-field path
writes the same `TextConst.FONT_WEIGHT` prop those setters write.

### Verification (§4)

Web build on 127.0.0.1:8231 against the design on 127.0.0.1:8111, both at 430x932 @2x
under touch emulation, driven through `scripts/web-harness.mjs`.

Login screen, computed `font-weight` per string, design vs implementation:

| string | design | impl before | impl after |
| --- | --- | --- | --- |
| `Ronaq` | 900 | 700 | **900** |
| `Continue with Google` | 800 | 700 | **800** |
| `Continue with Phone` | 800 | 700 | **800** |
| `Voice rooms, friends & gifts…` | 400 | 400 | 400 |
| `Explore as guest` | 400 | 400 | 400 |

Home screen census went from `{400, 700}` to `{400, 700, 800, 900}` — 900 on `Create
Room`, `Golden Gala Week`, `Claim +50`, `LIVE`; 800 on `Daily check-in` and the day
chips; 700 retained on the room-card tags. `./gradlew :shared:compileKotlinJs` passes.

### Known limitation — the web build renders no weight above 600 *yet*, for a second reason

The mapping was half the gap. The other half is not in the fork and cannot be fixed here:
**the web build loads no font.** `document.fonts.size === 0`, no `@font-face` is declared
in `h5App/src/jsMain/resources/index.html`, and nothing sets a `FontFamily`, so text
resolves to the platform default — PingFang SC on the macOS baseline — which has no face
above Semibold. Measured in that browser, `Continue with Google` at 15px:

| declared weight | 400 | 600 | 700 | 800 | 900 |
| --- | --- | --- | --- | --- | --- |
| PingFang SC (default) | 150.72px | 154.75px | 154.75px | 154.75px | 154.75px |
| Helvetica | 143.44px | — | 154.98px | 154.98px | 154.98px |

One synthesised bold serves 600 and above; 700, 800 and 900 are metrically identical. The
design bundles Cairo at 400/600/700/800/900 as `@font-face` and gets five distinct faces.

So after this change the web DOM matches the design's weights exactly and **iOS and
Android render differently immediately** (their system fonts do have Heavy and Black, and
Android's span picks a heavier stroke per rung), while web will not look different until
a family with those faces is bundled and selected. That is app-side work — a webfont in
`h5App`'s `index.html` plus a `FontFamily` in the shared theme — and is reported rather
than done here, because the fork must not carry product assets.

### Upstream

Worth offering, and it is a plain bug rather than a trade-off: the mapping contradicted
the layer directly beneath it, which has always accepted 100..900, and no test or comment
defended the collapse. The snapping rule is the only judgement call, and it can be dropped
if upstream would rather widen the iOS and Android tables to interpolate.

## 5. `Modifier.border` can draw the dashed and dotted styles core already models

**Files** · `compose/.../foundation/Border.kt`
**Driven by** · `prototype/` — the presentation baseline (parent `CLAUDE.md` §4): an
unoccupied mic seat is `1.5px dashed rgba(255,255,255,.15)`, the "Become guardian" chip
`1px dashed rgba(255,255,255,.3)`
**Date** · 2026-08-09

### What goes wrong here

`BorderModifierNode.draw` hardcoded the line style:

```kotlin
view.getViewAttr().border(Border(width.value, BorderStyle.SOLID, color.toKuiklyColor()))
```

`BorderStyle` has had `SOLID` / `DOTTED` / `DASHED` since the initial open-source drop,
`Attr.border` serialises whichever it is given, and the web renderer assigns it straight
to CSS `border-style`. Only the Compose DSL threw the choice away, so a dashed outline —
which the design uses for every "this slot is empty, act on it" affordance — could not be
expressed at all, and the client's `unimplemented.md` recorded it twice as *"this
renderer has no dash"*. It has one; the DSL did not reach it.

### The change

An optional `style: BorderStyle = BorderStyle.SOLID` on the three `Modifier.border`
overloads, threaded through `BorderModifierNodeElement` to the node and into `Border`.
Every existing call site is source-compatible and keeps solid borders by construction.

The parameter type is core's own `BorderStyle` rather than a new Compose enum: it is
already in `Modifier.border`'s file (it was the hardcoded constant), it is what `Attr`
takes, and inventing a parallel enum would mean a mapping table for three values.

### Verification (§5)

Verified in the browser with a real caller: `RoomScreen`'s mic grid passes
`style = if (seat.occupant == null) BorderStyle.DASHED else BorderStyle.SOLID`, and the
two empty seats in the Saudi Majlis room report
`border: 1px dashed rgba(255, 255, 255, 0.15)` at 58x58 with a 50% radius. The design's
own DOM for the same seats reports `1px dashed rgba(255, 255, 255, 0.15)` at 56x56.
Occupied seats in the same grid still report `solid`, as does every other bordered
element on the screen (chips, banner, avatars, LIVE pill) — the default did not move.

### Upstream

Worth offering. It exposes a capability the kit already ships end to end and adds no
concept: one defaulted parameter, no behaviour change for any existing call site, and the
renderers need no work. `androidx` has no dashed-border modifier, so a maintainer may want
the parameter named or placed differently, but the gap it closes is real for any design
system that uses a dashed outline for an empty slot.

## 6. A sweep (conic) gradient brush, and the web renderer that draws it

**Files** · `compose/.../ui/graphics/Brush.kt`,
`compose/.../ui/text/style/TextForegroundStyle.kt`,
`core-render-web/base/.../ktx/KuiklyRenderCSSKTX.kt`
**Driven by** · `prototype/` — the presentation baseline (parent `CLAUDE.md` §4): the
lucky wheel disc is
`conic-gradient(#8E1B4B 0deg 45deg, #3B2B8E 45deg 90deg, … #B4233A 315deg 360deg)`
**Date** · 2026-08-09

### What goes wrong here

The kit had `Brush.linearGradient` and its horizontal / vertical shorthands, and nothing
else. Every ring in the design — the eight-wedge lucky wheel, the rotating avatar frames,
the halo behind a live badge — had to be approximated by a horizontal ramp, and the
client's `RoomScreen` says so in a comment at each site.

For the wheel the approximation loses the part that carries the meaning. The wheel exists
to be pointed at: the pointer sits at twelve o'clock and a spin settles on a wedge. A
smooth left-to-right ramp has no wedges, so the pointer indicates nothing, and no amount
of colour tuning recovers it. This is the one case where "closest available brush" is not
a near miss but a different object.

### The change

`Brush.sweepGradient(…)`, matching `androidx.compose.ui.graphics.Brush.sweepGradient`
(offset 0 at three o'clock, clockwise, optional pixel `center`) with one addition: a
`startAngle` in degrees. A wheel or ring nearly always wants its first boundary at twelve
o'clock, and without the parameter that has to be folded into every stop by hand, which
is exactly the kind of arithmetic that silently rotates a wheel by one wedge.

A hard boundary is a repeated colour at two adjacent offsets, as in CSS. The design's
disc is:

```kotlin
Brush.sweepGradient(
    0.000f to wedge0, 0.125f to wedge0,
    0.125f to wedge1, 0.250f to wedge1,
    …
    0.875f to wedge7, 1.000f to wedge7,
    startAngle = -90f,
)
```

**Transport.** The core layer models a background gradient as one string under the
`backgroundImage` prop, `linear-gradient(<directionOrdinal>,<color> <stop>,…)`, parsed
positionally by each renderer. A sweep goes in the same prop as
`sweep-gradient(<startAngleDeg> <centreXFraction> <centreYFraction>,<color> <stop>,…)`,
and `getCSSBackgroundImage` turns it into `conic-gradient(from <angle-90>deg at x% y%, …)`.
The fixed -90 is the difference between the two conventions: CSS starts a conic gradient
at twelve o'clock, `Brush.sweepGradient` at three.

**Why the brush asks which renderer it is talking to.** `SweepGradient.applyTo(view)`
emits the sweep form only when `pageData.isWeb`, and otherwise falls back to
`backgroundLinearGradient` with the same stops — Android, iOS, OHOS and the mini-program
runtime all take the fallback. This is not a preference between platforms; two of those
renderers cannot survive the string at all:

- Android `KRCSSBackgroundDrawable.parseBackgroundImage` slices at a fixed
  `"linear-gradient(".length` and then calls `.toInt()` on what it finds — a
  differently-prefixed value is a `NumberFormatException`, not a fallback.
- iOS `UIView+CSS.p_tryToParseWithLinearGradient` requires the same prefix and returns
  `NO` without it, leaving the view with no background at all.

So the fallback is the approximation callers write by hand today, and nothing regresses
where the capability is not yet implemented. Giving Android, iOS and OHOS a real sweep is
a change inside each renderer (Android has `android.graphics.SweepGradient`; iOS needs a
drawn `CGGradient` since `CAGradientLayer` has no conic type) and is **outstanding** —
listed here so the next person does not have to rediscover why web is ahead.

> **Corrected by §7 (2026-08-10).** Android and iOS now parse and draw the sweep form.
> Two statements above did not survive contact with the platforms and are left in place
> only because this file records what was believed at the time: iOS **does** have a conic
> `CAGradientLayer` (`kCAGradientLayerConic`, iOS 12 / macOS 10.14), so no drawn
> `CGGradient` was needed; and Android's `GradientDrawable` *can* build a sweep shader but
> never gives it stop positions, so the Android half is not a straight port of the linear
> parser. The `pageData.isWeb` gate in `SweepGradient.applyTo` is **still in place** and
> still has to be lifted before either renderer sees a sweep — see §7 «What is still
> missing».

`SweepGradient` deliberately has **no companion object**; its two constants are private
top-level ones. With them in a companion, `:shared`'s `kspKotlinJs` died with
`KotlinIllegalArgumentException … from RAW_FIR to TYPES, current declaration phase
COMPANION_GENERATION` the moment a shared file referenced `Brush.sweepGradient` — KSP's
Analysis API resolves this module from source under `useLocalKuikly=true`, and it cannot
take that companion. Moving the constants out fixed it with no other change. Do not put
one back without re-running `./gradlew :shared:compileKotlinJs`.
（`SweepGradient` 刻意不带 companion object：带上后，共享层一旦引用
`Brush.sweepGradient`，`:shared:kspKotlinJs` 即在 COMPANION_GENERATION 阶段崩溃。）

`TextForegroundStyle.from` resolves a sweep brush to `Unspecified` rather than a
`BrushStyle`: the text path converts a background-image string into a foreground span and
only parses the linear form, so a sweep would arrive there unpaintable. Text keeps its
declared colour instead.

### Verification (§6)

Web build on :8231 against the design on :8111, driven through `scripts/web-harness.mjs`.

Verified with the real caller: `RoomScreen`'s lucky wheel now builds its disc with
`Brush.sweepGradient(colorStops = wheelWedgeStops(), startAngle = WHEEL_TOP_DEG)`, and the
rendered disc is eight discrete wedges with a visible boundary per prize and the first
boundary at twelve o'clock under the pointer — the same reading as the design's own
`conic-gradient(#8E1B4B 0deg 45deg, …)`. The element reported
`linear-gradient(to right, …)` before and `conic-gradient(from …deg at 50% 50%, …)` after.

An earlier instrumented build, in which every `Brush.horizontalGradient(List)` was routed
through the sweep, confirmed the same conversion on unrelated elements and that the
angle convention holds (`startAngle = -90f` arrives as CSS `from -180deg`, since the
renderer subtracts a further 90 for the twelve-o'clock CSS origin).

Regression: with the instrumentation removed, every gradient on the room screen reports
`linear-gradient` again, byte-identical to the pre-change strings.

Frames: `build/wh-fork-evidence/` — `impl-wheel-before-sweep.png`,
`impl-wheel-after-sweep.png`, `design-wheel.png`.

### Upstream

`Brush.sweepGradient` restores an androidx API this port dropped rather than adding a
Kuikly concept, and the renderer half is 30 lines beside the linear parser. Worth
offering. A maintainer may reasonably want the three remaining renderers in the same
change, and may prefer the fallback to live in each renderer rather than in the brush —
both are improvements on this shape, and neither was reachable from a web-only slice.

## 7. Android and iOS draw the sweep gradient the web renderer already draws

**Files** · `core-render-android/.../css/drawable/KRCSSBackgroundDrawable.kt`,
`core-render-ios/Extension/Category/UIView+CSS.m`
**Driven by** · `prototype/` — the presentation baseline (parent `CLAUDE.md` §4): the
lucky wheel disc is
`conic-gradient(#8E1B4B 0deg 45deg, #3B2B8E 45deg 90deg, … #B4233A 315deg 360deg)`, and
the wheel float is `conic-gradient(#FF5C8A,#F5C15C,#4EE1C1,#7B5CFF,#FF5C8A)`
**Date** · 2026-08-10

### What goes wrong here

§6 added `Brush.sweepGradient` and taught the web renderer to draw it, and recorded why
the other three renderers could not take the string at all. Both reasons were real:

- Android `KRCSSBackgroundDrawable.parseBackgroundImage` sliced at a fixed
  `"linear-gradient(".length` and then called `.toInt()` on whatever that produced —
  a `NumberFormatException` on any other prefix, not a fallback.
- iOS `CSSGradientLayer.p_tryToParseWithLinearGradient` required the same prefix and
  returned `NO` without it; `initWithLayer:cssGradient:` ignored the result, so the layer
  ended up with no colors and drew **nothing**.

So the wheel is eight discrete wedges on web and a smooth left-to-right ramp on the two
platforms that ship. The wedge boundaries are the part that carries the meaning — the
pointer sits at twelve o'clock and has to indicate one prize — so this is not a colour
nuance, it is a different object.
Web 已能绘制扫描渐变，Android／iOS 仍只能得到横向渐变近似；分格边界正是指针语义的
前提，故此为实质缺口而非色彩细节。

### The change

Both renderers now recognise
`sweep-gradient(<startAngleDeg> <cxFrac> <cyFrac>,<argb> <stop>,…)` in the same
`backgroundImage` prop the linear form travels in, and draw it with the platform's own
conic primitive. Neither renderer gained a Ronaq concept; both gained a second `if` at
the point where they already branch on the string's prefix.

**Android** — `parseSweepGradient` resolves the string, `applySweepGradient` sets
`gradientType = SWEEP_GRADIENT` and the gradient centre. Two things are not obvious:

- **`GradientDrawable` never hands a sweep its stop positions.** Its `SWEEP_GRADIENT`
  branch builds `SweepGradient(x0, y0, tempColors, tempPositions)` where `tempPositions`
  is initialised to `null` and only filled when `mUseLevel` is set — `st.mPositions` is
  read by the linear and nothing else. Checked in the platform sources shipped with the
  SDK for API 30, 35 and 36; all three are identical. `setColors(colors, offsets)` is
  therefore inert for a sweep on every API level, and the offsets have to be resolved
  before the drawable sees them. The stops are sampled into 1024 evenly spaced colors,
  which is exactly what a `null` position array means to the shader. A hard boundary
  survives as a blend one sample wide — `360 / 1023 = 0.35°`, which on the design's 228dp
  disc at 3x (684px across, 2149px around) is 2.1px.
  GradientDrawable 的扫描分支恒以 null 位置数组构建着色器（API 30/35/36 平台源码一致），
  故 setColors(colors, offsets) 对扫描渐变不生效；色标须在交给 drawable 前解析为
  1024 个等距颜色，硬分界退化为 0.35° 的过渡（设计转盘上约 2.1 像素）。
- **The drawable is reused across views**, so `updateBackgroundImage` now restores
  `gradientType = LINEAR_GRADIENT` on the linear and the empty paths. Without that, the
  next linear gradient to land on a recycled view would be drawn as a sweep.

`parseBackgroundImage` keeps its signature and its linear behaviour, and stops throwing:
a sweep handed to a caller that can only draw a line — a text foreground span, an image
mask — now yields the same stops laid along `LEFT_RIGHT`, which is the approximation
those callers would have had to write by hand. An unrecognised prefix yields a
transparent gradient rather than an exception.

**iOS** — `p_tryToParseWithSweepGradient` runs when the linear parse declines, and
`p_applySweepGeometry` sets `type = kCAGradientLayerConic`. The locations pass through
untouched, so the wedges are true hard stops rather than a sampled ramp.
`kCAGradientLayerConic` has existed since **iOS 12 / macOS 10.14** — §6's claim that
`CAGradientLayer` has no conic type was wrong, and the drawn `CGGradient` it called for
is not needed. The pod's iOS deployment target is exactly 12.0, but its macOS target is
10.13, so the assignment sits behind `@available` and falls back to the linear path
(`TO_RIGHT`, the same approximation as before) when the type is unavailable.

The whole turn is placed by rotating one ray, which is what the SDK header says the
property means: «the gradient is centered at `startPoint` and its 0-degrees direction is
defined by a vector spanned between `startPoint` and `endPoint`». `endPoint` is therefore
`startPoint + 0.5·(sin θ, −cos θ)`, half a unit away in every case — the header also says
overlapping points are undefined — and θ = 0 gives straight up, the canonical
`(0.5, 0.5) → (0.5, 0)` conic recipe, which is the configuration the app's own wheel uses.
整圈方位由一条射线的旋转决定（SDK 头文件即如此定义 endPoint）；θ=0 即正上方，
亦即本应用转盘实际使用的配置。

### The angle convention, which is not what `Brush.sweepGradient` documents

`Brush.sweepGradient`'s KDoc says offset `0` sits at three o'clock and `startAngle`
rotates from there, «as in `androidx.compose.ui.graphics.Brush.sweepGradient`», and gives
`startAngle = -90f` as the way to put the first boundary at twelve o'clock. **The wire
does not mean that.** What is actually implemented, end to end, is

> offset `0` is drawn `startAngleDeg − 90` degrees clockwise from **twelve** o'clock

— a half turn away from the KDoc. Three independent facts pin it, and they agree with
each other:

| where | what it says |
| --- | --- |
| `getCSSBackgroundImage` (web) | emits CSS `conic-gradient(from startAngle − 90 …)`, and CSS measures `from` clockwise from twelve o'clock |
| `RoomScreen.WHEEL_TOP_DEG` | `90f`, commented «so the sweep's offset 0 sits at twelve o'clock» |
| `prototype/` | `#8E1B4B 0deg 45deg` — the first wedge starts at twelve o'clock |

Under the KDoc's reading, `startAngle = 90f` would put offset `0` at six o'clock and the
shipped web wheel would be four wedges out of register with the design. It is not. So the
KDoc is the odd one out, and **these two renderers implement the wire, not the KDoc** —
that is what keeps all three platforms showing the same wheel.
KDoc 与线上实际语义相差 180°；Web 渲染层、调用方与设计三者互相印证，故本次以线上语义
为准，三端因此一致。

Fixing the mismatch is a one-line change in *either* direction — `getCSSConicGradient`'s
`- 90f` becomes `+ 90f`, or the KDoc is rewritten — but it cannot be done in one place:
whichever is chosen, `WHEEL_TOP_DEG` moves with it, and that is `:shared`. It is left
alone here deliberately rather than half-corrected. **Do not "fix" the `- 90f` on its own.**

### What is still missing

- **`SweepGradient.applyTo` still gates the sweep form on `pageData.isWeb`**, so neither
  renderer receives the string yet and this change is inert until that goes. The gate was
  correct when it was written — it existed precisely because these two renderers could not
  survive the string — and its removal is a one-line change in `compose/.../Brush.kt`:
  emit `BACKGROUND_IMAGE` unconditionally and keep the `backgroundLinearGradient` fallback
  for OHOS and the mini-program runtime only. That file was outside this change's scope.
  该网关仍在，故本次改动在其解除前不生效。
- **OHOS has no sweep.** `core-render-ohos` was not touched and still needs the fallback.
- **`Paint.toKuiklyLinearGradient`** still resolves a sweep shader to `null`, so a Canvas
  draw paints the flat paint colour. Unchanged from §6.
- **Android below API 29 is unaffected by this change either way** — the sampled ramp does
  not use `setColors(colors, offsets)`, so the sweep behaves identically from API 21 up.
  The *linear* path's pre-existing API-29 floor is untouched.

### Verification (§7)

**Device verification is outstanding, and this is a skip rather than a pass.** No emulator
or simulator was reachable from this change; per the client's `CLAUDE.md` an unverified
claim is a skip. What was established:

- `:core-render-android:compileDebugKotlin` — BUILD SUCCESSFUL.
- `clang -fsyntax-only -fobjc-arc -Wunguarded-availability` on `UIView+CSS.m` against the
  iOS simulator SDK at `-target arm64-apple-ios12.0-simulator`: clean. Against the macOS
  SDK at `-target arm64-apple-macos10.13`: the three pre-existing `NSBezierPath.CGPath`
  warnings and nothing from the new code, i.e. the `@available` guard is sufficient at the
  pod's real macOS floor.
- **The arithmetic was executed, not reasoned about.** A model of `parseSweepGradient` /
  `sampleTurn` / `rampColorAt`, transcribed statement for statement, was run on the exact
  wire string the brush emits for the wheel disc. Reading the resulting ramp the way
  `android.graphics.SweepGradient` reads it — sample `i` at turn `i/1023` clockwise from
  three o'clock — reproduces the design's own wedge at all 16 compass points sampled
  (1°, 44°, 46° … 359°), exact hex, no mismatch.
- The same model for the wheel float's smooth five-colour ramp, evaluated every 5° around
  the whole turn, is within **1/255** of the design's `conic-gradient` — the 1024-sample
  quantisation and nothing else. This is the case that would have exposed a wrap-around
  error: a quarter of that ring crosses offset 1, and a naive rotation that clamped the
  ends would have flattened it.
- Rendered side by side in a browser — the design's own CSS, the Android ramp positioned
  by Android's rule, and the iOS locations positioned by the conic layer's rule — the three
  discs are the same picture: 26612 pixels compared inside each disc, **0.68%** differing
  by more than 8/255 for Android (mean error 0.45/255) and **0.24%** for iOS (mean 0.23),
  every one of them on a wedge boundary. Android's larger residual is the sampled ramp's
  0.35° blend, exactly as predicted. Frames in the client's `build/wi-fork/`.

What that does **not** establish: that `GradientDrawable` with `SWEEP_GRADIENT` and a
1024-entry ramp renders as the model says on a real device (Skia may resample a long stop
list into a smaller lookup table on the GPU path, which would widen the boundary blend);
that `kCAGradientLayerConic` accepts duplicate `locations` for hard stops; that iOS unit
coordinates run top-left for a conic layer as they demonstrably do for the axial one; or
that either platform's rounded-corner clipping composites the new shader correctly. Each
needs a device.
以上未证实：真机上的实际绘制、CAGradientLayer 对重复 locations 的处理、锥形图层的单位
坐标方向，以及圆角裁剪与新着色器的合成 —— 均须真机验证。

### macOS

`kCAGradientLayerConic`'s angle «increases in the direction of rotation of positive x-axis
towards positive y-axis». On iOS that reads clockwise, because the unit coordinate space
runs y-down in practice — which is also how the existing linear helper
`hr_setStartPointAndEndPointWithLayer` reads it («to bottom» runs `y = 0 → 1`). If a macOS
host presents the layer y-up, the sweep runs counter-clockwise there. Not compensated:
macOS is not a target of this product, and an untested `#if TARGET_OS_OSX` branch would be
worse than a recorded caveat.

### Upstream

Worth offering, and both halves are ordinary renderer work rather than a Ronaq concept:
they complete a capability the kit's own Compose DSL already exposes. The Android half
carries one finding a maintainer will want independently — `GradientDrawable` silently
drops sweep positions, which makes `setColors(colors, offsets)` a trap for anyone
implementing this the obvious way. The natural shape for upstream is this change plus the
`pageData.isWeb` removal plus OHOS in one series, which is what a maintainer asked for in
§6 and what a single-renderer slice could not deliver.

## 8. The Android text renderer draws a real face per weight instead of faking every one

**Files** · `core-render-android/.../expand/component/text/TypefaceUtil.kt`,
`core-render-android/.../expand/component/text/KRRichTextBuilder.kt`,
`core-render-android/.../expand/component/KRRichTextView.kt`,
`core-render-android/.../expand/component/KRCanvasView.kt`
**Driven by** · `prototype/` — the presentation baseline (parent `CLAUDE.md` §4). The
design is set in Cairo at 400/600/700/800/900 and gets five distinct faces; §4 above made
the DSL stop capping the request at 700, and `ARCHITECTURE.md` §2.1 then had the app embed
those same five files. Android could reach exactly one of them.
**Date** · 2026-08-10

### What goes wrong here

Android never draws a heavy face. It draws the regular one and widens the paint stroke:

```kotlin
// KRRichTextBuilder.FontWeightSpan — 900 strokes at 2.5/50 of the text size
tp.style = Paint.Style.FILL_AND_STROKE
tp.strokeWidth = strokeWidth * tp.textSize
```

That was a reasonable thing to do while the only face available was the system one. It
stopped being reasonable when the product started shipping five real Cairo files: four of
them (~660 KB of the APK) were unreachable, because nothing in the renderer can name a
weight to the host.

The chain is short and every link drops the weight:

| layer | what it knows |
| --- | --- |
| `KRTextProps` | has both `fontWeight` ("900") and `fontFamily` ("Cairo,sans-serif") |
| `FontFamilySpan` / `buildSimpleText` / `KRCanvasView` | pass **only the family** to the loader |
| `TypeFaceLoader` | caches one typeface per `(name, italic)` — no weight in the key |
| `IKRFontAdapter.getTypeface` | takes a family name; there is no weight parameter |

So the host is asked "give me Cairo" and answers with Cairo-Regular, whatever weight the
label declared. The synthesised stroke then does the rest, at every weight, forever.
链上每一环都把字重丢掉：属性层两者都有，span 层只传族名，加载器按 (name, italic) 缓存，
适配器接口根本没有字重参数。宿主只被问到「给我 Cairo」，于是永远得到 Regular。

### Which of the two proposed shapes to take — and why neither, quite

The gap was recorded with two candidate fixes: a weight argument on `IKRFontAdapter`, or
`KuiklyTextExtension.applyFontFamily` folding the resolved weight into the family string.
Taken literally, both are worse than they look.

**Folding the weight into the family string in the Compose DSL is the wrong layer.**
`fontFamily` is not an Android string; it is a wire prop that four renderers read
verbatim, and two of them already resolve weight correctly *because* the string is clean:

- web — `RichTextProcessor` assigns it straight to CSS `font-family`. The five
  `@font-face` rules in `h5App`'s `index.html` are keyed on `font-weight`, so web already
  picks a real face per weight; a weight-qualified family would at best be inert and at
  worst invalidate the declaration.
- iOS — `KRConvertUtil` hands family **and weight** to
  `hr_fontWithFontFamily:fontSize:fontWeight:`. iOS already has the parameter this change
  is about, and the shared theme's `Cairo,sans-serif` chain exists precisely to reach that
  handler (see `ComposeTheme.RonaqFontFamily`'s note). Mangling the family would defeat it.

It would also fix only text written through the Compose DSL, leaving the core Kuikly DSL,
the canvas and the text field where they are. And it does not avoid an interface change —
it makes one out of strings, since every Android host adapter would have to learn the
encoding without a signature to tell it so. «不改接口» 只是把接口改成了未文档化的字符串约定。

**A weight argument on `IKRFontAdapter` is the right idea in the wrong place.** It is what
iOS did, and upstream has extended this interface exactly once before in exactly that
shape (`getTypeface(fontFamily, contextParams, result)`, defaulted to the older overload).
But a Kotlin interface method with a body compiles to `DefaultImpls` here — this module
sets no `-Xjvm-default` — so it is a compile break for any **Java** adapter, and it makes
every host implement something before it sees any benefit.

**What this change does instead**: it folds the weight into the family name — the second
idea — but inside `TypeFaceLoader`, where the encoding never leaves Android, rather than
in the shared DSL where it would corrupt a prop three other renderers depend on. The
loader asks the host for `Cairo-Bold` before it asks for `Cairo`, using the host's own
naming, and no interface changes at all.

The naming is not a Kuikly invention: `Family-Weight` is the PostScript face name that
`[UIFont fontWithName:]` takes on iOS and that a static `@font-face` file carries on web,
so a host that ships one file per weight has already named them this way. Ronaq's adapter
had those names in its map before this change, with a comment saying so — it needed no
edit to be served. And asking the adapter for a name it may not own is already a supported
query: that is exactly how `Cairo,sans-serif` falls through to the platform today.

### The change

`TypeFaceLoader.resolve(family, italic, weight)` returns a `ResolvedTypeface` — the face,
plus whether it really carries the requested weight. The three sites that fake weight
(`FontWeightSpan` via `KRRichTextBuilder`, `KRRichTextView.buildSimpleText`,
`KRCanvasView.flushTextCommand`) now stroke only when it does not.

The resolution walks the family chain exactly as before, and per name:

1. ask the host for `"$name-$faceName"` (`Bold` for 700, `Black` for 900, `BoldItalic`,
   …; null for an unstated or non-hundred weight, which skips the step entirely);
2. accept it **only if it is a different face from what the same host returns for the bare
   name**;
3. otherwise fall through to the pre-existing bare-name and platform-family lookups.

Step 2 is the guard that keeps this safe for adapters nobody has seen. An adapter that
answers every name with one typeface has not selected by weight, and its text still needs
the stroke; without the check such a host would silently lose every bold on screen. It
also, usefully, makes weight 400 self-correcting: Ronaq's adapter maps both `Cairo` and
`Cairo-Regular` onto one cached instance, so the probe is rejected and nothing changes —
which is right, since 400 strokes at zero anyway.
第 2 步是给「素未谋面的适配器」留的保险：对任何名字都返回同一 typeface 的宿主并未按字重
挑选，其文本仍须描边加粗；缺此判断，这类宿主界面上的粗体会无声变细。

Deliberately **not** done: `Typeface.create(family, weight, italic)` (API 28+), which
would let the *platform* pick a weighted face out of a system family. It is tempting and
it is a bigger change than it looks — it would move the metrics of every existing app's
text on the system font, on one API level and up, with no host opt-in. Only a face the
host names is preferred here, so the invariant is exact:

> **Nothing changes for a host whose adapter returns null for names it does not own —
> which is the contract the loader has always required.**

Two incidental notes, both inside the rewritten function:

- The adapter result is now read from a local. It used to be read from a variable reused
  across loop iterations, so an adapter that never invoked the callback — or a null
  adapter — could leak the previous name's answer into the next name's test. Reachable
  only in the italic + no-adapter corner, but it is not worth preserving.
- The cache bound moved 10 → 32, because the key now carries a weight: one family at five
  weights is five entries, and two families would have thrashed the old bound. A
  `Typeface` is a thin handle onto a shared, usually mmapped native font.

`IKRFontAdapter` is **unchanged**. So is every file under `compose/`.

### Verification (§8)

**Device verification is outstanding, and this is a skip, not a pass.** A parallel task
owns the emulator and the simulator this wave; per the client's `CLAUDE.md` an unverified
claim is a skip. What was established:

- `:core-render-android:compileDebugKotlin` — BUILD SUCCESSFUL, no new warnings.
- `:shared:compileKotlinJs` — BUILD SUCCESSFUL from `--rerun-tasks`. `:shared:jsTest` —
  BUILD SUCCESSFUL, 31 tests, 0 failures (`GiftRules` 12, `Search` 8, `PhoneCountry` 7,
  `Localization` 4). Neither could have been affected: `core-render-android` appears in
  the JS build only as a *configured* project and compiles no JS task (185 tasks in the
  log, one mention, and it is `Configure project`).
- **The resolution was executed, not reasoned about.** `createTypeface`,
  `postScriptFaceName`, `hostTypeface`, `parseWeight`, `getFontWeight` and the three call
  sites were transcribed statement for statement and run against models of three real
  adapters — Ronaq's (its exact asset map and its file-keyed instance cache), KuiklyUI's
  own demo adapter, and a deliberately lenient one. Typeface identity is modelled as
  object identity, which is what `Typeface` gives Kotlin's `===` and `!=` since it does
  not override `equals`. 33 checks, 0 failures. The material ones:

  | case | before | after |
  | --- | --- | --- |
  | Ronaq host, `Cairo,sans-serif` at 600 | Cairo-Regular + 0.30px stroke | **Cairo-SemiBold, no stroke** |
  | …at 700 | Cairo-Regular + 0.45px | **Cairo-Bold, no stroke** |
  | …at 800 | Cairo-Regular + 0.60px | **Cairo-ExtraBold, no stroke** |
  | …at 900 | Cairo-Regular + 0.75px | **Cairo-Black, no stroke** |
  | …at 400 / 500 (no such file) | Cairo-Regular + 0 / 0.15px | unchanged |
  | demo adapter, any weight | DEFAULT + stroke | unchanged, all four weights |
  | no adapter at all | DEFAULT + stroke | unchanged |
  | lenient adapter (answers every name) | House-Regular + stroke | unchanged — the guard holds |
  | `Satisfy,Cairo` at 400 and 700 | one family | still one family, not Satisfy + Cairo-Bold |
  | 50 resolves of one weight | — | 2 adapter calls; 6 weights → 6 cache entries |

  (strokes at 15px, the design's body size.) Both text paths were run: the plain `Text`
  one — `TextConst.VALUE` → `buildSimpleText`, which is what almost every label in this
  app takes — and the `AnnotatedString` span one.

What that does **not** establish, and each of these needs a device:

- that `Typeface.createFromAsset` on the four heavy Cairo files yields faces whose glyphs
  and metrics are what the design draws — the model asserts which *file* is selected, not
  what Skia does with it;
- that removing `FILL_AND_STROKE` does not shift baselines or line boxes anywhere that
  was silently relying on the stroke's extra width, which is a layout change on every
  screen at once and the one thing most likely to surprise;
- that Arabic shaping is intact in the heavy faces (Cairo is an Arabic-first family, but
  four of its faces have never been rendered by this app);
- that the extra adapter call per new `(family, italic, weight)` triple is invisible
  against the room screen's frame budget — it is once per triple and then cached, but
  「once per triple」 is a claim about the cache, not a measurement.
以上均须真机验证：四个重字重字面的实际字形与度量、去掉描边后基线与行盒是否位移、
重字重下的阿拉伯文整形，以及新增适配器调用对帧预算的影响。

### Upstream

Worth offering, and it is narrow: no interface changes, no new concept, four files in one
renderer, and a stated invariant that a non-participating host is byte-identical. The
argument a maintainer will want to weigh is step 2, the different-face guard — it is the
price of inferring weight support instead of declaring it, and a maintainer who would
rather declare it can replace the guard with a defaulted `IKRFontAdapter` overload without
touching anything else in this change. The `Typeface.create(family, weight, italic)`
question is left open on purpose and belongs in its own change with its own metrics
evidence.

The parallel gap on iOS is already closed upstream (`hr_fontWithFontFamily:fontSize:
fontWeight:`), which is the strongest argument that Android's omission is an oversight
rather than a decision.


## 9. Test tags survive semantics merging

**Files** · `compose/src/commonMain/kotlin/com/tencent/kuikly/compose/extension/KuiklySemantisHandler.kt`
**Driven by** · `mobile/docs/design/test-tags.md`, and through it the parent `CLAUDE.md`
requirement that the client be drivable by automated tests on Android, iOS and Web. The
framework already routes `Modifier.testTag` to `viewIdResourceName`, `accessibilityIdentifier`
and `data-testid`; this makes it arrive for the nodes automation needs most.
**Date** · 2026-08-16

### What goes wrong here

`onSemanticsChange` read every property, test tags included, from the MERGED semantics
tree:

```kotlin
val allNodes = semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
```

Merging is right for accessibility — a screen reader should hear one control rather than
each of its parts — and wrong for a test tag, which names one exact node the author
marked. A node carrying only a `testTag` is folded into whichever ancestor merges its
descendants, and the tag is gone before it can be applied.

The nodes this loses are the ones automation needs most: containers. Measured on the room
screen with the gift sheet open, 79 tagged nodes exist in the unmerged tree and 70 in the
merged one. The nine lost were the sheet body, the chat list, the composer field and the
marquee — every one a container whose children arrived perfectly well. A suite could
therefore address a gift tile but not the sheet holding it, and a chat row but not the
feed, which is exactly backwards: the container is what tells a test which screen it is
looking at.

A second, smaller loss sits behind the same line. `node.layoutNode as? KNode<*>` silently
skips a semantics node whose layout node has no view — composables built on `Layout`
produce `KNode(VirtualNodeView(), isVirtual = true)`, and some produce a plain
`LayoutNode`.

### What this fork does instead

Read the two trees for their two purposes. Accessibility text, role and state description
keep the merged tree. Test tags get their own pass over the unmerged tree, and resolve to
the nearest rendering node rather than requiring the semantics node to be one — descending
rather than ascending, so a tag stays inside the subtree its author marked instead of
landing on a parent that may hold several marked children.

Cost is one extra traversal per semantics change.

### Evidence

Web, Kuikly H5 renderer, room 66120 with the gift sheet open, counting
`document.querySelectorAll('[data-testid]')`:

| tag | before | after |
| --- | --- | --- |
| `room.chatFeed` | absent | present |
| `room.chatInput` | absent | present |
| `room.marquee` | absent | present |
| `room.chatRow#<id>` | 0 rows | 5 rows |
| `gift.root` | absent | present |
| room total | 44 | 52 |

### Upstreamability

High, and worth doing. Nothing here is Ronaq-specific: any Kuikly Compose app that reads
`testTag` from an automated suite loses the same container tags, and the fix is a
behaviour correction rather than a new feature.

---

## iOS renderer: marked views must be reachable in the accessibility tree

Files: `core-render-ios/Extension/Category/UIView+CSSDebug.{h,m}`,
`core-render-ios/Extension/Category/UIView+CSS.m`
Driver: mobile repository test-tag rule (testability ships with the feature); PRD clauses
are those of the screens under automated test, R-4 among them.

### The defect

XCUITest can reach a view only if it IS an accessibility element or CONTAINS one. The
renderer promoted a marked view to an element only when `subviews.count == 0` — a check
made at the moment the tag was applied, which for a container is before any child exists.
A marked container whose self-drawn children carried no accessibility of their own was
therefore neither an element nor a holder of one, and vanished from the tree entirely.

Measured on the room screen (fully rendered, simulator, iOS 18.6): the screen exposed
exactly two identifiers — its root and the one marked node that happened to be a leaf.
The composer, send button and feed were all marked, on screen, and unreachable. Every
other screen looked fine only because its marked nodes happened to be leaves or to wrap
native controls.

Three further writers made any per-view fix unstable: `setCss_accessibility`,
`setCss_accessibilityRole` and the clickable branch of the style application each set
`isAccessibilityElement` directly, and recomposition re-runs them against a view whose
children have been detached — so a settled container was re-promoted and swallowed its
subtree again (reproduced every time the keyboard opened).

### What this fork does instead

One deferred pass over every marked view, scheduled on the main queue whenever a tag is
applied or a subtree attached, so it always sees the finished tree:

1. a marked view with no accessible direct child becomes an element (a wrapper around a
   native control stays a container — an element is opaque, and hiding a text field costs
   it keyboard focus, measured as "neither element nor any descendant has keyboard focus"
   on sign-in);
2. every marked ancestor of a marked view is demoted to a container;
3. (added 2026-08-23) a view promoted to an element takes the drawn text of the subtree
   it swallows as its accessibilityLabel — UILabels collected in document order, an
   author-set `accessibility` prop never overridden. An element is opaque, so without
   this the swallowed text reads (and automates) as a mute button: measured on the room
   feed, every row exposed an identifier and no words — UIKit's own label synthesis is
   too shallow for nested text — leaving rows unreadable to VoiceOver and unassertable
   to XCUITest alike. Recomputed on every pass, so a reused list cell heals on the pass
   its re-applied tag schedules.

The three direct writers defer to that pass for any view carrying a test tag. Marked
nodes thus form their own tree — innermost as elements, everything above as containers —
and both kinds are reachable.

Two rejected attempts are recorded because their failure modes will tempt again: a
recursive subtree scan on every insertion hung the main thread until the watchdog killed
the app; a sticky "contains marked content" flag carried upward was order-dependent and
spread through re-parented wrappers, hiding views that contained nothing.

### Evidence

Room screen, simulator, before → after: 2 reachable identifiers → all 10
(`room.root`, `chatFeed`, `chatInput`, `sendBtn`, `chatRow#…`, four buttons), stable
across keyboard open and recomposition. Cross-host proof: `scripts/room-across-hosts.sh`
web+iOS+Android PASS, the iOS leg driven entirely through these identifiers. Label
merging: the live signal run (ronaq-mobile `scripts/wired-signals-ios.sh`) asserts the
room feed's SENTENCES on iOS through exactly this read.

### Upstreamability

High. The reachability rule is UIKit's, not Ronaq's; any Kuikly app driving XCUITest by
testTag hits the same wall. The deferred-pass shape is the part worth upstreaming intact —
the per-view variants are the two documented dead ends.

---

## 10. Pull-to-refresh works on a grid, not only on a list

**Files** · `compose/.../material3/PullToRefresh.kt`
**Driven by** · Home PRD H-1.5 (下拉刷新, P0) and the 2026-08 design revision, which
removed the refresh BUTTON in favour of the gesture
**Date** · 2026-08-26

### What upstream offers

One entry point, `LazyListScope.pullToRefreshItem(state, onRefresh, scrollState:
LazyListState, …)`, which places the header as the list's first item.

### Why that is not enough

Ronaq's home feed is a `LazyVerticalGrid` — H-1.5 is a two-column waterfall, and H-7.3
post cards span both columns. There is no list to hang the header on, so a P0 gesture
had no way to exist on the one screen the PRD names.

### What this fork changes

Nothing about the mechanism, which already worked for grids everywhere it mattered.
`ScrollableState.kuiklyInfo` and `ScrollableState.isAtTop()` both handle `LazyGridState`
— `isAtTop()` even applies the `hasPullToRefresh` index offset for grids and staggered
grids. Only the entry point was list-shaped.

1. `PullToRefreshItem`'s `scrollState` parameter widens from `LazyListState` to
   `ScrollableState`. Every line inside it was already calling `ScrollableState`
   extensions; nothing else in the body changes, and list behaviour is bit-for-bit what
   it was.
2. `LazyListScope.pullToRefreshItem`'s parameter widens the same way.
3. A new `LazyGridScope.pullToRefreshItem` places the header with
   `span = { GridItemSpan(maxLineSpan) }` — a refresh indicator occupying one column of
   a two-column grid is not a refresh indicator.

Staggered grids, pagers and plain scroll states are reachable through the same
signature now; only the grid one has an entry point, because that is the one this app
needs and an unused overload is an untested one.

### Verified

On hardware, not by compiling: `scripts/retest-pull-refresh-android.mjs` performs a slow
900 ms downward swipe on the feed and asserts that `room/getHotRoomList` is requested
again afterwards — a header that animates without refetching is the failure that check
exists for. The web renderer's list does not over-scroll, so the header draws there and
its caption never advances; that is the H5 scroll container, not this change, and web is
not a host this gesture ships on.

### Upstreaming

Worth offering as-is. It removes a restriction rather than adding a behaviour, and the
grid support it exposes is upstream's own.

---

## 11. An untagged ancestor must not swallow the tagged views beneath it

File: `core-render-ios/Extension/Category/UIView+CSSDebug.m`
Driver: mobile repository test-tag rule (testability ships with the feature); the
per-element design-alignment sweep across all six themes on all three hosts.

### The defect

An accessibility element is opaque: nothing beneath it is reachable. The pass that
promotes a marked view to an element already knew that, and demoted a marked view's
ancestors so they could not hide it — but only the ancestors that were themselves marked:

```objc
for (UIView *parent = view.superview; parent != nil; parent = parent.superview) {
    if (parent.css_testTag.length > 0 || parent.css_debugName.length > 0) {
        parent.isAccessibilityElement = NO;
    }
}
```

UIKit promotes a view of its own accord once it looks interactive, and Kuikly's clickable
wrapper looks exactly like that. Such a wrapper carries no test tag and no debug name, so
the loop walked straight past it and left it promoted — with every tagged descendant
sealed inside.

### The measurement

On a real iPhone (00008150-000651A6010A401C), on the Me tab, `app.debugDescription`
returned ONE element for the whole page:

```
Button, 0x1473ea300, {{16.0, -51.0}, {370.0, 80.0}}, label: 'R ronaq-rtc Lv.1
ID 900000002 · 🌍 Lv.1 0 / 500 · Lv.2 1 Fans 1 Following 0 Visitors ✦ Wallet 45,410
coins Recharge 🎁 Gift Wall 🎙 My Room Not created yet ♛ VIP Center ⚜ Noble Center
Open 30 days 💞 CP Space 🎯 Task Center ⭐ My Level Lv.1 🎒 Backpack 🎖 Badges
📨 Invite Friends ✿ Dress Up Store ⚑ My Family ⚙ Settings'
```

Twelve identifiers live inside that label and not one of them could be queried. The web
host publishes all twelve, so the divergence is this renderer's, not the shared UI's.

### The change

Demote EVERY ancestor of a tagged view, marked or not:

```objc
for (UIView *parent = view.superview; parent != nil; parent = parent.superview) {
    parent.isAccessibilityElement = NO;
}
```

A view that contains a tagged view is a container by definition; the semantics belong to
the children, which carry their own labels and traits.

### Verification

`scripts/retest-theme-ios.py` on the same iPhone, all six themes:

| | surfaces matching their token |
| --- | --- |
| before | 9 (gulf only — the sweep could not reach Settings again to switch theme) |
| after | **84** (14 surfaces × 6 themes) |

The one remaining skip is `home.myRoomBtn`, which this account does not have: the app
draws `home.createRoomBtn` in its place, which is a data state and not a defect.

### VoiceOver

This is a gain, not a trade. The swallowed children carry their own labels and traits;
before the change a VoiceOver user met one element reading forty words in a single
breath, with no way to reach anything inside it.

### Upstreaming

Worth offering as-is. It is general accessibility capability with no Ronaq concept in it,
and it makes the existing pass do what its own comments already say it intends.

## 12. Radial gradients (`Brush.radialGradient`, `backgroundRadialGradient`)

**What.** A radial gradient a view can carry as its BACKGROUND, on all three renderers:
`Brush.radialGradient(...)` in the Compose layer, `Attr.backgroundRadialGradient(...)` in
core, and a `radial-gradient(<cx> <cy> <r>,<argb> <stop>,…)` wire form parsed by the
Android, iOS and web renderers. Centre and radius are FRACTIONS of the view; the radius
is a fraction of its HEIGHT.

**Why.** The design states page glows as CSS radial gradients — gulf's `--roomGrad`
opens `radial-gradient(130% 68% at 50% -6%,rgba(245,193,92,.14) 0%,…)`. The fork had
linear, horizontal, vertical and sweep brushes and no radial, so the shared layer drew
the glow as a stack of 56 stroked annuli on a full-screen `Canvas`.

Measured on a Pixel 2 (Android 8.1), login screen, eight seconds idle:

| | frames | janky | 50th |
| --- | --- | --- | --- |
| 56 annuli + the dot pattern | 44 | 100% | 350 ms |
| dot pattern only | 51 | 100% | 300 ms |
| neither | 610 | 0.33% | 9 ms |
| **radial background + dot pattern** | **438** | 100% | **40 ms** |

The glow alone cost ~290 ms per frame, and it was re-issued on EVERY frame because the
brand mark runs an infinite animation that keeps the frame loop alive. As a background
the renderer draws it once. The remaining 40 ms is the dot pattern, which is a tiled
background (`background-size: 22px`) and needs tiling support to move the same way.

**Radius is the HEIGHT, not the shorter side.** The design's glows are ellipses wider
than the screen in every skin, so the vertical extent is what shapes them; a circle of
the width would be a different shape.

**PRD basis.** No clause requires a gradient primitive. It serves the appearance
baseline (`prototype/`) and the 60 fps budget in `ARCHITECTURE.md §3` — this screen was
running at about 2.5.

**Files.** `compose/.../ui/graphics/Brush.kt`, `compose/.../ui/text/style/TextForegroundStyle.kt`,
`core/.../base/Attr.kt`, `core-render-android/.../css/drawable/KRCSSBackgroundDrawable.kt`,
`core-render-web/.../ktx/KuiklyRenderCSSKTX.kt`, `core-render-ios/.../UIView+CSS.m`.
