# PIP placement probe results

**Date:** 2026-09-03  
**Environment:** Android 9/API 28 `OpenLauncherAOSP`, AOSP test platform key

## Implemented

- Added `:pip-probe-app`, package `com.openlauncher.pipprobe`.
- Added AOSP-only `PipPlacementProbeActivity` for a single pane independent of Compose and launcher settings.
- Removed `FLAG_ACTIVITY_MULTIPLE_TASK` from the embedding launch.
- Replaced the reusable request-code-0 PendingIntent with a unique, cancel-current immutable token.
- Matched `ActivityView` send semantics: null context and null fill-in Intent.
- Added placement polling through `IActivityManager.getAllStackInfos()`.
- Replaced the former feature fail-fast with privileged stack placement: snapshot stacks, launch, poll by package, `moveStackToDisplay`, verify, then `setFocusedStack`.
- Added focused Frida hooks for activity placement and task reuse.
- Added an emulator-only feature installer guarded by `ro.kernel.qemu=1`.

## Direct-launch gate

The original AVD did not advertise:

```text
android.software.activities_on_secondary_displays
```

Consequently `ActivityManagerService.mSupportsMultiDisplay` was false. The decisive trace was:

```text
ActivityStarter.getReusableIntentActivity result=null
ActivityStackSupervisor.isCallerAllowedToLaunchOnDisplay result=true
ActivityStackSupervisor.canPlaceEntityOnDisplay resizeable=true result=false
ActivityRecord.canBeLaunchedOnDisplay result=false
```

This proves Android rejected OpenLauncher's direct `setLaunchDisplayId()` path at the global multi-display gate. It does not prove that all privileged task-management paths are blocked.

LeCoAuto was subsequently tested after removing the feature, rebooting, force-stopping Maps/YouTube, and removing their old stacks. It still created fresh Maps and YouTube tasks on VirtualDisplays. Its API 28 implementation exposes `IActivityManager.moveStackToDisplay`, and the framework's `moveStackToDisplayLocked()` path does not check `mSupportsMultiDisplay`. Therefore the feature is not a LeCoAuto requirement; enabling it only made OpenLauncher's simpler direct-launch path work.

## Results after enabling the feature

| Target | Requested display | Actual display | Result |
|---|---:|---:|---|
| `com.openlauncher.pipprobe` | 3 | 3 | Pass |
| `app.morphe.android.apps.maps` | 4 | 4 | Pass |
| `app.revanced.android.youtube` | 5 | 5 | Pass |

The probe also reported the exact VirtualDisplay metrics instead of the default display metrics:

```text
displayId=3 size=1794x732 densityDpi=420
```

## Results with the feature disabled

Cold test after force-stopping both targets:

| Target | Stack | VirtualDisplay | Result |
|---|---:|---:|---|
| `app.morphe.android.apps.maps` | 24 | 13 | Pass |
| `app.revanced.android.youtube` | 25 | 14 | Pass |

Runtime log confirms both explicit moves completed within the first polling cycle:

```text
moveStackToDisplay(25, 14) ok
placement complete package=app.revanced.android.youtube stackId=25 displayId=14
moveStackToDisplay(24, 13) ok
placement complete package=app.morphe.android.apps.maps stackId=24 displayId=13
```

Touch focus routing also passed: a left-pane tap focused stack 24, and a right-pane tap focused stack 25.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/temurin-22-jdk-amd64 \
  ./gradlew :app:assembleAospDebug :pip-probe-app:assembleDebug
```

The OpenLauncher AOSP APK must be signed with the platform key matching the test system image.

## Prepare the AVD

Start the AVD with a writable system image:

```bash
ANDROID_SDK_ROOT=/home/hongnguyen/android-sdk \
  /home/hongnguyen/Android/Sdk/emulator/emulator \
  -avd OpenLauncherAOSP -writable-system -no-snapshot-load
```

Enable the missing feature once:

```bash
docs/pip-investigation/scripts/enable-secondary-displays-avd.sh
```

The script refuses to run unless `ro.kernel.qemu=1`.

## Run

Cold-launch the minimal probe:

```bash
docs/pip-investigation/scripts/run-placement-probe.sh
```

Cold-launch another package:

```bash
docs/pip-investigation/scripts/run-placement-probe.sh app.morphe.android.apps.maps
```

Dump complete placement state:

```bash
docs/pip-investigation/scripts/dump-placement.sh app.morphe.android.apps.maps
```

Run focused system-server tracing, with `frida-server` already running:

```bash
FRIDA_BIN=/path/to/frida \
  docs/pip-investigation/scripts/trace-placement.sh
```

## Remaining scope

Steps 1-4 establish that activity placement works. The first three interaction improvements were subsequently implemented and tested:

- `IInputForwarder` is created before launch and released with the embedder.
- `EmbeddedSurfaceView` forwards touch and pointer-class generic motion.
- The divider has an 18dp touch target while retaining a 2dp visible line.
- Pane input is locked during divider drag and for 180ms after commit.
- Resize requests reject invalid dimensions, deduplicate identical sizes, and debounce for 80ms.

Dual-pane runtime result:

```text
left  requestedDisplay=12 actualDisplay=12 initial=873x1017
right requestedDisplay=13 actualDisplay=13 initial=873x1017
divider drag forwarded touches=0
left  resize applied 1103x1017@420
right resize applied 644x1017@420
post-resize left tap forwarded touches=1
```

The remaining LeCoAuto parity work is tap exclusion, task-stack observation/recovery, back routing, and complete long-run task/surface lifecycle validation.
