# حفظ نام متدهای مورد استفاده توسط WebView JavaScript
-keepattributes *JavascriptInterface*
-keepclassmembers class ir.messenger.app.MainActivity$JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class ir.messenger.app.MainActivity$JsBridge { *; }
