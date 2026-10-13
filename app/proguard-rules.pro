# Spck Reconstruction — ProGuard / R8 rules for release builds.

# ============================================================
# JavascriptInterface: all @JavascriptInterface methods must be
# kept because they are called reflectively by WebView at runtime.
# ============================================================
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep the bridge class itself so WebView can find the interface.
-keep class com.reconstruction.spck.NativeFileBridge { *; }

# ============================================================
# Android lifecycle — keep Activity subclasses and their
# standard lifecycle callbacks intact.
# ============================================================
-keep public class * extends android.app.Activity

# ============================================================
# ArchiveCallbacks interface: used via reflection/callbacks
# across the Activity/bridge boundary.
# ============================================================
-keep interface com.reconstruction.spck.NativeFileBridge$ArchiveCallbacks { *; }

# ============================================================
# Parcelable / Serializable boilerplate — standard Android.
# ============================================================
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ============================================================
# org.json — used at runtime for JSON response building.
# ============================================================
-keep class org.json.** { *; }

# ============================================================
# Suppress notes about missing kotlin and other optional deps
# that are not present in this project.
# ============================================================
-dontnote kotlin.**
-dontwarn kotlin.**
-dontnote javax.**
-dontwarn javax.**
