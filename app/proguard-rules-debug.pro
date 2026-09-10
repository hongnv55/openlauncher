# Layered on top of proguard-rules.pro, for the aospDebug variant only.
#
# R8 runs there purely to tree-shake. material-icons-extended is 13.7MB of DEX
# against the app's own 675KB, and four of its five icon styles have no
# references at all; nothing about how icons are imported changes that, since
# the library ships each one as a separate class. Shaking them out takes the
# variant from ~25MB to a couple of MB.
#
# Names are kept, though. This is the variant used to diagnose the PIP embedder,
# and that is done entirely by reading its logcat output (see the TaskEmbedder
# tags in PipWidget.kt). Renamed frames would make those traces useless, and
# obfuscation is not where the size saving comes from anyway.
-dontobfuscate

# Every reflective target in the app is a framework class that is not in the
# APK — android.app.ActivityManager / StatusBarManager / ITaskStackListener,
# android.view.WindowManagerGlobal, android.hardware.input.InputManager — plus
# android.app.TaskStackListener, which comes from the compileOnly stub jar and
# resolves against the device boot image at runtime.
-dontwarn android.app.**
-dontwarn android.view.**
