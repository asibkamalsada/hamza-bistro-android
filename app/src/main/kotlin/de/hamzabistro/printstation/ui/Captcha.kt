package de.hamzabistro.printstation.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The Cloudflare Turnstile check the site puts in front of signing in.
 * Supabase refuses a password sign-in without its token, so the app shows
 * the same widget, in a WebView that believes it is on the site ([origin],
 * a host on the widget's allowlist) and can do nothing else:
 *
 *   * it shows one page, written here, and cannot navigate away;
 *   * no file or content access, no pop-ups, no location;
 *   * the page cannot call into the app — the app reads the token out,
 *     every half second, and nothing flows the other way;
 *   * its cookies and storage are wiped when it goes.
 *
 * A token is good for one sign-in: [round] makes a fresh widget for the next.
 */
@SuppressLint("SetJavaScriptEnabled") // Turnstile is JavaScript; see above for what the page may do.
@Composable
fun Captcha(siteKey: String, origin: String, lang: String, round: Int, onToken: (String?) -> Unit, modifier: Modifier = Modifier) {
    val currentOnToken by rememberUpdatedState(onToken)
    key(round) {
        var view by remember { mutableStateOf<WebView?>(null) }
        AndroidView(
            modifier = modifier.fillMaxWidth().height(72.dp),
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setGeolocationEnabled(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    webViewClient =
                        object : WebViewClient() {
                            // The widget lives in its own frame; this page stays put.
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                                request.isForMainFrame
                        }
                    loadDataWithBaseURL(origin, page(siteKey, lang), "text/html", "utf-8", null)
                    view = this
                }
            },
            onRelease = { webView ->
                webView.stopLoading()
                webView.destroy()
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
            },
        )
        LaunchedEffect(Unit) {
            currentOnToken(null)
            while (true) {
                delay(500)
                val token = view?.read("window.hbToken || ''")
                currentOnToken(token?.takeIf { it.isNotEmpty() })
            }
        }
    }
}

private suspend fun WebView.read(script: String): String = suspendCancellableCoroutine { done ->
    evaluateJavascript(script) { result ->
        // The answer comes JSON-encoded: "\"token\"".
        done.resume(runCatching { JSONTokener(result).nextValue() as? String }.getOrNull() ?: "")
    }
}

private fun page(siteKey: String, lang: String): String =
    """
    <!doctype html>
    <html><head>
    <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <style>html,body{margin:0;background:transparent}body{display:flex;justify-content:center}</style>
    <script>
      window.hbToken = '';
      window.hbReady = function () {
        turnstile.render('#captcha', {
          sitekey: ${JSONObject.quote(siteKey)},
          language: ${JSONObject.quote(lang)},
          theme: 'auto',
          callback: function (token) { window.hbToken = token; },
          'expired-callback': function () { window.hbToken = ''; },
          'error-callback': function () { window.hbToken = ''; }
        });
      };
    </script>
    <script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit&onload=hbReady" async defer></script>
    </head><body><div id="captcha"></div></body></html>
    """
        .trimIndent()
