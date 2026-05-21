# ProGuard rules for the SurveyAnalytica Clickstream library module.
# These rules apply when the library itself is minified (rare for library modules).
# Consumer rules (consumer-rules.pro) apply to apps that depend on this library.

# Keep the public SDK entry point
-keep public class com.surveyanalytica.clickstream.SAClickstream { public *; }
