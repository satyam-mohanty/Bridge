package com.bridge.android.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.bridge.android.crypto.KeyStoreManager
import com.bridge.android.protocol.BridgeProtocol
import com.bridge.android.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

class DiscoveryEngine(private val context: Context) {

    suspend fun discoverMacIp(keyStoreManager: KeyStoreManager, checkLastIp: Boolean = false): String? = withContext(Dispatchers.IO) {
        LogManager.i("Starting Mac discovery sequence")

        // 1. Last known Mac IP (only if explicitly requested)
        if (checkLastIp) {
            val lastIp = keyStoreManager.getLastKnownIp()
            if (!lastIp.isNullOrBlank()) {
                LogManager.i("Discovery Step 1: Trying last known IP: $lastIp")
                if (canConnectToPort(lastIp, BridgeProtocol.DEFAULT_PORT, 400)) {
                    LogManager.i("Discovery Step 1 succeeded: Connected to $lastIp")
                    return@withContext lastIp
                }
            }
        }

        // 2. mDNS / NSD service _bridge._tcp
        LogManager.i("Discovery Step 2: Searching via mDNS/NSD (_bridge._tcp)")
        val nsdIp = discoverViaNsd(3000)
        if (nsdIp != null) {
            LogManager.i("Discovery Step 2 succeeded: Found Mac via mDNS at $nsdIp")
            keyStoreManager.saveLastKnownIp(nsdIp)
            return@withContext nsdIp
        }

        // 3. UDP Broadcast Beacon (48250)
        LogManager.i("Discovery Step 3: Listening for UDP broadcast beacon on 48250")
        val beaconIp = listenForUdpBeacon(4000)
        if (beaconIp != null) {
            LogManager.i("Discovery Step 3 succeeded: Detected UDP beacon from $beaconIp")
            keyStoreManager.saveLastKnownIp(beaconIp)
            return@withContext beaconIp
        }

        // 4. Subnet Scan (/24)
        LogManager.i("Discovery Step 4: Performing concurrent subnet scan on /24")
        val scannedIp = scanLocalSubnet(BridgeProtocol.DEFAULT_PORT)
        if (scannedIp != null) {
            LogManager.i("Discovery Step 4 succeeded: Discovered Mac on subnet at $scannedIp")
            keyStoreManager.saveLastKnownIp(scannedIp)
            return@withContext scannedIp
        }

        LogManager.i("Discovery completed: No Mac discovered automatically")
        null
    }

    private fun canConnectToPort(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun discoverViaNsd(timeoutMs: Long): String? = withTimeoutOrNull(timeoutMs) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return@withTimeoutOrNull null
        val resultChannel = Channel<String?>(1)

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                resultChannel.trySend(null)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.contains("_bridge._tcp")) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                            val host = resolvedInfo.host?.hostAddress
                            resultChannel.trySend(host)
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }

        try {
            nsdManager.discoverServices("_bridge._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            val ip = resultChannel.receive()
            nsdManager.stopServiceDiscovery(discoveryListener)
            ip
        } catch (e: Exception) {
            LogManager.e("NSD discovery exception", e)
            null
        }
    }

    private suspend fun listenForUdpBeacon(timeoutMs: Long): String? = withTimeoutOrNull(timeoutMs) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("BridgeMulticastLock")?.apply {
            setReferenceCounted(true)
            acquire()
        }

        try {
            DatagramSocket(BridgeProtocol.DEFAULT_PORT).use { socket ->
                socket.broadcast = true
                socket.soTimeout = timeoutMs.toInt()

                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)

                val payload = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val json = JSONObject(payload)
                if (json.optInt("port", 0) == BridgeProtocol.DEFAULT_PORT) {
                    packet.address?.hostAddress
                } else null
            }
        } catch (_: Exception) {
            null
        } finally {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        }
    }

    private suspend fun scanLocalSubnet(port: Int): String? = coroutineScope {
        val localIps = getDeviceIpv4Addresses()
        if (localIps.isEmpty()) return@coroutineScope null

        for (localIp in localIps) {
            val parts = localIp.split(".")
            if (parts.size != 4) continue
            val subnetPrefix = "${parts[0]}.${parts[1]}.${parts[2]}."

            val deferredList = (1..254).map { hostNum ->
                val targetIp = "$subnetPrefix$hostNum"
                if (targetIp == localIp) return@map null
                async(Dispatchers.IO) {
                    if (canConnectToPort(targetIp, port, 300)) targetIp else null
                }
            }.filterNotNull()

            val results = deferredList.awaitAll()
            val found = results.firstOrNull { it != null }
            if (found != null) return@coroutineScope found
        }
        null
    }

    private fun getDeviceIpv4Addresses(): List<String> {
        val list = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return list
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is InetAddress && addr.hostAddress?.contains(":") == false) {
                        addr.hostAddress?.let { list.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.e("Error inspecting network interfaces", e)
        }
        return list
    }
}
