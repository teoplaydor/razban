package com.razban.app.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.razban.app.R
import com.razban.app.bg.RazbanVpnService
import com.razban.app.config.ConfigStore
import com.razban.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) reallyStart()
        else toast(getString(R.string.err_no_permission))
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            render(intent?.getStringExtra(RazbanVpnService.EXTRA_STATUS))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.connectButton.setOnClickListener { onConnectClicked() }
        binding.importButton.setOnClickListener { importFromClipboard() }
        binding.perAppButton.setOnClickListener {
            startActivity(Intent(this, PerAppActivity::class.java))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        render(null)
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, statusReceiver,
            IntentFilter(RazbanVpnService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        render(null)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }

    private fun onConnectClicked() {
        if (!ConfigStore.hasConfig(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.no_config_title)
                .setMessage(R.string.no_config_body)
                .setPositiveButton(R.string.action_import) { _, _ -> importFromClipboard() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) vpnConsent.launch(prepare) else reallyStart()
    }

    private fun reallyStart() {
        ContextCompat.startForegroundService(this, Intent(this, RazbanVpnService::class.java))
        render("Starting")
    }

    private fun stop() {
        startService(Intent(this, RazbanVpnService::class.java).setAction(RazbanVpnService.ACTION_STOP))
        render("Stopping")
    }

    private fun importFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty() || !text.startsWith("{")) {
            toast(getString(R.string.err_clipboard_empty))
            return
        }
        try {
            ConfigStore.importConfig(this, text)
            toast(getString(R.string.import_ok))
            render(null)
        } catch (e: Exception) {
            toast(getString(R.string.err_import, e.message ?: ""))
        }
    }

    private fun render(forced: String?) {
        val state = forced ?: if (ConfigStore.hasConfig(this)) "Ready" else "NoConfig"
        val connected = state == "Started"
        binding.statusText.text = when (state) {
            "Started" -> getString(R.string.status_connected)
            "Starting" -> getString(R.string.status_connecting)
            "Stopping" -> getString(R.string.status_disconnecting)
            "NoConfig" -> getString(R.string.status_no_config)
            else -> getString(R.string.status_ready)
        }
        binding.connectButton.text =
            getString(if (connected) R.string.action_disconnect else R.string.action_connect)
        binding.connectButton.setOnClickListener { if (connected) stop() else onConnectClicked() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
