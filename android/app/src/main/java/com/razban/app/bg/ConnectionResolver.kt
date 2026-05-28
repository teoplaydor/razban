package com.razban.app.bg

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import io.nekohasekai.libbox.ConnectionOwner
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Resolves which app (UID → package names) owns a connection, so the core can
 * apply `package_name` route rules — the Android sibling of the desktop
 * `process_name` rules (invariant #1's per-app cascade). Needs API 29's
 * getConnectionOwnerUid; on older devices the core falls back to /proc scanning
 * (we return useProcFS()==true there and this method isn't used).
 */
object ConnectionResolver {

    fun find(
        context: Context,
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        val owner = ConnectionOwner()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return owner
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return owner
            val local = InetSocketAddress(InetAddress.getByName(sourceAddress), sourcePort)
            val remote = InetSocketAddress(InetAddress.getByName(destinationAddress), destinationPort)
            val uid = cm.getConnectionOwnerUid(ipProtocol, local, remote)
            if (uid >= 0) {
                owner.userId = uid
                val pkgs = context.packageManager.getPackagesForUid(uid)
                if (!pkgs.isNullOrEmpty()) {
                    owner.userName = pkgs[0]
                    owner.setAndroidPackageNames(StringList(pkgs.toList()))
                }
            }
        } catch (_: Exception) {}
        return owner
    }
}
