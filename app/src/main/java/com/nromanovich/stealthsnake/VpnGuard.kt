package com.nromanovich.stealthsnake

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Verifies whether the device's active default route currently traverses a VPN interface.
 *
 * Note: App-level checks + WebViewClient interception block HTTP/HTTPS navigations and
 * subresources when VPN is down. Kernel-level enforcement for all sockets (including WebSockets
 * and system DNS) is additionally provided by Android's Always-on VPN + "Block connections without VPN".
 */
object VpnGuard {

    fun isVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork: Network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return isVpnCapabilities(caps)
    }

    internal fun isVpnCapabilities(caps: NetworkCapabilities): Boolean {
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }
}
