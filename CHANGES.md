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
