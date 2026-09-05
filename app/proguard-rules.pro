# ============================================================================
# Open Screen Recorder - R8 & ProGuard Aggressive Optimization Rules
# ============================================================================

# Enable R8 aggressive optimization passes and inlining
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''

# Optimize code for maximum execution speed
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*

# Keep source file and line numbers for clean stack traces
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable,AnnotationDefault,InnerClasses,EnclosingMethod,Signature

# ============================================================================
# Android Core & App Specific Rules
# ============================================================================

# Keep Android components declared in Manifest
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.app.Application
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.service.quicksettings.TileService

# Keep App Data Models
-keepclassmembers class com.openscreenrecorder.app.VideoFile { *; }

# Keep ViewBinding generated classes
-keep class com.openscreenrecorder.app.databinding.** { *; }

# ============================================================================
# Jetpack Compose Optimizations
# ============================================================================

-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
    @androidx.compose.runtime.ReadOnlyComposable <methods>;
}

# ============================================================================
# Kotlin Coroutines & Lifecycle
# ============================================================================

-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ============================================================================
# Media & Hardware Codec Safety Rules
# ============================================================================

-keepclassmembers class android.media.** { *; }

# ============================================================================
# Suppress benign warnings
# ============================================================================
-dontwarn androidx.**
-dontwarn com.google.android.material.**
