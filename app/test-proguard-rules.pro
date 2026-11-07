# Error Prone annotation metadata references a compile-time JDK type, never an Android runtime API.
-dontwarn javax.lang.model.element.Modifier

-keep class androidx.tracing.** { *; }

# Preserve shared Kotlin runtime names for instrumentation against release.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# Keep public application and Compose entry points shared with instrumentation.
-keep,allowoptimization class ua.kpi.radmonitor.** { *; }
-keep class androidx.compose.** { *; }

-keep class androidx.lifecycle.** { *; }
-keep class androidx.savedstate.** { *; }
-keep class androidx.activity.** { *; }
