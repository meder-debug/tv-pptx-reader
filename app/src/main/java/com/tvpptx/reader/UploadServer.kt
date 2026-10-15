package com.tvpptx.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A tiny HTTP server on the local network. A phone opens http://<projector>:<port>/, picks a
 * presentation and uploads it; the same page doubles as a slide clicker.
 */
object UploadServer {
    interface Listener {
        fun onProgress(name: String, percent: Int)
        fun onReceived(file: File)
    }

    /** Implemented by the slide viewer while it is showing. Called on the main thread. */
    interface Remote {
        fun command(cmd: String)
        fun state(): JSONObject
    }

    @Volatile var port = 0
        private set
    var listener: Listener? = null
    var remote: Remote? = null

    private const val MAX_UPLOAD = 500L shl 20
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4)
    private var server: ServerSocket? = null
    private lateinit var appContext: Context

    fun start(context: Context) {
        if (server != null) return
        appContext = context.applicationContext
        val socket = (8080..8090).firstNotNullOfOrNull { p ->
            try { ServerSocket(p) } catch (e: Exception) { null }
        } ?: try { ServerSocket(0) } catch (e: Exception) { return }
        server = socket
        port = socket.localPort
        Thread({
            while (true) {
                val client = try { socket.accept() } catch (e: Exception) { break }
                pool.execute { handle(client) }
            }
        }, "upload-server").apply { isDaemon = true }.start()
    }

    /** The projector's address on the local network, e.g. http://192.168.1.23:8080 */
    fun url(): String? {
        if (port == 0) return null
        val ip = localIp() ?: return null
        return "http://$ip:$port"
    }

    private fun localIp(): String? = try {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it } }
            .filter { it.second.isSiteLocalAddress }
        (candidates.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("eth") } ?: candidates.firstOrNull())
            ?.second?.hostAddress
    } catch (e: Exception) {
        null
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 30000
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                val query = parts[1].substringAfter('?', "").split('&').filter { '=' in it }.associate {
                    decode(it.substringBefore('=')) to decode(it.substringAfter('='))
                }
                when {
                    method == "GET" && (path == "/" || path == "/index.html") -> {
                        val html = appContext.assets.open("upload.html").use { it.readBytes() }
                        respond(out, 200, "text/html; charset=utf-8", html)
                    }
                    method == "POST" && path == "/upload" -> upload(input, out, headers, query["name"] ?: "presentation.pptx")
                    path == "/state" -> respondJson(out, onMain { remote?.state() } ?: idleState())
                    method == "POST" && path == "/cmd" -> {
                        val cmd = query["c"] ?: ""
                        respondJson(out, onMain { remote?.let { r -> r.command(cmd); r.state() } } ?: idleState())
                    }
                    else -> respond(out, 404, "text/plain", "Not found".toByteArray())
                }
            } catch (e: Exception) {
                // client went away; nothing to do
            }
        }
    }

    private fun upload(input: InputStream, out: OutputStream, headers: Map<String, String>, rawName: String) {
        val length = headers["content-length"]?.toLongOrNull() ?: -1L
        val name = safeName(rawName)
        if (!Documents.isSupported(name)) {
            respondJson(out, JSONObject().put("ok", false).put("error", "Only .pptx, .ppsx and .pdf files can be shown"), 415)
            return
        }
        if (length <= 0 || length > MAX_UPLOAD) {
            respondJson(out, JSONObject().put("ok", false).put("error", "File is empty or too large"), 413)
            return
        }
        val dir = Library.receivedDir(appContext)
        if (dir.usableSpace < length + (20L shl 20)) {
            respondJson(out, JSONObject().put("ok", false).put("error", "Not enough free space on the projector"), 507)
            return
        }
        val tmp = File(dir, ".upload-${System.nanoTime()}")
        try {
            tmp.outputStream().use { fo ->
                val buf = ByteArray(64 * 1024)
                var remaining = length
                var lastPct = -1
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) throw IllegalStateException("Upload interrupted")
                    fo.write(buf, 0, n)
                    remaining -= n
                    val pct = ((length - remaining) * 100 / length).toInt()
                    if (pct != lastPct && pct % 5 == 0) {
                        lastPct = pct
                        main.post { listener?.onProgress(name, pct) }
                    }
                }
            }
            val target = uniqueFile(dir, name)
            if (!tmp.renameTo(target)) throw IllegalStateException("Could not save file")
            main.post { (appContext as? App)?.onFileReceived(target) ?: listener?.onReceived(target) }
            respondJson(out, JSONObject().put("ok", true).put("name", target.name))
        } catch (e: Exception) {
            tmp.delete()
            main.post { listener?.onProgress(name, -1) }
            throw e
        }
    }

    private fun idleState() = JSONObject().put("presenting", false)

    /** Runs [block] on the UI thread and waits briefly for its answer. */
    private fun <T> onMain(block: () -> T?): T? {
        var result: T? = null
        val latch = CountDownLatch(1)
        main.post {
            try { result = block() } finally { latch.countDown() }
        }
        latch.await(2, TimeUnit.SECONDS)
        return result
    }

    private fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val clean = base.map { c -> if (c.isLetterOrDigit() || c in " ._-()[]&+,'") c else '_' }.joinToString("").trim().trimStart('.')
        return clean.ifEmpty { "presentation.pptx" }.takeLast(120)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 2
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        while (f.exists()) f = File(dir, "$stem ($i).$ext").also { i++ }
        return f
    }

    private fun decode(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(c.toChar())
        }
    }

    private fun respondJson(out: OutputStream, json: JSONObject, code: Int = 200) =
        respond(out, code, "application/json", json.toString().toByteArray())

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Error" }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }
}
