# LeCoAuto PIP deep dive

**Ngay nghien cuu:** 2026-09-02  
**Target:** LeCoAuto 1.9.0.8 tren Android 9/API 28  
**Pham vi:** co che nhung application vao launcher bang `VirtualDisplay`/`ActivityView`, khong nghien cuu hoac vuot qua license/anti-debug.

## 1. Ket luan

LeCoAuto khong dung Android Picture-in-Picture API (`enterPictureInPictureMode`) va cung khong dung freeform window cho widget nay. Tren API 27/28, no da clone va mo rong hidden framework class `android.app.ActivityView`:

```text
SurfaceView trong layout launcher
  -> VirtualDisplay PUBLIC | OWN_CONTENT_ONLY
  -> dontOverrideDisplayInfo(displayId)
  -> IInputForwarder(displayId)
  -> ActivityOptions.setLaunchDisplayId(displayId)
  -> scan task/stack theo package
  -> moveStackToDisplay(stackId, displayId)
  -> verify + setFocusedStack(stackId)
  -> Surface cua display duoc render vao SurfaceView
```

OpenLauncher hien da clone duong placement API 28: tao `SurfaceView`/`VirtualDisplay`, goi `dontOverrideDisplayInfo`, tao input forwarder, launch, scan stack theo package, move stack sang display dich, verify va focus. No khong con phu thuoc feature secondary-display cho placement.

Hai lop van de phai duoc xu ly rieng:

1. **Placement:** tao/reparent task tren dung secondary display. Phan nay da pass runtime tren AVD khi feature bi tat.
2. **Embedding hoan chinh:** input forwarding, tap exclusion, focus/back, visibility, resize/DPI va cleanup. Cac phan nay khong phai nguyen nhan truc tiep lam task roi ve display 0, nhung bat buoc de clone UX 1:1 sau khi placement thanh cong.

## 2. Bang chung kien truc LeCoAuto

### 2.1 Class dang active

`BaseTaskView` chon implementation theo API:

- API 27/28: `q93`
- API 29: `r93`
- API 30: `t93`
- API 31+: `u93`

Bang chung: `payload_jadx_out/sources/com/lecoauto/widget/base/BaseTaskView.java:83-127`.

Tren API 28, `q93` ke thua `p93`. Cac field quan trong:

- `p93.J`: `VirtualDisplay`
- `p93.K`: `Surface`
- `p93.H`: `VirtualDisplay.Callback`
- `q93.B`: `IInputForwarder`
- `q93.V`: mang toa do view

Bang chung:

- `jadx_out/sources/defpackage/p93.java:22-35`
- `jadx_out/sources/defpackage/q93.java:12-19`

`widget3.view.EmbedView` add `widget3.chlid.TaskView` bang `MATCH_PARENT`; day la child view that trong launcher, khong phai overlay window. Bang chung: `payload_jadx_out/sources/com/lecoauto/widget3/view/EmbedView.java:25-50`.

### 2.2 ActivityView contract

Framework Android 9 tren chinh head unit chua `android.app.ActivityView`. Luong chuan trong firmware:

1. Tao `VirtualDisplay` flags `9`, tuc `PUBLIC | OWN_CONTENT_ONLY`.
2. Goi `IWindowManager.dontOverrideDisplayInfo(displayId)`.
3. Goi `InputManager.createInputForwarder(displayId)`.
4. Dang ky `TaskStackListener`.
5. Chi bao view ready sau khi cac buoc tren hoan thanh.

Bang chung tu framework keo truc tiep tu head unit:

- `/tmp/opencode/headunit-framework/framework-jadx/sources/android/app/ActivityView.java:200-225`
- Input forwarding: cung file, dong 135-158
- Surface lifecycle: cung file, dong 162-196
- Tap-exclude region: cung file, dong 116-133 va 264-270
- Cleanup: cung file, dong 228-261

LeCoAuto co cung object model va API calls, nhung them task policy, DPI tuy chinh va workaround theo platform.

### 2.3 Native Dex2C da xac nhan

`p93`, `q93` va `n93.run()` bi Dex2C chuyen sang ARM machine code trong `libencry.so`. Phan tich static native xac nhan:

- `p93.E()` phan xa va goi `dontOverrideDisplayInfo(displayId)` tren SDK phu hop.
- `p93.G()` tao `ActivityOptions`, sau do `setLaunchDisplayId(p93.T())`.
- `q93.O()` phan xa `InputManager.createInputForwarder(displayId)`, cast sang `IInputForwarder`, luu vao `q93.B`; tren SDK 28 no goi tiep `p93.E()`.
- `q93.I/N` dung `SurfaceView.getLocationOnScreen`, `IWindowSession.updateTapExcludeRegion`, `SurfaceView.getIWindow` va `WindowManagerGlobal.getWindowSession`.
- `p93.H()` tao/gan lai `VirtualDisplay`, lay `Display.getDisplayId()` va co them policy/setup theo SDK.
- `n93.run()` co ca nhanh launch moi va nhanh danh thuc/di chuyen task dang ton tai; binary chua string/call cho `moveStackToDisplay`, `moveRootTaskToDisplay`, `setFocusedStack`, `setFocusedTask`.

Dia chi native da map de tiep tuc kiem chung:

| Method | Address |
|---|---:|
| `n93.run()` | `0xb5a90` |
| `p93.E()` | `0xc3cb0` |
| `p93.G()` | `0xc47b0` |
| `p93.H(SurfaceView)` | `0xc4bd0` |
| `p93.J(w,h,dpi)` | `0xc78c0` |
| `q93.O()` | `0xcb5c0` |
| `q93.I(SurfaceView,Surface)` | `0xc9850` |
| `q93.N()` | `0xc9bd0` |

Luu y: binary co tham chieu `setDisplayImePolicy`, nhung Android 9 framework tren head unit khong expose method nay. Day co the la nhanh SDK moi hoac reflection duoc boc `try/catch`; khong duoc xem la dieu kien placement tren API 28.

### 2.4 Input that su duoc forward

`BaseTaskView` override ca `onTouchEvent` va `onGenericMotionEvent`, ngan parent intercept, sau do goi `p93.Y(event)`. Tren `q93`, method nay la Java thuong va goi thang:

```java
IInputForwarder inputForwarder = this.B;
return inputForwarder != null && inputForwarder.forwardEvent(event);
```

Bang chung:

- `payload_jadx_out/sources/com/lecoauto/widget/base/BaseTaskView.java:557-572`
- `payload_jadx_out/sources/com/lecoauto/widget/base/BaseTaskView.java:621-636`
- `jadx_out/sources/defpackage/q93.java:35-43`

`dumpsys display` tren head unit cung cho thay display cua LeCoAuto nam trong `mVirtualTouchViewports`. Day la dau vet runtime rang `createInputForwarder` da thanh cong.

### 2.5 Task policy

LeCoAuto khong launch mu quang moi lan:

- Tim app/task dang chay theo package/component.
- Biet `taskId`, `stackId` va `displayId`.
- Neu app da nam tren secondary display, no co the reuse/awaken thay vi tao task moi.
- Khi dua app ra fullscreen, no co the move task ve display 0.
- Co eviction/force-stop cho app cu de tranh task stale va tiet kiem tai nguyen.

Bang chung:

- `jadx_out/sources/defpackage/ba3.java:19-41`
- `jadx_out/sources/defpackage/ca3.java:20-42`
- `payload_jadx_out/sources/com/lecoauto/utils/model/TaskInfo.java:7-38`
- `payload_jadx_out/sources/defpackage/sb.java:81-108`
- `payload_jadx_out/sources/defpackage/lb.java:40-69,101-119`

Live trace truoc day khong thay `moveStackToDisplayLocked` trong mot cua so 40 giay. Dieu do chi loai call nay khoi lan launch da quan sat, khong loai toan bo task policy cua LeCoAuto.

### 2.6 Density va resize

LeCoAuto khong bat buoc dung density cua display chinh:

- Moi pane co DPI rieng.
- UI cho phep khoang 100-1000 DPI.
- Thay doi DPI goi `p93.Z(dpi)` native.
- Tren Freescale + API 28, khi size thay doi no remove/reinflate subtree va delay 388 ms thay vi chi `VirtualDisplay.resize()`.

Bang chung:

- `payload_jadx_out/sources/com/lecoauto/widget3/chlid/TaskView.java:74-82,141-147`
- `payload_jadx_out/sources/defpackage/h5.java:107-181`
- `payload_jadx_out/sources/com/lecoauto/widget/base/BaseTaskView.java:159-164,610-619`

Head unit dang ket noi la Android 9/API 28, fingerprint `alps/full_spm8666p2_64_pvetec_p05/...`, khong phai Freescale theo ten platform quan sat. Workaround nay can giu theo platform detection, khong ap dung vo dieu kien.

## 3. Vi sao OpenLauncher roi ve display 0

### 3.1 Co che fallback cua Android 9

`setLaunchDisplayId` la preference, khong phai lenh bat buoc. Android 9 chi tao stack tren secondary display khi `ActivityRecord.canBeLaunchedOnDisplay(displayId)` thanh cong. Neu placement hoac reusable-task resolution khong thoa man, `getLaunchStack()` co the im lang tra stack hien tai/default display.

Firmware head unit dung logic AOSP tuong ung:

- `ActivityRecord.java:1014` goi `canPlaceEntityOnDisplay`.
- `ActivityStackSupervisor.java:228-249` kiem tra multi-display, resize/config va quyen caller.
- `ActivityStackSupervisor.java:1717-1808` chon/tao launch stack va fallback.
- `ActivityStarter.java:1574-1643` tim reusable task va chi reparent trong dieu kien cu the.

Duong dan decompile firmware: `/tmp/opencode/headunit-framework/services-jadx/sources/com/android/server/am/`.

Head unit co feature `android.software.activities_on_secondary_displays`; LeCoAuto va OpenLauncher aosp build deu co the chay UID 1000. Vi vay quyen so huu display khong phai gia thuyet manh nhat.

### 3.2 Sai khac co kha nang gay placement fail

#### A. Task stale/reuse tren display 0

OpenLauncher khong lookup task hien tai truoc launch. Hai app test co the da co task tren display 0; `singleTask`, `singleInstance`, affinity va task matching van co the tai su dung task du co `MULTIPLE_TASK`.

Day la gia thuyet uu tien cao nhat vi no khop hien tuong: Binder launch thanh cong, khong exception, nhung task cu duoc dua len display 0.

#### B. `PendingIntent` cache khong duoc kiem soat

OpenLauncher dung request code `0`, `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`, roi gui lai chinh intent nhu fill-in:

`app/src/main/java/com/openlauncher/app/ui/widget/PipWidget.kt:383-387`.

Tren Android 9, key cua `PendingIntent` khong bao gom Intent flags. `FLAG_UPDATE_CURRENT` thay extras nhung co the giu base request Intent cu; `IMMUTABLE` lam fill-in intent bi bo qua. Sau nhieu lan thu nghiem voi flags khac nhau, OpenLauncher co nguy co dang gui mot token cache mang intent cu.

Framework `ActivityView.startActivity(PendingIntent)` chuan gui:

```java
pendingIntent.send(null, 0, null, null, null, null, options.toBundle());
```

Bang chung: head-unit `ActivityView.java:91-97`.

#### C. Intent/task semantics khong match

OpenLauncher ep `NEW_TASK | MULTIPLE_TASK` tai `PipWidget.kt:370`. Visible Java path cua LeCoAuto chi tao intent explicit voi `NEW_TASK`; `PackageManager.getLaunchIntentForPackage` thuong mang `NEW_TASK | RESET_TASK_IF_NEEDED`.

Khong co bang chung LeCoAuto runtime API 28 ep `MULTIPLE_TASK`. Vi vay khong nen coi flags launch hien tai la da match.

#### D. Trace cu da so sanh sai invocation

Bao cao cu ghi `canPlaceEntityOnDisplay=false` cho ca LeCoAuto thanh cong va OpenLauncher fail. Hai ket qua nay khong the cung la decisive invocation cua mot new task: neu call placement that su tra false, Android 9 khong tao secondary stack.

Method nay duoc goi tu nhieu noi:

- `ActivityRecord` truyen target activity, pid/uid va resize state that.
- `TaskRecord` co the truyen pid/uid `-1` va `ActivityInfo=null`.
- Existing-task validation co context khac new-task placement.

Trace tiep theo phai hook `ActivityRecord.canBeLaunchedOnDisplay` va `getLaunchStack`, dong thoi log object/argument/return, khong chi log ten method va boolean.

#### E. Trinh tu khoi tao khong match ActivityView

OpenLauncher dang:

- dang ky `DisplayListener`;
- tao display;
- tao thread rieng;
- goi `dontOverrideDisplayInfo`;
- sleep 150 ms;
- launch;
- co fallback timer 400 ms.

LeCoAuto/framework ActivityView thuc hien setup dong bo ngay sau `createVirtualDisplay`: `dontOverrideDisplayInfo`, input forwarder, task listener, sau do moi bao ready. Khong co bang chung cho sleep 150 ms hay `DisplayListener` 400 ms trong LeCoAuto.

Day co the tao race configuration/display-content, nhung xep sau task reuse va PendingIntent cache.

### 3.3 Nhung thu khong giai thich placement

- Thieu `IInputForwarder`: lam app khong nhan touch, khong lam activity roi ve display 0.
- Thieu tap-exclude region: anh huong input/focus, khong tao stack.
- DPI sai: anh huong scale/configuration; app resizeable van co the launch.
- `setDisplayImePolicy`: khong co trong framework API 28 cua head unit va khong phai placement permission.
- Virtual display owner package: Android 9 access check chu yeu theo owner UID; ca hai la UID 1000 va display la public.
- “Trusted display” flags: day la mo hinh Android moi hon, khong ton tai trong framework Android 9 nay.

## 4. Khoang cach cua OpenLauncher

### 4.1 Bat buoc de sua loi hien tai

| Hang muc | LeCoAuto/ActivityView | OpenLauncher |
|---|---|---|
| Cold/reused task policy | Lookup, awaken, move/focus, eviction | Launch package truc tiep |
| PendingIntent identity | Chua reconstruct het | Request code 0 + update + immutable |
| Launch flags | Visible path khong ep `MULTIPLE_TASK` | Ep `MULTIPLE_TASK` |
| Ready sequence | Setup dong bo sau VD creation | listener + sleep + timer |
| Placement observability | Co wrapper task/display | Chi log `send()` thanh cong |

`PendingIntent.send()` thanh cong chi co nghia request duoc AMS chap nhan; no khong chung minh task da vao requested display.

### 4.2 Bat buoc de clone UX sau placement

| Hang muc | Trang thai OpenLauncher |
|---|---|
| `IInputForwarder` va forward touch/mouse | Thieu |
| `requestDisallowInterceptTouchEvent` | Thieu |
| Tap-exclude region theo vi tri view | Thieu |
| `TaskStackListener`/foreground tracking | Thieu |
| Back/key routing theo pane | Thieu |
| Surface alpha/ready gate | Thieu |
| Visibility hide/show tach biet detach/release | Thieu |
| Per-pane DPI | Thieu |
| Existing task move ve display 0 | Thieu |
| Cleanup input/listener/surface | Chua day du |

Hai lifecycle bug cu the trong `PipWidget.kt`:

- `release()` khong reset `launched`; object bi reuse co the tao display moi nhung khong launch lai.
- `DisplayListener` chi unregister trong `launchOnce`; failure/dispose som co the leak listener.

## 5. Ke hoach kiem chung theo thu tu

Khong nen them tat ca API mot luc. Moi test chi thay mot bien va phai dump task/display sau moi launch.

### Test 1: cold-task placement

1. Force-stop app target va remove task target.
2. Xac nhan khong con task package trong `dumpsys activity activities`.
3. Tao VirtualDisplay va launch mot lan.
4. Kiem tra stack/task display ngay sau launch.

Neu cold launch thanh cong, root cause la task reuse/stale, khong phai display authorization.

### Test 2: app test toi gian

Dung mot APK nho voi activity:

- `launchMode="standard"`
- `resizeableActivity="true"`
- task affinity rieng
- khong splash/router activity

Neu app toi gian vao secondary display nhung Maps/YouTube khong vao, van de nam o launch mode/router/task cua app target.

### Test 3: bo PendingIntent cache

Thu mot bien duy nhat:

- unique request code hoac `FLAG_CANCEL_CURRENT`;
- khong fill-in intent khi `send`;
- dump `dumpsys activity intents` de xac nhan base intent.

Sau do so sanh voi direct `context.startActivity(intent, options)` vi ActivityView API 28 ho tro ca hai. Live trace PendingIntent cu la bang chung ve mot lan runtime, khong co nghia PendingIntent la permission secret.

### Test 4: trace decisive placement

Hook hep, co filter package, cac method:

- `ActivityRecord.canBeLaunchedOnDisplay`
- `ActivityStackSupervisor.getLaunchStack`
- `ActivityStarter.getReusableIntentActivity`
- `ActivityStarter.setTargetStackAndMoveToFrontIfNeeded`
- `TaskRecord.reparent`

Can log:

- component/package;
- requested display ID;
- task ID, stack ID, current display ID;
- launch mode, intent flags;
- `ActivityInfo.resizeMode`, `TaskRecord.mResizeMode`;
- calling/real-calling PID, UID, package;
- returned stack/display.

### Test 5: clone ActivityView initialization

Sau khi placement root cause da ro:

1. Goi `dontOverrideDisplayInfo` dong bo.
2. Tao `IInputForwarder` truoc khi bao ready.
3. Forward touch/generic motion.
4. Update/clear tap-exclude region.
5. Dang ky task stack listener.
6. Hoan thien release va surface lifecycle.

### Test 6: task reuse va DPI

1. Lookup task theo package/component.
2. Neu task o display 0, reparent/move co kiem soat hoac remove/cold-launch.
3. Neu task da o display pane, awaken/focus thay vi launch duplicate.
4. Them DPI rieng theo pane va recreate display khi firmware/platform yeu cau.

### Test 7: target SDK

LeCoAuto target SDK 28, OpenLauncher target SDK 36. AOSP placement path khong truc tiep branch theo target SDK cua launcher, nen day khong phai gia thuyet dau tien. Tuy nhien co the build mot flavor target 28 de A/B sau khi task state va PendingIntent da duoc co dinh.

## 6. Tieu chi thanh cong 1:1

Khong danh gia bang viec `PendingIntent.send()` khong throw. Moi pane phai dat tat ca dieu kien:

1. `dumpsys display`: VirtualDisplay dung size/DPI, surface ON khi hien.
2. `dumpsys activity activities`: task/stack nam tren display ID cua pane.
3. Touch va generic motion vao dung display.
4. Back/focus khong day launcher ra sau ngoai y muon.
5. Resize/divider cap nhat configuration va scale dung.
6. Hide/show khong tao task duplicate.
7. Release xoa display, listener, input forwarder va tap-exclude region.
8. App cu co the duoc dua ve fullscreen/display 0 mot cach co chu dich.

## 7. Ket luan uu tien implementation

Thu tu hop ly nhat cho OpenLauncher:

1. Bo bien gay nhieu: cold task + app test toi gian + PendingIntent unique/null fill-in.
2. Trace dung decisive placement branch va sua task policy.
3. Thay timing thu nghiem bang lifecycle ActivityView dong bo.
4. Them input forwarder/tap exclusion/task listener.
5. Them task reuse, back/focus, DPI va resize workaround.

Khong nen tiep tuc thu ngau nhien VirtualDisplay flags hoac tang delay. Flags `PUBLIC | OWN_CONTENT_ONLY` da dung; delay khong sua duoc mot task dang bi reuse tren display 0 hoac mot `getLaunchStack()` da fallback.

## 8. Cap nhat cold probe 2026-09-03

Da trien khai app probe `com.openlauncher.pipprobe` voi activity `standard`, `resizeableActivity=true`, target SDK 28, va mot single-pane host doc lap khoi Compose/settings.

Ket qua trace truoc khi sua AVD:

```text
getReusableIntentActivity result=null
isCallerAllowedToLaunchOnDisplay display=5 uid=1000 result=true
canPlaceEntityOnDisplay display=5 resizeable=true result=false
canBeLaunchedOnDisplay display=5 result=false
placement requestedDisplay=5 actualDisplay=0
```

Day la root cause cua rieng duong launch truc tiep ma OpenLauncher dang dung: system image `OpenLauncherAOSP` khong khai bao feature:

```text
android.software.activities_on_secondary_displays
```

Do do `ActivityManagerService.mSupportsMultiDisplay=false`, va `ActivityStackSupervisor.canPlaceEntityOnDisplay()` tra false tai gate tong, truoc khi quyen caller hoac resizeability co the quyet dinh placement.

Sau khi them feature XML vao `/system/etc/permissions`, reboot AVD va chay lai cung binary:

```text
probe requestedDisplay=3 actualDisplay=3
Maps requestedDisplay=4 actualDisplay=4
YouTube requestedDisplay=5 actualDisplay=5
```

Nhu vay cac gia thuyet task stale, `PendingIntent` cache va `MULTIPLE_TASK` khong phai root cause cua redirect tren duong launch truc tiep. Cac thay doi launch semantics van duoc giu vi gan contract ActivityView hon va loai bo state khong xac dinh.

Kiem thu A/B tiep theo da xoa feature, reboot, force-stop target va loai task cu. LeCoAuto van tao task Maps/YouTube moi tren VirtualDisplay. Event log cho thay task duoc launch truoc, sau do stack bi detach/relaunch va focus bang `setFocusedStack`. Shell APK co wrapper `IActivityManager.moveStackToDisplay` tai `jadx_out/sources/defpackage/ba3.java`; framework `moveStackToDisplayLocked()` khong kiem tra `mSupportsMultiDisplay`. Vi vay LeCoAuto dung duong task-management privileged de bypass gate nay. Khong can push feature XML len head unit, va OpenLauncher can sao che fallback move-stack thay vi phu thuoc feature.

## 9. Cap nhat implementation va runtime 2026-09-03

OpenLauncher da trien khai fallback tren trong `TaskEmbedder`. Cold start voi feature van `false` cho ket qua:

```text
Maps: launch -> stack 24 -> moveStackToDisplay(24, 13) -> verified
YouTube: launch -> stack 25 -> moveStackToDisplay(25, 14) -> verified
```

`dumpsys activity activities` xac nhan task Maps nam tren display 13, task YouTube nam tren display 14, va launcher van visible tren display 0. Moi forwarded `ACTION_DOWN` goi `setFocusedStack` truoc; tap hai pane da lan luot focus dung stack 24 va 25.
