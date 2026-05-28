package com.razban.app.ui

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.razban.app.R
import com.razban.app.config.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

/**
 * Lets the user pick which apps' traffic enters the tunnel (exclude list — all
 * apps tunnel except the checked ones). On Android per-app entry is a binary
 * whitelist XOR blacklist (VpnService limitation); the finer direct/proxy/dpi
 * distinction happens inside the tunnel via package_name route rules in the
 * config. We expose the blacklist ("these apps bypass the tunnel entirely"),
 * which is the most common user need.
 */
class PerAppActivity : AppCompatActivity() {

    private val selected = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.per_app_title)
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PerAppActivity)
        }
        setContentView(rv)
        selected.addAll(ConfigStore.excludePackages(this))

        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { loadApps() }
            rv.adapter = Adapter(apps)
        }
    }

    override fun onPause() {
        super.onPause()
        ConfigStore.setExcludePackages(this, selected)
    }

    private data class AppRow(val label: String, val pkg: String)

    private fun loadApps(): List<AppRow> {
        val pm = packageManager
        return pm.getInstalledApplications(0)
            .filter { it.packageName != packageName }
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || hasInternet(it) }
            .map { AppRow(pm.getApplicationLabel(it).toString(), it.packageName) }
            .sortedBy { it.label.lowercase() }
    }

    private fun hasInternet(info: ApplicationInfo): Boolean = try {
        packageManager.getPackageInfo(info.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
    } catch (_: Exception) { false }

    private inner class Adapter(val items: List<AppRow>) : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_app, parent, false)
            return VH(v)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = items[position]
            holder.label.text = row.label
            holder.pkg.text = row.pkg
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = selected.contains(row.pkg)
            holder.check.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selected.add(row.pkg) else selected.remove(row.pkg)
            }
            holder.itemView.setOnClickListener { holder.check.toggle() }
        }
    }

    private class VH(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.app_label)
        val pkg: TextView = v.findViewById(R.id.app_pkg)
        val check: CheckBox = v.findViewById(R.id.app_check)
    }
}
