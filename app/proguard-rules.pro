# ── EEVDF Scheduler — R8 / ProGuard rules ────────────────────────────────────
# app/build.gradle.kts referenced this file but it did not exist, so
# assembleRelease could not complete. These are the rules this project needs.

# Keep line numbers so release stack traces are readable, but hide the original
# source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Room ─────────────────────────────────────────────────────────────────────
# Entities are constructed reflectively by generated code; DAOs are interfaces
# whose implementations are generated at build time.
-keep class com.eevdf.data.task.Task { *; }
-keep class com.eevdf.data.task.InterruptReturnEntry { *; }
-keep class com.eevdf.data.runlog.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ── Hilt / Dagger ────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
-dontwarn dagger.hilt.**

# ── Kotlin ───────────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-dontwarn kotlin.**

# Coroutines
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ── MPAndroidChart ───────────────────────────────────────────────────────────
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# ── org.json (backup / sync serialization) ───────────────────────────────────
-dontwarn org.json.**

# ── App components referenced only from the manifest ─────────────────────────
-keep class com.eevdf.app.SchedulerApplication
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.app.Activity

# ── Backup/sync field names are JSON keys ────────────────────────────────────
# Obfuscating these would break restore of any archive made by an older build.
-keepclassmembernames class com.eevdf.data.task.Task { <fields>; }
