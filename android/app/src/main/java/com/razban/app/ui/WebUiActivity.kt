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
import com.razban.app.diag.DiagnosticReporter
import com.razban.app.config.ConfigStore
import com.razban.app.config.ProfileImport
import com.razban.app.config.ProfileStore
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
    private var diagStatusView: android.widget.TextView? = null

    private val openProfileDocument = registerForActivityResult(OpenAnyDocument()) { uri ->
        if (uri == null) {
            android.widget.Toast.makeText(this, "Импорт файла отменён", android.widget.Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        finishDocumentImport(uri)
    }

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) startVpn()
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val s = i?.getStringExtra(RazbanVpnService.EXTRA_STATUS)
            val ui = i?.getStringExtra(RazbanVpnService.EXTRA_UI_STATE) ?: when (s) {
                "Started" -> "connected"; "Starting" -> "connecting"
                "Stopping" -> "disconnecting"; else -> "disconnected"
            }
            // Direct profiles must not report connected from TUN-up alone.
            val mapped = if (s == "Started" && com.razban.app.config.ProfileStore.isDirectActive(this@WebUiActivity)) {
                i?.getStringExtra(RazbanVpnService.EXTRA_UI_STATE)
                    ?: com.razban.app.direct.VpnUiState.publicLabel(
                        s ?: "Stopped",
                        RazbanVpnService.lastChannelState,
                        true,
                    )
            } else ui
            bridge.pushEvent("vpn.state", mapped)
        }
    }

    @Suppress("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        web = WebView(this)
        setContentView(wrapWithChrome(web))
        WebView.setWebContentsDebuggingEnabled(true)

        bridge = WebUiBridge(applicationContext, onConnect = { runOnUiThread { requestConnect() } },
            onDisconnect = { runOnUiThread { stopVpn() } },
            onImportDocument = { runOnUiThread { launchProfileDocumentPicker() } })
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
        handleLaunchImport(intent)

        // NO auto-connect. The tunnel must come up ONLY on an explicit user tap of
        // the Connect button (which arrives as the `onConnect` callback →
        // requestConnect → consent → startVpn). Previously this opened the VPN
        // (and even popped the consent dialog) ~1.2s after launch, which the user
        // explicitly does NOT want — opening the app must never start the VPN.
        // (Desktop has the same invariant — see CLAUDE.md #7.)

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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchImport(intent)
    }

    private fun maybeImportConfigFromClipboard() {
        if (ConfigStore.hasConfig(this)) return
        try {
            val cm = getSystemService(android.content.ClipboardManager::class.java) ?: return
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: return
            if (!text.startsWith("{")) return
            if (text.contains("\"razbanProfile\"")) {
                val imported = ProfileStore.importPayload(this, text)
                if (imported.ok) {
                    android.widget.Toast.makeText(this, "Импортирован профиль ${imported.name}", android.widget.Toast.LENGTH_LONG).show()
                    bridge.pushEvent("servers.changed", true)
                }
                return
            }
            if (!text.contains("\"outbounds\"")) return
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

    private fun wrapWithChrome(webView: WebView): android.view.View {
        val wantDiag = com.razban.app.BuildConfig.DIAGNOSTIC_UPLOAD_UI
        if (!wantDiag) return webView
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        root.addView(buildDiagBar())
        root.addView(webView, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        refreshDiagStatus()
        webView.post(object : Runnable {
            override fun run() {
                refreshDiagStatus()
                webView.postDelayed(this, 2000)
            }
        })
        return root
    }

    private fun buildDiagBar(): android.view.View {
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(32, 20, 32, 20)
            setBackgroundColor(0xFF1B2A1C.toInt())
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val status = android.widget.TextView(this).apply {
            setTextColor(0xFFE8E8E8.toInt())
            textSize = 13f
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val toggle = android.widget.Switch(this).apply {
            text = "Диагностика"
            setTextColor(0xFFE8E8E8.toInt())
            isChecked = DiagnosticReporter.isEnabled(this@WebUiActivity)
            setOnCheckedChangeListener { _, checked ->
                DiagnosticReporter.setEnabled(this@WebUiActivity, checked)
                refreshDiagStatus()
            }
        }
        diagStatusView = status
        bar.addView(status)
        bar.addView(toggle)
        return bar
    }

    fun launchProfileDocumentPicker() {
        openProfileDocument.launch(Unit)
    }

    private fun handleLaunchImport(intent: android.content.Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val uri = when (action) {
            android.content.Intent.ACTION_VIEW -> intent.data
            android.content.Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
                }
            }
            else -> null
        }
        val extraText = if (action == android.content.Intent.ACTION_SEND) {
            intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
        } else null
        when {
            uri != null -> finishDocumentImport(uri)
            !extraText.isNullOrBlank() -> applyImportResult(ProfileImport.fromText(this, extraText), "отправки")
        }
    }

    private fun finishDocumentImport(uri: android.net.Uri) {
        applyImportResult(ProfileImport.fromUri(this, uri), "файла")
    }

    private fun applyImportResult(result: ProfileStore.ImportResult, source: String) {
        if (result.ok) {
            android.widget.Toast.makeText(this, "Импортирован профиль ${result.name}", android.widget.Toast.LENGTH_LONG).show()
            if (this::bridge.isInitialized) bridge.pushEvent("servers.changed", true)
        } else {
            android.widget.Toast.makeText(
                this,
                "Не удалось импортировать из $source (${result.error ?: "ошибка"})",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun refreshDiagStatus() {
        val view = diagStatusView ?: return
        val st = DiagnosticReporter.statusJson(this)
        val enabled = st.optBoolean("enabled")
        val queued = st.optInt("queued")
        val status = st.optString("status")
        val err = if (st.isNull("lastError")) "" else st.optString("lastError")
        view.text = when {
            !enabled -> "Диагностика: выкл"
            err.isNotEmpty() && status != "ok" -> "Диагностика: $status · очередь $queued"
            else -> "Диагностика: $status · очередь $queued"
        }
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

/**
 * SAF picker that must not filter by MIME: Samsung My Files hides .json when
 * EXTRA_MIME_TYPES is set to application/json + text/plain. Type is *\/* with
 * no EXTRA_MIME_TYPES; content is validated after the user picks a file.
 */
class OpenAnyDocument : ActivityResultContract<Unit, android.net.Uri?>() {
    override fun createIntent(context: android.content.Context, input: Unit): android.content.Intent {
        return android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
    }

    override fun parseResult(resultCode: Int, intent: android.content.Intent?): android.net.Uri? {
        return intent?.takeIf { resultCode == android.app.Activity.RESULT_OK }?.data
    }
}
