# Keep JS interfaces if any are added later; none ship in this app today.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
