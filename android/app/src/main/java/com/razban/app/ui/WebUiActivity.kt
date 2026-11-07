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
import com.razban.app.config.ProfileSwitch
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
    private var profileSpinner: android.widget.Spinner? = null
    private var profileLabel: android.widget.TextView? = null
    private var suppressProfileCallback = false

    private val openProfileDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
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
        setContentView(wrapWithChrome(web))
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
        refreshProfileBar()
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
                    refreshProfileBar()
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
        val wantNative = com.razban.app.BuildConfig.DEBUG || wantDiag
        if (!wantNative) return webView
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        root.addView(buildProfileBar())
        if (wantDiag) root.addView(buildDiagBar())
        root.addView(webView, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))
        refreshProfileBar()
        refreshDiagStatus()
        webView.post(object : Runnable {
            override fun run() {
                refreshProfileBar()
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

    private fun buildProfileBar(): android.view.View {
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(32, 18, 24, 18)
            setBackgroundColor(0xFF152033.toInt())
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val label = android.widget.TextView(this).apply {
            setTextColor(0xFFE8E8E8.toInt())
            textSize = 13f
            text = "Профиль: не выбран"
        }
        val spinner = android.widget.Spinner(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val fileBtn = android.widget.Button(this).apply {
            text = "Из файла"
            textSize = 12f
            isAllCaps = false
            setOnClickListener {
                openProfileDocument.launch(arrayOf(
                    "application/json",
                    "text/plain",
                    "text/*",
                    "application/octet-stream",
                ))
            }
        }
        profileLabel = label
        profileSpinner = spinner
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (suppressProfileCallback) return
                val name = parent?.getItemAtPosition(position) as? String ?: return
                if (name.isBlank() || name == "Нет профилей") return
                if (name == ProfileStore.activeName(this@WebUiActivity)) return
                if (ProfileSwitch.blockedByTunnel(RazbanVpnService.lastStatus.name)) {
                    android.widget.Toast.makeText(
                        this@WebUiActivity,
                        "Сначала отключите VPN, чтобы сменить профиль",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    refreshProfileBar()
                    return
                }
                val ok = ProfileStore.select(this@WebUiActivity, name)
                if (ok && this@WebUiActivity::bridge.isInitialized) {
                    bridge.pushEvent("servers.changed", true)
                }
                refreshProfileBar()
                if (!ok) {
                    android.widget.Toast.makeText(this@WebUiActivity, "Не удалось выбрать профиль", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        bar.addView(label)
        bar.addView(spinner)
        bar.addView(fileBtn)
        return bar
    }

    private fun refreshProfileBar() {
        val spinner = profileSpinner ?: return
        val names = ProfileStore.list(this).map { it.name }
        val active = ProfileStore.activeName(this)
        val display = if (names.isEmpty()) listOf("Нет профилей") else names
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, display)
        suppressProfileCallback = true
        spinner.adapter = adapter
        val idx = names.indexOf(active)
        if (idx >= 0) spinner.setSelection(idx)
        suppressProfileCallback = false
        val kind = ProfileStore.list(this).firstOrNull { it.name == active }?.kind
        profileLabel?.text = when {
            active != null && kind != null -> "Профиль: $active"
            active != null -> "Профиль: $active"
            else -> "Профиль: не выбран"
        }
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
            refreshProfileBar()
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
