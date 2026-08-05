# Metrom release shrink/obfuscate rules.
# Audio timing uses plain Kotlin + AudioTrack — no JNI. Keep public app entry
# points, enums persisted by ordinal, and resource-name lookups.

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn javax.annotation.**

# Application / activity / FGS entry points
-keep class com.metrom.app.MetromApplication { <init>(); }
-keep class com.metrom.app.MainActivity { <init>(); }
-keep class com.metrom.app.PlaybackService { <init>(); }

# Enums persisted via ordinal / .entries in prefs + SongStore JSON
-keepclassmembers enum com.metrom.app.engine.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}
-keep enum com.metrom.app.engine.ClickTone
-keep enum com.metrom.app.engine.AccentNote
-keep enum com.metrom.app.engine.Subdivision
-keep enum com.metrom.app.engine.SwingFeel
-keep enum com.metrom.app.engine.BeatAccent
-keep enum com.metrom.app.SessionPhase

# Song presets (manual JSON — keep model + store from aggressive pruning)
-keep class com.metrom.app.data.SongPreset { *; }
-keep class com.metrom.app.data.SongStore { *; }
-keep class com.metrom.app.MutePattern { *; }

# Engine + sample loaders (getIdentifier looks up raw/asset names by string)
-keep class com.metrom.app.engine.MetronomeEngine { *; }
-keep class com.metrom.app.engine.ClickSynthesizer { *; }
-keep class com.metrom.app.engine.WavSampleLoader { *; }
-keep class com.metrom.app.engine.ChugSamples { *; }
-keep class com.metrom.app.engine.VisualOutputLatency { *; }

# Compose / coroutines — standard safe defaults
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
