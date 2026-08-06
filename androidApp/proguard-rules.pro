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

# SongDto / kotlinx.serialization: the kotlinx-serialization-core (and json)
# artifacts ship consumer ProGuard rules that keep generated $serializer
# classes and companion serializers. No redundant SongDto keep needed here.
-keep class com.metrom.shared.data.SongPreset { *; }
-keep class com.metrom.shared.data.SongStore { *; }
-keep class com.metrom.shared.domain.MutePattern { *; }

-dontwarn androidx.compose.**
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
