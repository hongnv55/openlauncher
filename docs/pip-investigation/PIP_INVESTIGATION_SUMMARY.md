# OpenLauncher PIP (multi-app) feature — investigation summary

**Ngày:** 2026-09-02
**Mục tiêu gốc:** Clone 1:1 trải nghiệm Picture-in-Picture của lecoauto (`app.morphe`/`app.revanced` chạy song song trong 1 widget) cho OpenLauncher, vì kỹ thuật hiện tại (freeform windows) có 2 vấn đề UX: thanh caption bar quá lớn, và content của app scale không đúng với size thật của pane.

**Kết luận nhanh (cập nhật 2026-09-03):** OpenLauncher đã chạy được VirtualDisplay embedding khi feature `android.software.activities_on_secondary_displays` bị tắt. Bản production hiện launch task, tìm stack theo package, gọi `IActivityManager.moveStackToDisplay`, xác nhận display rồi gọi `setFocusedStack`; touch vào mỗi pane cũng refocus stack trước khi forward input. Bản freeform cũ vẫn được backup tại `docs/pip-investigation/PipWidget.freeform-working-backup.kt`.

> **Đính chính sau A/B test 2026-09-03:** feature `android.software.activities_on_secondary_displays` chỉ là gate của đường launch trực tiếp bằng `ActivityOptions.setLaunchDisplayId()` mà OpenLauncher đang dùng. Sau khi xóa feature, reboot, force-stop target và loại task cũ, LeCoAuto vẫn tạo task Maps/YouTube mới trên VirtualDisplay. Event log cho thấy LeCoAuto launch task trước, sau đó reattach/relaunch stack và gọi `setFocusedStack`; shell APK cũng có wrapper `IActivityManager.moveStackToDisplay`. Framework `moveStackToDisplayLocked()` không kiểm tra `mSupportsMultiDisplay`, nên đây là đường bypass cần sao chép. Kết luận cũ rằng feature là yêu cầu chung của LeCoAuto là sai.

---

## 0. Tài nguyên & công cụ có sẵn (dùng luôn, không cần tải lại)

| Thứ | Đường dẫn |
|---|---|
| jadx (CLI + GUI) | `/home/hongnguyen/Downloads/jadx-1.5.0/bin/jadx` (và `jadx-gui`) |
| frida-server đã tải sẵn (17.17.0, android-x86_64, dạng `.xz`) | `/media/hongnguyen/data/car/shared/frida-server-17.17.0-android-x86_64.xz` — giải nén bằng `xz -d`, push vào `/data/local/tmp/`, `chmod 755`, chạy `nohup ... &` trên device (cần root, cần user cho phép — action nhạy cảm) |
| frida-tools (Python venv đã cài, dùng lại luôn) | `/tmp/claude-1000/-media-hongnguyen-data-car-bingo-update-0120-E260-V4-00-23-extracted/ffbc97a0-b801-4756-a262-6859b1539b0f/scratchpad/fridavenv/bin/frida` — ⚠️ nằm trong `/tmp` scratchpad, **có thể đã mất khi qua session mới**; nếu mất, tạo lại nhanh: `python3 -m venv <path>/fridavenv && <path>/fridavenv/bin/pip install frida-tools` (phải dùng venv vì hệ thống chặn `pip install` trực tiếp, PEP 668) |
| lecoauto APK gốc | `/media/hongnguyen/data/car/bingo/apk/patch/lecoauto_1.9.0.8.apk` — ký cùng cert test-keys AOSP, cài thẳng lên AVD test là chạy UID 1000 thật, không cần resign |
| lecoauto — decompile shell APK (jadx, có sẵn) | `/media/hongnguyen/data/car/bingo/apk/patch/jadx_out/` — chứa `p93`/`q93`/... nhưng toàn bộ method quan trọng là `native` (Dex2C), không đọc được logic thật |
| lecoauto — payload dex đã **decrypt lúc runtime** (raw `.dex`, chưa decompile) | `/media/hongnguyen/data/car/bingo/apk/patch/decrypted_dex/classes.dex` — decompile lại bằng jadx với flag debug để đọc được nhiều hơn bản mặc định, vd:<br>`jadx --show-bad-code --comments-level debug -d <outdir> decrypted_dex/classes.dex` |
| lecoauto — payload dex đã decompile sẵn (bản cũ, từ session trước) | `/media/hongnguyen/data/car/bingo/apk/patch/payload_jadx_out/` — chứa `BaseTaskView`, `TaskView` (dưới `com.lecoauto.widget.*`/`widget2.*` — bản CŨ/không active), `am.java`, `bm.java`, `ch1.java`, `zj.java`, `h5.java`, `EmbedView.java`. **Lưu ý:** re-decompile từ `decrypted_dex/classes.dex` (dùng `--show-bad-code --comments-level debug`) cho ra bản **đầy đủ hơn** — tìm thấy `com.lecoauto.widget3.chlid.TaskView` (class **đang thực sự active**, package `widget3`) mà bản `payload_jadx_out` cũ không có/không đọc được hết (vd method `S(int, AppInfo)` trong `EmbedView` cũ bị lỗi "Method not decompiled", bản widget3 mới không bị lỗi này) |
| Report reverse-engineering VIP/license (session trước) | `/media/hongnguyen/data/car/bingo/apk/patch/BAO_CAO_REVERSE_ENGINEERING_LECOAUTO.md` |
| Native lib chứa logic Dex2C thật (ARM machine code, chưa disassemble) | `libencry.so` trong `jadx_out/resources/lib/arm64-v8a/` (và `armeabi-v7a/`) — cần `radare2`/Ghidra để đọc thật, xem mục 7 |

**Gợi ý quan trọng cho session sau:** nên **re-decompile `decrypted_dex/classes.dex` bằng jadx với `--show-bad-code --comments-level debug`** ngay từ đầu thay vì chỉ đọc `payload_jadx_out/` cũ — đã chứng minh cho ra kết quả đầy đủ hơn hẳn (tìm ra được class `widget3.chlid.TaskView` đang active, và 1 số method đọc được thêm mà bản cũ bị lỗi decompile).

## 1. Bối cảnh & môi trường test

- **Project:** `tools/openlauncher` — file chính: `app/src/main/java/com/openlauncher/app/ui/widget/PipWidget.kt`
- **Git:** branch `feature/pip-dual-app-view`, có nhiều thay đổi chưa commit (user chưa yêu cầu commit)
- **Signing:** platform test-keys tại `.claude/keys/platform.pk8` + `platform.x509.pem` (SHA-256 `c8a2e9bc...`) — app build với `sharedUserId="android.uid.system"` → cài vào AVD test-keys thì chạy dưới **UID 1000**
- **Build:** `JAVA_HOME=/usr/lib/jvm/temurin-22-jdk-amd64` (bắt buộc, JDK 21 chỉ có JRE không có javac). Build type: `aospDebug`. Sign bằng `apksigner` (build-tools 33.0.1) rồi `adb install -r -g`.
- **AVD test:** `OpenLauncherAOSP` (system-images;android-28;default;x86_64 — **bản `default`, KHÔNG phải `google_apis`** — vì chỉ bản default mới ký bằng test-keys công khai khớp với `platform.pk8`). Khởi động: `emulator -avd OpenLauncherAOSP -gpu host -memory 6144 -no-snapshot-load`.
- **2 app test:** `app.revanced.android.youtube`, `app.morphe.android.apps.maps` — **đã xác nhận qua `aapt dump xmltree` rằng cả 2 khai báo tường minh `android:resizeableActivity="true"`** (giá trị `0xffffffff` = true trong encoding của aapt). Đây là các bản test/patched riêng cho AVD này, **không phải app gốc từ Play Store** — nghĩa là mọi block "resizeableActivity" gặp phải KHÔNG áp dụng ở đây (2 app này vốn đã resizeable).
- Chuyển đổi launcher mặc định để so sánh: `adb shell cmd package set-home-activity <pkg>/.MainActivity`

## 2. Hai kỹ thuật đã thử

### A. Freeform windows (`WINDOWING_MODE_FREEFORM`) — **ĐANG CHẠY ỔN ĐỊNH**

Kỹ thuật: launch app qua `ActivityOptions.setLaunchWindowingMode(FREEFORM)` + `launchBounds`, sau đó dùng reflection gọi `IActivityManager.resizeTask(taskId, bounds, RESIZE_MODE_USER_FORCE)` để ép đúng khung của divider. Che caption bar thật bằng 1 overlay (dùng thư viện `com.hjq.window` EasyWindow — Gradle: `implementation("com.github.getActivity:EasyWindow:15.8")`, cần repo `jitpack.io`). Khoá không cho user kéo/resize bằng touchable overlay + watchdog `resizeTask` định kỳ.

**Đã fix xong trong quá trình này:**
- Overlap giữa 2 pane → do hệ thống tự nới rộng dưới mức tối thiểu (`default_minimal_size_resizable_task`) → clamp split-range theo mức tối thiểu này.
- Divider không kéo được khi cả 2 pane có app chạy → do surfaceInsets ~40dp của freeform window nuốt hết touch → thêm 1 touch-priority overlay (`DividerOverlay`) đè lên trên.
- `resizeTask` với `RESIZE_MODE_SYSTEM` chỉ có tác dụng lúc mới launch, không tác dụng khi task đã settle → phải dùng `RESIZE_MODE_USER_FORCE` (giá trị 5).
- Caption mask ban đầu lệch vị trí (WMS tự clip đè overlay vào vùng "stable" trừ status bar) → cần cờ `FLAG_LAYOUT_IN_SCREEN`.
- Giả định "caption nằm ở dải TRÊN rect.top" sai — xác nhận qua `uiautomator dump`: caption thực ra nằm ở ~110px ĐẦU của chính task bounds, không phải phía trên nó.

**Vấn đề UX còn lại theo đúng lời user (lý do muốn bỏ hướng này):** cảm giác thanh bar phía trên (dù đã che) vẫn chiếm không gian quá lớn, và content các app scale không khớp chuẩn với kích thước pane thật.

→ **Bản này đã backup tại `docs/pip-investigation/PipWidget.freeform-working-backup.kt`** (666 dòng, hoạt động đầy đủ: dual-pane, divider kéo được, không overlap, không lộ caption, không cho user kéo-thả window).

### B. VirtualDisplay embedding (đúng kỹ thuật của lecoauto) — **ĐÃ PLACEMENT THÀNH CÔNG**

Kỹ thuật: tạo 1 `VirtualDisplay` riêng cho mỗi pane, gắn vào 1 `SurfaceView` trong layout của mình, rồi launch app thật vào display đó qua `ActivityOptions.setLaunchDisplayId()`. Nếu thành công, app sẽ hiện ra như 1 activity fullscreen bình thường bên trong SurfaceView — không hề có caption/title bar (vì nó chưa bao giờ là freeform window), scale đúng chuẩn 100% vì đó chính là kích thước thật của display.

Đường launch trực tiếp từng bị Android redirect về display mặc định (`displayId=0`). Sau A/B test, OpenLauncher đã sao chép đường privileged task-management của LeCoAuto và không còn phụ thuộc feature gate này.

**5 giả thuyết đã test — TẤT CẢ ĐỀU FAIL GIỐNG HỆT NHAU (redirect về display 0):**

| # | Giả thuyết | Cách verify | Kết quả |
|---|---|---|---|
| 1 | Launch quá sớm, display chưa sẵn sàng → cần đợi `DisplayManager.DisplayListener.onDisplayAdded()` | Log timing | Fix xong 1 bug thật (code cũ quên gọi `launch()`), nhưng sau khi fix, vẫn fail |
| 2 | Sai `flags` khi tạo VirtualDisplay | So `dumpsys display` của mình với của lecoauto | Dùng đúng `VIRTUAL_DISPLAY_FLAG_PUBLIC \| VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY` → **khớp 100% byte-for-byte** với lecoauto's live display (`FLAG_OWN_CONTENT_ONLY` duy nhất, không còn `FLAG_PRIVATE`) → **vẫn fail** |
| 3 | Dùng `context.startActivity()` trực tiếp thay vì `PendingIntent.send()` như lecoauto | Trace `system_server` lấy full stack trace | Sau khi sửa đúng dùng `PendingIntent.send()`, xác nhận đi **đúng y hệt code path** lecoauto (`PendingIntentRecord.sendInner` → `ActivityStartController.startActivityInPackage`) → **vẫn fail** |
| 4 | Thiếu độ trễ giữa `dontOverrideDisplayInfo` và launch | Thêm `postDelayed(150ms)` | Vẫn fail |
| 5 | Launch trên main thread thay vì background thread pool như lecoauto (`ch1.Q()`) | Dispatch qua `Thread{}.start()`, xác nhận TID khác trong logcat | Vẫn fail |

## 3. Lecoauto — kiến trúc thật (từ decompile + live trace)

### 3.1. Cài lecoauto lên chính AVD test để quan sát trực tiếp
Lecoauto ký **cùng cert test-keys AOSP** với setup của mình (SHA-256 `c8a2e9bc...`) → cài thẳng lên AVD, chạy với UID 1000 thật, **không cần resign gì cả**:
```
adb install -r -g /media/hongnguyen/data/car/bingo/apk/patch/lecoauto_1.9.0.8.apk
```
Đã đăng ký 1 tài khoản thật (`hongnguyenvan@hotmail.com`) để mở khoá tính năng PIP (yêu cầu login). Được tặng tự động **7 ngày VIP** (level=2) — xác nhận qua `/data/data/com.lecoauto/shared_prefs/lecoauto_setting.xml` (JWT token + profile JSON, key hash: `8e5f59a202e8ca0b15971a37f6164b55` = JWT, `38806cc1086a8fd0fc768a3f403e0472` = profile JSON base64×2). File setting này **không dùng để crack/mở khoá vĩnh viễn** (đã từ chối yêu cầu đó của chính user vì đó là hành vi bẻ khoá license) — chỉ dùng để hiểu cấu trúc lưu trữ.

### 3.2. Kiến trúc embedding (từ payload dex đã decrypt lúc runtime + shell APK)
- `BaseTaskView` (abstract, `com.lecoauto.widget.base`) tạo embedder theo API level:
  - **API 27/28 → `q93`** (VirtualDisplay + `IInputForwarder`, kiểu ActivityView cổ điển) ← **đúng API level target của mình**
  - API 29 → `r93`, API 30 → `t93`, 31+ → `u93` (dùng SurfaceControl-reparenting, KHÔNG áp dụng cho target của mình)
- `com.lecoauto.widget3.chlid.TaskView extends BaseTaskView` là class **đang thực sự active** (package `widget3`, không phải `widget`/`widget2` — 2 package đó là code cũ/không dùng)
- `EmbedView` (`widget3.view`) add `TaskView` như 1 child view bình thường (`addView(taskView, MATCH_PARENT)`) — xác nhận đây là real View trong layout, **không phải floating window**
- Chuỗi gọi khi launch app vào 1 pane:
  `h0(AppInfo)` → `yj3.c1(new bm(...), true)` [check VIP/login] → `bm.run()` case mặc định → **`ch1.Q(new n93(p93Var, appInfo, 0))`** → dispatch ra **background thread pool** (`ch1.E`, `ThreadPoolExecutor` thường, không có gì đặc biệt) → `n93.run()` **[NATIVE — Dex2C, không đọc được]** → thực hiện `createVirtualDisplay` + launch thật
- `p93`/`q93` **chỉ tồn tại trong shell APK dex** (không có trong payload dex đã decrypt) — mọi method quan trọng đều khai báo `native`, logic thật nằm trong `libencry.so` (ARM machine code, Dex2C-protected) — **jadx/smali không đọc được gì thêm từ đây, kể cả sau khi decrypt payload dex.**

### 3.3. EasyWindow (`com.hjq.window`) — xác nhận KHÔNG liên quan đến PIP
Qua `dumpsys window windows` khi lecoauto đang chạy PIP thật: có 1 overlay window nhỏ (94x94px, `TYPE_APPLICATION_OVERLAY`, `NOT_FOCUSABLE|NOT_TOUCH_MODAL` — đúng flags mặc định của EasyWindow) — đây là **nút "Action Bar" nhỏ nổi** (khớp setting "Action Bar Position" trong app), **không phải cơ chế nhúng app**. Kết luận: đừng tốn công tích hợp EasyWindow cho mục đích nhúng — nó chỉ hợp cho 1 nút điều khiển nhỏ nổi trên cùng, nếu cần.

### 3.4. Native string/xref analysis (Capstone disassembly của `libencry.so`, độ tin cậy khác nhau)
**High confidence** (khớp với native string + method signature, thứ tự xuất hiện trong file):
```
DisplayManager.createVirtualDisplay(name, w, h, dpi, surface, flags, callback, handler)
→ IWindowManager.dontOverrideDisplayInfo(displayId)
→ ActivityOptions.makeCustomAnimation(ctx, 0, 0).setLaunchDisplayId(displayId)
→ startActivity(...) HOẶC PendingIntent.send(...) (rẽ nhánh theo SDK version)
```
**High confidence sau cold-task A/B test:** LeCoAuto có fallback `moveStackToDisplay`/`setFocusedStack` gắn với việc scan running tasks (`aa3`/`qz0`). Trace cũ không bắt được invocation vì cửa sổ/điểm hook không bao phủ lần reattach. Event log mới cho thấy task được tạo, sau đó stack bị detach, activity relaunch và focus chuyển bằng `setFocusedStack`; task cuối cùng nằm trên VirtualDisplay dù `pm has-feature android.software.activities_on_secondary_displays` trả `false`.

## 4. Live dynamic tracing bằng Frida — phát hiện quan trọng nhất

### 4.1. Setup
- `frida-server` (17.17.0, x86_64 android) push vào `/data/local/tmp/`, chạy bằng `nohup ... &` (cần user cho phép — action nhạy cảm)
- **KHÔNG attach được trực tiếp vào process của lecoauto** — có `com.leco.encry.EncryAntiDebug.guard()` phát hiện Frida và chủ động crash app (`SecurityException: runtime threat detected`) — **đã KHÔNG cố bypass** (quyết định rõ ràng của user: dừng ở đây, không vượt qua biện pháp bảo vệ của họ)
- **Giải pháp:** hook thẳng vào `system_server` — đây là process hệ điều hành, không có anti-debug, và cho thấy đúng lý do Android quyết định redirect display cho BẤT KỲ app nào gọi (kể cả app của mình), không cần đụng đến process lecoauto
- ⚠️ Hook **quá rộng cùng lúc** (~150 method trên 6 class) từng làm **crash `system_server`** (tự phục hồi bình thường, nhưng nên hook hẹp/từng phần)
- Sau mỗi lần `system_server` restart, cần restart lại `frida-server` (nếu không sẽ báo `DeadSystemException`)

### 4.2. Kết quả trace (class `com.android.server.am.*`, API 28)
- `ActivityStackSupervisor.isCallerAllowedToLaunchOnDisplay(pid,uid,displayId,ActivityInfo)` → **luôn `true`** cho cả 2 bên
- `ActivityStackSupervisor.canPlaceEntityOnDisplay(displayId,boolean,pid,uid,ActivityInfo)` → **luôn `false`** cho CẢ 2 bên (kể cả lecoauto lúc thành công!) → **đây KHÔNG PHẢI cái chặn thật**, chỉ là red herring
- `ActivityStarter.getPreferedDisplayId(...)` → **luôn resolve đúng** display target cho cả 2 bên
- Trace cũ không quan sát được `ActivityStackSupervisor.moveStackToDisplayLocked`; kết quả này không đủ để loại đường move-stack và đã bị A/B test mới bác bỏ.
- `WindowManagerService.addWindow(...)`'s tham số `displayId` thô → **luôn là `0`** cho MỌI app window (kể cả app đã đặt đúng chỗ thành công) — xác nhận đây chỉ là hint phía client, server tự resolve display thật từ Activity/Task liên kết, không dùng tham số này

### 4.3. Kết luận từ tracing
Toàn bộ logic ở tầng `ActivityManager` (`com.android.server.am.*`) đã được verify **giống hệt nhau** giữa lecoauto (thành công) và OpenLauncher (thất bại) qua mọi checkpoint có thể hook. **Điểm khác biệt thật sự — nếu có — phải nằm ở tầng sâu hơn:**
- `com.android.server.wm.RootWindowContainer` / `DisplayContent` (chưa hook sâu, mới chỉ enumerate methods: `createDisplayContent`, `reParentWindowToken`, `reparentToOverlay`)
- Hoặc trong chính native machine code của `q93` (`libencry.so`) mà không công cụ jadx/smali nào đọc được — cần radare2/Ghidra để disassemble thật (máy hiện chỉ còn ~4.8GB trống, Ghidra cần ~1.5GB+, radare2 nhẹ hơn ~150MB — **chưa cài, user dừng lại ở bước "sử dụng jadx+smali" trước khi quyết định có cài native disassembler hay không**)

## 5. Việc KHÔNG làm (giới hạn phạm vi, đã thống nhất rõ với user)

1. **Không patch DEX để cấp VIP/viptime vĩnh viễn miễn phí** cho lecoauto — đây là bẻ khoá license, khác hẳn với việc "tìm hiểu kỹ thuật để tự làm lại"
2. **Không bypass anti-debug** (`EncryAntiDebug`) của lecoauto để Frida attach được vào process thật của họ — đã dừng lại đúng lúc phát hiện, chuyển sang hook `system_server` (an toàn, hợp lý, không đụng đến biện pháp bảo vệ của bên thứ ba)
3. Việc dùng tài khoản lecoauto thật (đăng ký hợp lệ, có VIP 7 ngày thật do server họ cấp) để test/quan sát UI là **được chấp nhận** — khác với việc giả mạo/cấp quyền không hợp lệ

## 6. Cách khôi phục bản freeform đang chạy tốt (nếu muốn dừng đào sâu VirtualDisplay)

```bash
cp docs/pip-investigation/PipWidget.freeform-working-backup.kt \
   app/src/main/java/com/openlauncher/app/ui/widget/PipWidget.kt
```
Sau đó cần thêm lại 2 dòng cấu hình Gradle cho EasyWindow (bản freeform phụ thuộc thư viện này để che caption bar):

`settings.gradle.kts` (trong `dependencyResolutionManagement.repositories`):
```kotlin
maven { url = uri("https://jitpack.io") } // com.github.getActivity:EasyWindow
```

`app/build.gradle.kts` (trong `dependencies`):
```kotlin
implementation("com.github.getActivity:EasyWindow:15.8")
```

Build lại bình thường: `./gradlew :app:assembleAospDebug`, sign bằng `apksigner` + `.claude/keys/platform.{pk8,x509.pem}`, `adb install -r -g`.

## 7. Trạng thái triển khai hiện tại

1. `PipWidget.kt` hiện là bản VirtualDisplay hoạt động trên API 28: setup display/input, launch, poll stack, move, verify và focus.
2. Cold test khi feature tắt tạo Maps stack `24` trên display `13` và YouTube stack `25` trên display `14`.
3. Touch pane trái/phải lần lượt chuyển `mFocusedStack` sang stack `24`/`25`.
4. Phần hardening còn lại là tap-exclude region, back routing, task-exit recovery và test dài hạn trên head unit thật.
