# ProGuard rules for webView use
-keep class androidx.webkit.ProxyController { *; }
-keep class androidx.webkit.ProxyConfig { *; }
-keep class androidx.webkit.WebViewFeature { *; }
-keep class com.github.kr328.clash.BrowserActivity { *; }
-keep class com.github.kr328.clash.BrowserActivity$* { *; }
-keep class android.webkit.WebView { *; }
-keep class android.webkit.WebViewClient { *; }
-keep class android.webkit.WebChromeClient { *; }
-keep class android.webkit.WebSettings { *; }
-keep class android.webkit.ValueCallback { *; }
-keep class android.webkit.WebResourceRequest { *; }
-keep class android.webkit.WebResourceError { *; }
-keep class android.webkit.WebResourceResponse { *; }
-keep class android.webkit.ConsoleMessage { *; }
-keep class android.webkit.GeolocationPermissions { *; }
-keep class android.webkit.JsResult { *; }
-keep class android.webkit.JsPromptResult { *; }
-keep class android.webkit.JsConfirmResult { *; }
-keep class android.webkit.WebStorage { *; }
-keep class android.webkit.WebViewDatabase { *; }
-keep class android.webkit.CookieManager { *; }
-keep class android.webkit.CookieSyncManager { *; }
-keep class android.webkit.HttpAuthHandler { *; }
-keep class android.webkit.SslErrorHandler { *; }
-keep class android.webkit.ClientCertRequest { *; }
-keep class android.webkit.WebBackForwardList { *; }
-keep class android.webkit.WebHistoryItem { *; }
-keep class android.webkit.DownloadListener { *; }
-keep class android.webkit.WebIconDatabase { *; }
-keep class android.webkit.WebViewFragment { *; }
-keep class android.webkit.WebViewProvider { *; }
-keep class android.webkit.WebViewDelegate { *; }
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void *(android.webkit.WebView, int);
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}