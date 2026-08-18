# Metrom release shrink/obfuscate rules.

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn javax.annotation.**

-keep class com.metrom.app.MetromApplication { <init>(); }
-keep class com.metrom.app.MainActivity { <init>(); }
-keep class com.metrom.app.PlaybackService { <init>(); }

-keepclassmembers enum com.metrom.shared.domain.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}
-keep enum com.metrom.shared.domain.ClickTone
-keep enum com.metrom.shared.domain.AccentNote
-keep enum com.metrom.shared.domain.Subdivision
-keep enum com.metrom.shared.domain.SwingFeel
-keep enum com.metrom.shared.domain.BeatAccent
-keep enum com.metrom.shared.domain.SessionPhase

# SongDto serializers: covered by kotlinx-serialization consumer ProGuard rules.
# No keeps for SongPreset / SongStore / MutePattern — nothing reflects on them.

-keep class com.garmin.android.connectiq.** { *; }
-dontwarn com.garmin.android.connectiq.**

-dontwarn androidx.compose.**
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
