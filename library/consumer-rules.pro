# Consumer ProGuard rules for SurveyAnalytica Clickstream Android SDK.
# These rules are included automatically in any app that depends on this library.

# Keep the public API so apps can call it without obfuscation
-keep public class com.surveyanalytica.clickstream.SAClickstream {
    public static void initialize(android.content.Context, java.lang.String, java.lang.String, java.lang.String);
    public static void track(java.lang.String, java.util.Map);
    public static void page(java.lang.String, java.util.Map);
    public static void identify(java.lang.String);
    public static void setConsent(boolean);
}

# Preserve event data classes (used for manual JSON serialization)
-keep class com.surveyanalytica.clickstream.SAClickstreamEvent { *; }
-keep class com.surveyanalytica.clickstream.SADeviceInfo { *; }
