# JNI: the native library looks these up by name (also in engine/consumer-rules.pro).
-keep class com.example.janggiai.engine.fsf.FairyStockfishNative { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Enum ids are read via reflection-free code, but keep enum names for SavedState round-trips.
-keepclassmembers enum com.example.janggiai.** { *; }

# Coroutines / Compose need nothing special beyond the default optimize rules.
-dontwarn org.jetbrains.annotations.**
