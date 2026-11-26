package com.gunyah.launcher.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import java.io.File

/**
 * WiFi ⇄ 移动数据切换后，虚拟机（gunytap0 + NAT）还挂着旧的上行网卡，
 * 表现为「手机有网、虚拟机没网」，只能停/启虚拟机才会恢复。
 *
 * 这里监听系统默认网络变化，直接用新上行网卡重写 SNAT 与策略路由，
 * 让 guest 立刻跟上，不必重启虚拟机。
 */
object NetWatcher {
    private const val TAG = "NetWatcher"
    private var cm: ConnectivityManager? = null
    private var cb: ConnectivityManager.NetworkCallback? = null

    @Volatile private var script: File? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 刷新脚本：由 GunyahManager 在启动虚拟机时落盘。 */
    fun refreshScript(ctx: Context): File = File(GunyahManager.privateDir(ctx), "net-refresh.sh")

    fun start(ctx: Context, scriptFile: File) {
        script = scriptFile
        if (cb != null) return
        val mgr = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        cm = mgr
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val iface = runCatching {
                    cm?.getLinkProperties(network)?.interfaceName
                }.getOrNull()
                Log.i(TAG, "default network changed -> $network iface=$iface")
                // 新网络刚可用时路由还没落表，立刻刷会拿到旧网卡；
                // 分几次重写，既快又稳。
                for (delay in longArrayOf(400L, 1500L, 4000L, 8000L)) {
                    handler.postDelayed({ apply(iface) }, delay)
                }
            }
            override fun onLost(network: Network) {
                Log.i(TAG, "default network lost -> $network")
            }
        }
        runCatching { mgr.registerDefaultNetworkCallback(callback) }
            .onSuccess { cb = callback }
            .onFailure { Log.w(TAG, "register failed", it) }
    }

    fun stop() {
        cb?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        cb = null
        cm = null
        script = null
    }

    /** 立刻按当前默认网络重写 NAT；虚拟机没在跑时脚本会自己退出。 */
    fun apply(iface: String? = null) {
        val f = script ?: return
        if (!f.isFile) return
        val arg = iface?.let { " " + it } ?: ""
        Thread {
            runCatching {
                val p = ProcessBuilder(
                    "su", "-c", "sh ${f.absolutePath}$arg"
                ).redirectErrorStream(true).start()
                p.inputStream.bufferedReader().use { r -> r.readText() }
                p.waitFor()
            }.onFailure { Log.w(TAG, "apply failed", it) }
        }.start()
    }
}
