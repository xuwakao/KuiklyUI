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

## 1. Layout direction is inherited at attach — RTL

**Files** · `compose/.../ui/node/LayoutNode.kt`
**Driven by** · Charter C-5 (Arabic is a launch language, not a later addition)
**Date** · 2026-08-07

### What upstream does

`LayoutNode.layoutDirection` defaults to `Ltr` and is only ever assigned inside the
`compositionLocalMap` setter, from `LocalLayoutDirection`. `Row`, `Arrangement` and
`padding(start/end)` all read that value through the measure scope.

### What goes wrong here

In this renderer nothing ever sets `compositionLocalMap`. Verified by probe on device:
a `println` inside the setter never fired during a full app launch, while a probe in
`Row`'s `arrange` printed `dir=Ltr` — with the application providing
`LocalLayoutDirection = Rtl` two levels above, confirmed by a third probe reading
`LocalLayoutDirection.current` inside the app's own theme.

The result is that an Arabic build renders Arabic copy in a left-to-right layout:
icons on the left, tab order unreversed, the room's composer input on the left. The
app cannot fix this from outside — every layout in the tree measures Ltr regardless
of what it provides.

### The change

```kotlin
this.owner = owner
this.depth = (parent?.depth ?: -1) + 1

// Ronaq: inherit the layout direction from the parent, or from the owner for a
// root node.
layoutDirection = parent?.layoutDirection ?: owner.layoutDirection
```

A node takes its direction from its parent when it attaches, and the root takes the
owner's — which `RootNodeOwner` already receives from `ComposeContainer.layoutDirection`
at scene creation. A node that later does receive composition locals still overrides
this, so the upstream path is untouched.

### Verification

Device: AVD `ronaq-test`, android-34, arm64-v8a, 1080x2400.

- Arabic (`--es lang ar`): header avatar and back control move to the right, the mic
  seats run right-to-left with seat 1 rightmost, the composer input sits on the right
  with send / gift / mute / leave running leftward, and the regional motif mirrors.
- English: unchanged. The ten-assertion device walk (`scripts/walk-flow.sh`) passes,
  and the Me page, room, square, wallet and ranking screenshots match the previous
  build.

### Upstream

Worth offering: the fix is four words of behaviour, it restores documented Compose
semantics rather than adding a Ronaq concept, and every RTL-market app on this
framework needs it. If upstream prefers wiring `compositionLocalMap` properly
instead, that supersedes this change and it should be dropped on the next merge.
