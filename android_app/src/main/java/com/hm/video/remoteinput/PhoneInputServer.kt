package com.hm.video.remoteinput

import android.os.Handler
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Temporary LAN HTTP endpoint used only while the TV's phone-input dialog is open.
 *
 * It deliberately has no relation to HlsLocalProxy: it binds a separate short-lived port and
 * accepts only one GET page and one POST submission for the current session.
 */
class PhoneInputServer(
    private val ports: IntArray = intArrayOf(8899, 8787, 9090),
    private val sessionTtlMs: Long = 5 * 60 * 1000L,
    private val mainHandler: Handler,
    private val onKeyword: (String) -> Unit,
    private val onExpired: () -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val allowedHosts: Set<String> = emptySet(),
    private val hostOverride: String? = null
) {
    data class Session(val url: String, val code: String, val port: Int)

    private val random = SecureRandom()
    private val accepted = AtomicBoolean(false)
    private val workers = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(4),
        { runnable -> Thread(runnable, "hm-phone-input").apply { isDaemon = true } }
    )

    @Volatile private var running = false
    @Volatile private var expired = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var sessionCode = ""
    @Volatile private var publicHost = ""
    @Volatile private var publicPort = 0
    @Volatile private var expiresAt = 0L

    private val expiryRunnable = Runnable {
        if (running && !accepted.get()) {
            expired = true
            onExpired()
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun start(): Session {
        if (running) throw IllegalStateException("手机输入服务已在运行")
        val code = newCode()
        val socket = bind(ports)
        val host = hostOverride?.takeIf { it.isNotBlank() }
            ?: LanAddresses.candidates().firstOrNull()?.host
            ?: run {
                try { socket.close() } catch (_: Exception) {}
                throw IOException("未找到可用的局域网 IPv4 地址")
            }

        sessionCode = code
        publicHost = host
        publicPort = socket.localPort
        expiresAt = clock() + sessionTtlMs
        expired = false
        accepted.set(false)
        serverSocket = socket
        running = true
        mainHandler.postDelayed(expiryRunnable, sessionTtlMs)

        Thread({ acceptLoop(socket) }, "hm-phone-accept").apply { isDaemon = true }.start()
        return Session("http://$host:${socket.localPort}/$code", code, socket.localPort)
    }

    @Synchronized
    fun stop() {
        retire()
        workers.shutdownNow()
    }

    /** Stops accepting new work after a successful submit, but lets in-flight requests finish. */
    @Synchronized
    private fun retire() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        mainHandler.removeCallbacks(expiryRunnable)
        workers.shutdown()
    }

    private fun bind(candidates: IntArray): ServerSocket {
        var last: Exception? = null
        for (port in candidates) {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 8)
                return socket
            } catch (error: Exception) {
                last = error
            }
        }
        throw IOException("手机输入端口绑定失败", last)
    }

    private fun acceptLoop(socket: ServerSocket) {
        try {
            while (running) {
                val client = socket.accept()
                client.soTimeout = 5000
                try {
                    workers.execute { handle(client) }
                } catch (_: Exception) {
                    sendStatus(client, 503, "Service Unavailable")
                    try { client.close() } catch (_: Exception) {}
                }
            }
        } catch (_: SocketException) {
            // Expected when the dialog closes or the session is consumed.
        } catch (_: Exception) {
            // A listener failure must never escape to the UI thread.
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            try {
                val input = BufferedInputStream(client.getInputStream())
                val request = readRequest(input) ?: return sendStatus(client, 400, "Bad Request")
                if (expired || clock() > expiresAt) {
                    sendHtml(client, 410, "会话已过期", pageMessage("会话已过期，请回到电视重新打开“手机输入”。"))
                    return
                }
                val pathCode = request.path.trim('/').lowercase()
                if (pathCode != sessionCode || request.path.count { it == '/' } != 1) {
                    return sendStatus(client, 404, "Not Found")
                }
                when (request.method) {
                    "GET" -> {
                        if (accepted.get()) {
                            sendHtml(client, 409, "已提交", pageMessage("本次输入已提交，请回到电视查看。"))
                        } else {
                            sendHtml(client, 200, "手机输入", inputPage())
                        }
                    }
                    "POST" -> submit(client, request)
                    else -> sendStatus(client, 405, "Method Not Allowed")
                }
            } catch (_: Exception) {
                try { sendStatus(client, 400, "Bad Request") } catch (_: Exception) {}
            }
        }
    }

    private fun submit(client: Socket, request: Request) {
        if (!allowedSource(request.headers)) return sendStatus(client, 403, "Forbidden")
        if (!request.contentType.startsWith("application/x-www-form-urlencoded", ignoreCase = true)) {
            return sendStatus(client, 415, "Unsupported Media Type")
        }
        val length = request.headers["content-length"]?.toIntOrNull() ?: return sendStatus(client, 411, "Length Required")
        if (length <= 0) return sendHtml(client, 400, "输入无效", inputPage(error = "请输入搜索词。"))
        if (length > MAX_BODY_BYTES) return sendHtml(client, 413, "请求过大", inputPage(error = "搜索词太长，请缩短后重试。"))

        val body = request.body
        if (body.size != length) return sendStatus(client, 400, "Bad Request")
        val keyword = formValue(String(body, Charsets.UTF_8), "keyword")
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?.trim()
            .orEmpty()

        if (keyword.isEmpty()) return sendHtml(client, 400, "输入无效", inputPage(error = "请输入搜索词。"))
        if (keyword.length > MAX_KEYWORD_CHARS || keyword.toByteArray(Charsets.UTF_8).size > MAX_KEYWORD_BYTES) {
            return sendHtml(client, 413, "搜索词过长", inputPage(keyword, "搜索词太长，请缩短后重试。"))
        }
        if (keyword.any { it < ' ' && it != '\n' && it != '\r' }) {
            return sendHtml(client, 400, "输入无效", inputPage(error = "搜索词包含不支持的字符。"))
        }
        if (!accepted.compareAndSet(false, true)) {
            return sendHtml(client, 409, "已提交", pageMessage("本次输入已提交，请回到电视查看。"))
        }

        // Flush the phone's confirmation before handing the keyword to the TV UI.
        sendHtml(client, 200, "已发送", successPage())
        mainHandler.post { onKeyword(keyword) }
        retire()
    }

    internal fun allowedSource(headers: Map<String, String>): Boolean {
        val source = headers["origin"] ?: headers["referer"] ?: return true
        if (source == "null") return false
        return try {
            val uri = URI(source)
            val port = if (uri.port > 0) uri.port else 80
            val hosts = allowedHosts + publicHost
            val hostMatches = uri.host?.let { host -> hosts.any { it.equals(host, ignoreCase = true) } } == true
            hostMatches && port == publicPort
        } catch (_: Exception) {
            false
        }
    }

    private fun readRequest(input: BufferedInputStream): Request? {
        val header = readHeader(input) ?: return null
        if (header.toByteArray(Charsets.ISO_8859_1).size > MAX_HEADER_BYTES) return null
        val lines = header.split("\r\n")
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size != 3) return null
        val method = parts[0].uppercase()
        val path = parts[1]
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val index = line.indexOf(':')
            if (index <= 0) continue
            headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
        }
        val body = if (method == "POST") {
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > MAX_BODY_BYTES) return null
            readBytes(input, length)
        } else ByteArray(0)
        return Request(method, path, headers, headers["content-type"].orEmpty(), body)
    }

    private fun readHeader(input: BufferedInputStream): String? {
        val output = ByteArrayOutputStream()
        var matched = 0
        while (output.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value == -1) return null
            output.write(value)
            matched = when {
                matched == 0 && value == 13 -> 1
                matched == 1 && value == 10 -> 2
                matched == 2 && value == 13 -> 3
                matched == 3 && value == 10 -> return output.toString("ISO-8859-1").dropLast(4)
                value == 13 -> 1
                else -> 0
            }
        }
        return null
    }

    private fun readBytes(input: BufferedInputStream, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(result, offset, length - offset)
            if (count < 0) break
            offset += count
        }
        return if (offset == length) result else result.copyOf(offset)
    }

    private fun formValue(body: String, name: String): String? {
        for (part in body.split('&')) {
            val pair = part.split('=', limit = 2)
            if (pair.size == 2 && URLDecoder.decode(pair[0], "UTF-8") == name) return pair[1]
        }
        return null
    }

    private fun newCode(): String {
        val alphabet = "23456789abcdefghjkmnpqrstuvwxyz"
        return buildString(CODE_LENGTH) {
            repeat(CODE_LENGTH) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    private fun sendStatus(socket: Socket, code: Int, reason: String) {
        try {
            val body = "$code $reason".toByteArray(Charsets.UTF_8)
            val output = BufferedOutputStream(socket.getOutputStream())
            output.write("HTTP/1.1 $code $reason\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.flush()
        } catch (_: Exception) {}
    }

    private fun sendHtml(socket: Socket, code: Int, title: String, html: String) {
        try {
            val body = html.toByteArray(Charsets.UTF_8)
            val output = BufferedOutputStream(socket.getOutputStream())
            val header = "HTTP/1.1 $code $title\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Referrer-Policy: no-referrer\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; form-action 'self'; base-uri 'none'\r\n" +
                "Connection: close\r\n\r\n"
            output.write(header.toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.flush()
        } catch (_: Exception) {}
    }

    private fun inputPage(value: String = "", error: String? = null): String = page(
        title = "手机输入",
        body = buildString {
            append("<p class=\"hint\">发送后，请在电视上查看搜索结果。</p>")
            if (error != null) append("<p class=\"error\">").append(escapeHtml(error)).append("</p>")
            append("<form method=\"post\" action=\"/").append(sessionCode).append("\" accept-charset=\"UTF-8\">")
            append("<input name=\"keyword\" type=\"text\" maxlength=\"").append(MAX_KEYWORD_CHARS)
                .append("\" autocomplete=\"off\" autocapitalize=\"off\" value=\"")
                .append(escapeHtml(value)).append("\" placeholder=\"片名或演员\">")
            append("<button type=\"submit\">发送到电视</button></form>")
            append("<script>document.querySelector('form').addEventListener('submit',function(){var b=document.querySelector('button');b.disabled=true;b.textContent='发送中…';});</script>")
        }
    )

    private fun successPage(): String = page(
        title = "已发送",
        body = "<p class=\"ok\">已发送到电视，请使用电视遥控器继续操作。</p>"
    )

    private fun pageMessage(message: String): String = page(
        title = "提示",
        body = "<p class=\"hint\">" + escapeHtml(message) + "</p>"
    )

    private fun page(title: String, body: String): String =
        "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>$title</title><style>" +
            "body{margin:0;background:#0d1118;color:#eaf1ff;font:16px -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}" +
            "main{max-width:420px;margin:0 auto;padding:32px 20px}" +
            "h1{font-size:22px;margin:0 0 12px}p{line-height:1.6}.hint{color:#b6c9e2}" +
            ".error{color:#ff8a80}.ok{color:#9be7c4;font-size:18px;margin-top:24px}" +
            "input{box-sizing:border-box;width:100%;height:52px;margin:18px 0 12px;padding:0 14px;border-radius:10px;border:1px solid #34435c;background:#141b26;color:#fff;font-size:18px}" +
            "button{width:100%;height:52px;border:0;border-radius:10px;background:#3b82f6;color:#fff;font-size:17px;font-weight:600}" +
            "button:disabled{opacity:.6}" +
            "</style></head><body><main><h1>$title</h1>$body</main></body></html>"

    private fun escapeHtml(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    private data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val contentType: String,
        val body: ByteArray
    )

    private companion object {
        const val CODE_LENGTH = 6
        const val MAX_HEADER_BYTES = 8 * 1024
        const val MAX_BODY_BYTES = 4 * 1024
        const val MAX_KEYWORD_CHARS = 80
        const val MAX_KEYWORD_BYTES = 512
    }
}
