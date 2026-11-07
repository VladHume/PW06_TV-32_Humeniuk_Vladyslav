-keepattributes Signature,InnerClasses,EnclosingMethod
-keep class ua.kpi.radmonitor.domain.Measurement { *; }
-keep class ua.kpi.radmonitor.data.LoginRequest { *; }
-keep class ua.kpi.radmonitor.data.Session { *; }

-keep class ua.kpi.radmonitor.data.RegistrationRequest { *; }

# Android instrumentation shares tracing classes with the optimized application.
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
