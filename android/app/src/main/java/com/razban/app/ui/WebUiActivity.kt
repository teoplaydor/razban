package com.razban.app.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.razban.app.bg.RazbanVpnService
import com.razban.app.bridge.WebUiBridge
import com.razban.app.config.ConfigStore
import org.json.JSONObject

/**
 * Hosts the SAME React UI as the desktop, inside a WebView. The dist is bundled
 * in assets/webui and served over https via WebViewAssetLoader (so ES modules
 * load — they're blocked over file://). A document-start script installs the
 * window.chrome.webview shim the UI's bridge expects, routed to [WebUiBridge].
 */
class WebUiActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var bridge: WebUiBridge

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) startVpn()
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val s = i?.getStringExtra(RazbanVpnService.EXTRA_STATUS)
            val ui = when (s) {
                "Started" -> "connected"; "Starting" -> "connecting"
                "Stopping" -> "disconnecting"; else -> "disconnected"
            }
            bridge.pushEvent("vpn.state", ui)
        }
    }

    @Suppress("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        web = WebView(this)
        setContentView(web)
        WebView.setWebContentsDebuggingEnabled(true)

        bridge = WebUiBridge(applicationContext, onConnect = { runOnUiThread { requestConnect() } },
            onDisconnect = { runOnUiThread { stopVpn() } })
        // Guard with `&&`: a native push (e.g. the auto-connect vpn.state) can
        // fire before the document-start shim defined __razbanDeliver, especially
        // on old WebViews — without the guard that logs "is not a function" and
        // the event is lost. The App.tsx status poll recovers any missed push.
        bridge.deliver = { json -> web.post { web.evaluateJavascript("window.__razbanDeliver && window.__razbanDeliver(${JSONObject.quote(json)})", null) } }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
        }
        web.addJavascriptInterface(JsTransport(bridge), "AndroidBridgeNative")

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                // appassets.androidplatform.net is a virtual local origin — safe.
                handler?.proceed()
            }
        }

        // Install the chrome.webview shim BEFORE the app's scripts run.
        val shim = SHIM_JS
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, shim, setOf("https://appassets.androidplatform.net"))
        } else {
            // Fallback: inject as early as possible.
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    view?.evaluateJavascript(shim, null)
                }
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) { handler?.proceed() }
            }
        }

        web.loadUrl("https://appassets.androidplatform.net/webui/index.html")

        // Instant auto-connect. A default config is bundled, so on launch we
        // bring the tunnel up by ourselves. CRITICAL: on the FIRST launch
        // VpnService.prepare() returns a consent Intent — we MUST launch it to
        // show Android's VPN permission dialog (otherwise the tunnel never
        // establishes and every protocol shows "no connection"). After the user
        // taps OK once, prepare() returns null and subsequent launches connect
        // silently.
        web.postDelayed({
            if (RazbanVpnService.lastStatus == RazbanVpnService.Status.Stopped &&
                ConfigStore.hasConfig(this)) {
                val prep = VpnService.prepare(this)
                if (prep == null) startVpn() else try { vpnConsent.launch(prep) } catch (_: Exception) {}
            }
        }, 1200)

        // In-app self-update check (private channel). A few seconds after launch
        // so it never competes with connecting.
        lifecycleScope.launch {
            kotlinx.coroutines.delay(4000)
            com.razban.app.update.AndroidUpdater.checkAndPrompt(this@WebUiActivity)
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(this, statusReceiver,
            IntentFilter(RazbanVpnService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        // Reliable import path that needs no UI rebuild: if there is no config
        // yet and the clipboard holds a sing-box config JSON (our exported
        // bundle), auto-import it. Runs only while config is absent, so the
        // clipboard isn't re-read (and no repeated "pasted" toast) afterwards.
        web.postDelayed({ maybeImportConfigFromClipboard() }, 700)
    }

    private fun maybeImportConfigFromClipboard() {
        if (ConfigStore.hasConfig(this)) return
        try {
            val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: return
            if (!text.startsWith("{") || !text.contains("\"outbounds\"")) return
            ConfigStore.importConfig(this, text)
            android.widget.Toast.makeText(this, "Конфигурация импортирована из буфера", android.widget.Toast.LENGTH_LONG).show()
            bridge.pushEvent("vpn.log", "Конфигурация импортирована из буфера")
            bridge.pushEvent("servers.changed", true)
        } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun requestConnect() {
        if (!ConfigStore.hasConfig(this)) {
            // No config yet — tell the UI so it can prompt for import.
            bridge.pushEvent("vpn.log", "Нет конфигурации — импортируйте sing-box конфиг")
            bridge.pushEvent("vpn.state", "disconnected")
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) vpnConsent.launch(prepare) else startVpn()
    }

    private fun startVpn() {
        ContextCompat.startForegroundService(this, Intent(this, RazbanVpnService::class.java))
        bridge.pushEvent("vpn.state", "connecting")
    }

    private fun stopVpn() {
        startService(Intent(this, RazbanVpnService::class.java).setAction(RazbanVpnService.ACTION_STOP))
        bridge.pushEvent("vpn.state", "disconnecting")
    }

    /** Thin @JavascriptInterface holder (keeps the annotated surface minimal). */
    class JsTransport(private val b: WebUiBridge) {
        @android.webkit.JavascriptInterface
        fun postMessage(raw: String) = b.postMessage(raw)
    }

    companion object {
        private const val SHIM_JS = """
            (function(){
              if (window.chrome && window.chrome.webview) return;
              var listeners = [];
              window.chrome = { webview: {
                postMessage: function(m){ try { AndroidBridgeNative.postMessage(typeof m === 'string' ? m : JSON.stringify(m)); } catch(e){} },
                addEventListener: function(t, cb){ if (t === 'message') listeners.push(cb); },
                removeEventListener: function(t, cb){ var i = listeners.indexOf(cb); if (i >= 0) listeners.splice(i,1); }
              }};
              window.__razbanDeliver = function(s){ var ev = { data: s }; for (var i=0;i<listeners.length;i++){ try { listeners[i](ev); } catch(e){ console.error(e); } } };
            })();
        """
    }
}
