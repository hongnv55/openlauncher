# PIP v1.0 readiness

## Implemented and verified

### Activity placement

- VirtualDisplay with `PUBLIC | OWN_CONTENT_ONLY`.
- `dontOverrideDisplayInfo(displayId)` before launch.
- Feature-independent API 28 placement through `getAllStackInfos`, `moveStackToDisplay`, placement verification, and `setFocusedStack`.
- Unique immutable PendingIntent with ActivityView-compatible send semantics.
- Actual task display polling after launch.
- Verified with probe app, Maps, and ReVanced YouTube on API 28.
- Cold-start verified with `android.software.activities_on_secondary_displays=false`.

### Input forwarding

- Reflective `InputManager.createInputForwarder(displayId)`.
- Cached `IInputForwarder.forwardEvent(InputEvent)` method.
- Touch forwarding with full gesture ownership.
- Pointer-class generic motion forwarding.
- Parent interception disabled while an embedded gesture is active.
- Forwarder state cleared during release.
- Embedded stack is refocused before every forwarded `ACTION_DOWN`.

### Divider behavior

- 18dp touch target with a 2dp visible divider.
- Embedded pane input locked while dragging.
- Input remains locked for 180ms after resize commit.
- Live preview line without resizing VirtualDisplays on every pointer movement.
- Split is committed once at drag end.
- The committed split is retained locally while persisted state catches up, preventing snap-back.
- Each gesture starts from the latest committed fraction and uses its total horizontal delta.

### Resize control

- Zero and invalid dimensions are ignored.
- Identical applied or pending sizes are deduplicated.
- Surface resize bursts are coalesced with an 80ms debounce.
- Existing task remains on its VirtualDisplay; resize does not relaunch it.
- Runtime dual-pane test produced one resize per pane after divider commit.

## Runtime evidence

```text
createInputForwarder(12) ok
createInputForwarder(13) ok
probe actualDisplay=12
maps actualDisplay=13
resize applied 1103x1017@420
resize applied 644x1017@420
divider drag: probe touches=0
tap after settle: probe touches=1
```

Repeated-drag regression test after the committed-state fix:

```text
initial: 873x1017 | 873x1017
drag right: 1148x1017 | 599x1017
drag left from committed divider: 611x1017 | 1136x1017
drag right from committed divider: 1003x1017 | 744x1017
drag near center: 892x1017 | 855x1017
tap after settle: probe touches 1 -> 2
```

Feature-disabled cold placement and focus regression:

```text
Maps:   stack 24 -> display 13
YouTube: stack 25 -> display 14
left tap:  mFocusedStack=24
right tap: mFocusedStack=25
```

## Diagnostic entry points

- Single pane: `.debug.PipPlacementProbeActivity`
- Dual pane: `.debug.PipDividerProbeActivity`
- Script: `docs/pip-investigation/scripts/run-placement-probe.sh`
- Script: `docs/pip-investigation/scripts/run-divider-probe.sh`

The diagnostic activities only exist in the `aospDebug` source set.

## Remaining v1.0 hardening

The requested input/divider/resize scope is complete. Before declaring the whole PIP feature production-ready, the remaining high-priority items are:

1. Tap-exclude region updates when pane bounds move.
2. Back routing to the focused pane.
3. Task-stack observation and recovery after an embedded app exits.
4. Explicit policy for returning an embedded task to display 0/fullscreen.
5. Long-run lifecycle tests covering launcher hide/show and repeated widget recreation.
6. Validation on the physical head unit, whose density and firmware differ from the AVD.
