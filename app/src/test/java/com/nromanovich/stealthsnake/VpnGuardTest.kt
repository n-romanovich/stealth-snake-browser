package com.nromanovich.stealthsnake

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class VpnGuardTest {

    private val ctx: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `isVpnActive returns false when active network has only wifi`() {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)

        assertFalse(VpnGuard.isVpnActive(ctx))
    }

    @Test
    fun `isVpnActive returns false if TRANSPORT_VPN still has NET_CAPABILITY_NOT_VPN`() {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)

        assertFalse(VpnGuard.isVpnActive(ctx))
    }

    @Test
    fun `isVpnActive returns true when TRANSPORT_VPN is set and NOT_VPN is absent`() {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        Shadows.shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)

        assertTrue(VpnGuard.isVpnActive(ctx))
    }

    @Test
    fun `isVpnActive returns false when activeNetwork is null`() {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        Shadows.shadowOf(cm).setDefaultNetworkActive(false)
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, null)

        assertFalse(VpnGuard.isVpnActive(ctx))
    }
}
