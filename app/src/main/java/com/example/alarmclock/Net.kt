package com.example.alarmclock

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Простые HTTP-запросы к часам (веб-сервер на ESP32 понимает только http и формы). */
object Http {
    private fun open(url: String, method: String, timeoutMs: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.useCaches = false
        return c
    }

    private fun encodeForm(p: Map<String, String>): String =
        p.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }

    fun get(base: String, path: String, timeoutMs: Int = 3000): String {
        val c = open(base + path, "GET", timeoutMs)
        try {
            if (c.responseCode != 200) throw IOException("HTTP " + c.responseCode)
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun post(base: String, path: String, params: Map<String, String> = emptyMap(), timeoutMs: Int = 4000) {
        val body = encodeForm(params).toByteArray(Charsets.UTF_8)
        val c = open(base + path, "POST", timeoutMs)
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            if (c.responseCode != 200) throw IOException("HTTP " + c.responseCode)
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}

/**
 * Все запросы идут по очереди в одном потоке: веб-сервер на ESP32 обслуживает
 * одного клиента за раз, а результат возвращается в главный поток.
 */
object Net {
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun <T> exec(work: () -> T, done: (T?, Throwable?) -> Unit) {
        pool.execute {
            var res: T? = null
            var err: Throwable? = null
            try {
                res = work()
            } catch (t: Throwable) {
                err = t
            }
            main.post { done(res, err) }
        }
    }
}

/** Поиск часов по mDNS (служба _http._tcp с именем alarm-clock). Возвращает IPv4-адрес. */
class Discovery(ctx: Context, private val onHost: (String) -> Unit) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private var listener: NsdManager.DiscoveryListener? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    @Volatile
    private var active = false

    fun start() {
        if (active) return
        active = true
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName.startsWith("alarm-clock", ignoreCase = true)) {
                    main.post { enqueue(serviceInfo) }
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                active = false
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        listener = l
        try {
            nsd.discoverServices("_http._tcp", NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            active = false
        }
    }

    fun stop() {
        active = false
        pending.clear()
        resolving = false
        val l = listener
        listener = null
        if (l != null) {
            try {
                nsd.stopServiceDiscovery(l)
            } catch (e: Exception) {
            }
        }
    }

    private fun enqueue(info: NsdServiceInfo) {
        if (!active) return
        pending.addLast(info)
        next()
    }

    // NSD умеет определять только одну службу за раз, поэтому очередь
    @Suppress("DEPRECATION")
    private fun next() {
        if (resolving || !active) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        next()
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val addrs: List<InetAddress> =
                        if (Build.VERSION.SDK_INT >= 34) serviceInfo.hostAddresses
                        else listOfNotNull(serviceInfo.host)
                    val v4 = addrs.firstOrNull { it is Inet4Address }
                    val ip: String? = v4?.hostAddress
                    main.post {
                        resolving = false
                        if (active && ip != null) onHost(ip)
                        next()
                    }
                }
            })
        } catch (e: Exception) {
            resolving = false
        }
    }
}

/** Запасной поиск: перебираем адреса своей подсети и ищем ответ часов на /api/lists. */
class SubnetScanner {
    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun scan(prefix: String, onFound: (String) -> Unit, onDone: () -> Unit) {
        val pool = Executors.newFixedThreadPool(32)
        val left = AtomicInteger(254)
        val found = AtomicBoolean(false)
        for (i in 1..254) {
            pool.execute {
                try {
                    val ip = prefix + i
                    if (!cancelled && !found.get() && probe(ip)) {
                        if (found.compareAndSet(false, true)) onFound(ip)
                    }
                } catch (e: Exception) {
                }
                if (left.decrementAndGet() == 0) {
                    pool.shutdown()
                    onDone()
                }
            }
        }
    }

    private fun probe(ip: String): Boolean {
        val c = URL("http://$ip/api/lists").openConnection() as HttpURLConnection
        c.connectTimeout = 500
        c.readTimeout = 800
        try {
            if (c.responseCode != 200) return false
            val t = c.inputStream.bufferedReader().use { it.readText() }
            return t.contains("\"fx\"") && t.contains("\"tap\"") && t.contains("\"pal\"")
        } finally {
            c.disconnect()
        }
    }

    companion object {
        /** Например "192.168.0." для адреса телефона 192.168.0.57, либо null, если нет Wi-Fi. */
        fun localPrefix(): String? {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return null
            for (nif in Collections.list(ifs)) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        val parts = (addr.hostAddress ?: continue).split(".")
                        if (parts.size == 4) return parts[0] + "." + parts[1] + "." + parts[2] + "."
                    }
                }
            }
            return null
        }
    }
}
