# JNI entry points must keep their names: the native library resolves them by symbol.
-keep class com.example.janggiai.engine.fsf.FairyStockfishNative { *; }
-keepclasseswithmembernames class * { native <methods>; }
