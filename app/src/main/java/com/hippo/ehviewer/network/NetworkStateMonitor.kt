package com.hippo.ehviewer.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.hippo.ehviewer.EhApplication
import com.hippo.ehviewer.R

/**
 * 监听 VPN/网络状态变化。
 * 当 VPN 开关或切换时，清空 OkHttp 连接池并重建 client，
 * 使后续请求走新 VPN 的网络路径。
 */
class NetworkStateMonitor(private val context: Context) {

    companion object {
        private const val TAG = "NetworkStateMonitor"
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var lastVpnActive = isVpnActive()
    private var lastActiveNetwork: Network? = connectivityManager.activeNetwork

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            checkVpnChange()
        }

        override fun onLost(network: Network) {
            checkVpnChange()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            checkVpnChange()
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        Log.d(TAG, "NetworkStateMonitor started")
    }

    fun stop() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
        Log.d(TAG, "NetworkStateMonitor stopped")
    }

    private fun isVpnActive(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun checkVpnChange() {
        val currentNetwork = connectivityManager.activeNetwork
        val currentVpnActive = isVpnActive()
        val networkChanged = currentNetwork != lastActiveNetwork
        val vpnStateChanged = currentVpnActive != lastVpnActive

        // 只在 VPN 相关变化时 reset：
        // 1. VPN 开/关状态变化
        // 2. activeNetwork 变化，且当前或之前有 VPN（即 VPN 间切换）
        val shouldReset = vpnStateChanged ||
            (networkChanged && (currentVpnActive || lastVpnActive))

        lastActiveNetwork = currentNetwork
        lastVpnActive = currentVpnActive

        if (shouldReset) {
            Log.i(TAG, "VPN state changed (vpn=$currentVpnActive, networkChanged=$networkChanged), resetting OkHttp clients")
            EhApplication.resetOkHttpClients()
            val msg = if (currentVpnActive) context.getString(R.string.vpn_connected_refreshed)
                      else context.getString(R.string.vpn_disconnected_refreshed)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
