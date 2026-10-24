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
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Temporary LAN HTTP endpoint used only while the TV's phone-input dialog is open.
 *
 * It deliberately has no relation to HlsLocalProxy: it binds a separate short-lived port and
 * accepts only one GET page and one POST submission for the current session.
 *
 * Delivery rules:
 * - A submission is committed exactly once, under the same lock used to stop the session, so a
 *   request that is still being received when the dialog closes can never reach the TV.
 * - Once committed, the keyword is handed to the TV even if the phone's confirmation write fails;
 *   the phone then reports "unknown" instead of a false success and cannot resubmit, because the
 *   session is retired.
 */
class PhoneInputServer(
    private val ports: IntArray = intArrayOf(8899, 8787, 9090),
    private val sessionTtlMs: Long = 5 * 60 * 1000L,
    private val requestTimeoutMs: Long = 8 * 1000L,
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
    private val clients: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val workers = ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(8),
        { runnable -> Thread(runnable, "hm-phone-input").apply { isDaemon = true } }
    )

    @Volatile private var running = false
    @Volatile private var expired = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var sessionCode = ""
    @Volatile private var publicHost = ""
    @Volatile private var publicPort = 0
    @Volatile private var expiresAt = 0L

    private val expiryRunnable: Runnable = Runnable {
        if (running && !accepted.get()) {
            expired = true
            closeClients()
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

    /** Closes the listener and every open client connection, aborting partially received requests. */
    @Synchronized
    fun stop() {
        running = false
        closeListener()
        closeClients()
        workers.shutdownNow()
    }

    /** Stops accepting new work after a successful submit, but lets in-flight requests finish. */
    @Synchronized
    private fun retire() {
        running = false
        closeListener()
        workers.shutdown()
    }

    private fun closeListener() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        mainHandler.removeCallbacks(expiryRunnable)
    }

    private fun closeClients() {
        val snapshot = synchronized(clients) { clients.toList() }
        for (client in snapshot) {
            try { client.close() } catch (_: Exception) {}
        }
        clients.clear()
    }

    /** Session is usable only while the dialog is open, before expiry, and before the first commit. */
    private fun isActive(): Boolean = running && !expired && clock() <= expiresAt

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
                client.soTimeout = requestTimeoutMs.toInt()
                clients.add(client)
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(requestTimeoutMs)
                try {
                    workers.execute { handle(client, deadline) }
                } catch (_: Exception) {
                    clients.remove(client)
                    try { client.close() } catch (_: Exception) {}
                }
            }
        } catch (_: SocketException) {
            // Expected when the dialog closes or the session is consumed.
        } catch (_: Exception) {
            // A listener failure must never escape to the UI thread.
        }
    }

    private fun handle(socket: Socket, deadline: Long) {
        try {
            socket.use { client ->
                try {
                    val input = BufferedInputStream(client.getInputStream())
                    val request = readRequest(input, client, deadline)
                        ?: return@use sendStatus(client, 400, "Bad Request")
                    if (!isActive()) {
                        return@use fail(client, request, 410, "会话已过期，请回到电视重新打开“手机输入”。", retryable = false)
                    }
                    val pathCode = request.path.trim('/').lowercase()
                    if (pathCode != sessionCode || request.path.count { it == '/' } != 1) {
                        return@use fail(client, request, 404, "??????????????????????", retryable = false)
                    }
                    when (request.method) {
                        "GET" -> {
                            if (accepted.get()) {
                                fail(client, request, 409, "本次输入已提交，请回到电视查看。", retryable = false)
                            } else {
                                sendHtml(client, 200, inputPage())
                            }
                        }
                        "POST" -> submit(client, request)
                        else -> fail(client, request, 405, "不支持的请求方式，请刷新页面后重试。", retryable = true)
                    }
                } catch (_: SocketTimeoutException) {
                    try { fail(client, Request("GET", "/", emptyMap(), "", ByteArray(0)), 408, "发送超时，请重试。", retryable = true) } catch (_: Exception) {}
                } catch (_: Exception) {
                    try { sendStatus(client, 400, "Bad Request") } catch (_: Exception) {}
                }
            }
        } finally {
            clients.remove(socket)
        }
    }

    private fun submit(client: Socket, request: Request) {
        if (!allowedSource(request.headers)) {
            return fail(client, request, 403, "来源校验失败，请从电视显示的地址重新打开页面。", retryable = false)
        }
        if (!request.contentType.startsWith("application/x-www-form-urlencoded", ignoreCase = true)) {
            return fail(client, request, 415, "提交格式不正确，请刷新页面后重试。", retryable = true)
        }
        val length = request.headers["content-length"]?.toIntOrNull()
            ?: return fail(client, request, 411, "提交内容为空，请重新输入。", retryable = true)
        if (length <= 0) {
            return fail(client, request, 400, "请输入搜索词。", retryable = true, formValue = "")
        }
        if (length > MAX_BODY_BYTES) {
            return fail(client, request, 413, "搜索词太长，请缩短后重试。", retryable = true, formValue = "")
        }

        val body = request.body
        if (body.size != length) return sendStatus(client, 400, "Bad Request")
        val keyword = formValue(String(body, Charsets.UTF_8), "keyword")
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?.trim()
            .orEmpty()

        if (keyword.isEmpty()) {
            return fail(client, request, 400, "请输入搜索词。", retryable = true, formValue = "")
        }
        if (keyword.length > MAX_KEYWORD_CHARS || keyword.toByteArray(Charsets.UTF_8).size > MAX_KEYWORD_BYTES) {
            return fail(client, request, 413, "搜索词太长，请缩短后重试。", retryable = true, formValue = keyword)
        }
        if (keyword.any { it < ' ' && it != '\n' && it != '\r' }) {
            return fail(client, request, 400, "搜索词包含不支持的字符。", retryable = true, formValue = keyword)
        }

        // Atomic with stop(): a session closed mid-request can never commit a keyword.
        val committed = synchronized(this) {
            if (!isActive()) false else accepted.compareAndSet(false, true)
        }
        if (!committed) {
            val expiredNow = !isActive()
            return fail(
                client,
                request,
                if (expiredNow) 410 else 409,
                if (expiredNow) "会话已过期，请回到电视重新打开“手机输入”。" else "本次输入已提交，请回到电视查看。",
                retryable = false
            )
        }

        // Flush the phone's confirmation before handing the keyword to the TV UI. If the write
        // fails the keyword is still delivered: the phone shows "unknown" and cannot resubmit.
        sendSuccess(client, request)
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

    private fun sendSuccess(socket: Socket, request: Request) {
        if (wantsJson(request)) {
            sendJson(socket, 200, "{\"ok\":true}")
        } else {
            sendHtml(socket, 200, successPage())
        }
    }

    private fun fail(
        socket: Socket,
        request: Request,
        code: Int,
        message: String,
        retryable: Boolean,
        formValue: String? = null
    ) {
        if (wantsJson(request)) {
            sendJson(socket, code, "{\"ok\":false,\"message\":\"$message\",\"retryable\":$retryable}")
            return
        }
        if (formValue != null) {
            sendHtml(socket, code, inputPage(formValue, message))
        } else {
            sendHtml(socket, code, pageMessage(message))
        }
    }

    private fun wantsJson(request: Request): Boolean =
        request.headers["accept"]?.contains("application/json", ignoreCase = true) == true

    private fun readRequest(input: BufferedInputStream, socket: Socket, deadline: Long): Request? {
        val header = readHeader(input, socket, deadline) ?: return null
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
            readBytes(input, socket, length, deadline)
        } else ByteArray(0)
        return Request(method, path, headers, headers["content-type"].orEmpty(), body)
    }

    private fun readHeader(input: BufferedInputStream, socket: Socket, deadline: Long): String? {
        val output = ByteArrayOutputStream()
        var matched = 0
        while (output.size() < MAX_HEADER_BYTES) {
            armTimeout(socket, deadline)
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

    private fun readBytes(input: BufferedInputStream, socket: Socket, length: Int, deadline: Long): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            armTimeout(socket, deadline)
            val count = input.read(result, offset, length - offset)
            if (count < 0) break
            offset += count
        }
        return if (offset == length) result else result.copyOf(offset)
    }

    /**
     * Arms the socket so a single blocking read can never outlive the request deadline that was
     * fixed when the connection was accepted. Remaining time also covers queueing time.
     */
    private fun armTimeout(socket: Socket, deadline: Long) {
        val remainingNanos = deadline - System.nanoTime()
        if (remainingNanos <= 0L) throw SocketTimeoutException("request deadline exceeded")
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
        socket.soTimeout = remainingMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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

    private fun sendJson(socket: Socket, code: Int, json: String) {
        send(
            socket, code, "application/json; charset=utf-8", json,
            "Referrer-Policy: no-referrer\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Content-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\n"
        )
    }

    private fun sendHtml(socket: Socket, code: Int, html: String) {
        send(
            socket, code, "text/html; charset=utf-8", html,
            "Referrer-Policy: no-referrer\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'\r\n"
        )
    }

    private fun send(socket: Socket, code: Int, contentType: String, body: String, extraHeaders: String) {
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val output = BufferedOutputStream(socket.getOutputStream())
            val header = "HTTP/1.1 $code ${statusTitle(code)}\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                extraHeaders +
                "Connection: close\r\n\r\n"
            output.write(header.toByteArray(Charsets.US_ASCII))
            output.write(bytes)
            output.flush()
        } catch (_: Exception) {}
    }

    private fun statusTitle(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        409 -> "Conflict"
        410 -> "Gone"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        415 -> "Unsupported Media Type"
        else -> "Error"
    }

    private fun inputPage(value: String = "", error: String? = null): String = page(
        title = "手机输入",
        body = buildString {
            append("<p class=\"hint\">发送后，请在电视上查看搜索结果。</p>")
            append("<form id=\"phone-form\" method=\"post\" action=\"/").append(sessionCode).append("\" accept-charset=\"UTF-8\">")
            append("<input id=\"phone-keyword\" name=\"keyword\" type=\"text\" maxlength=\"").append(MAX_KEYWORD_CHARS)
                .append("\" autocomplete=\"off\" autocapitalize=\"off\" value=\"")
                .append(escapeHtml(value)).append("\" placeholder=\"片名或演员\">")
            append("<p id=\"phone-status\" class=\"status")
            if (error != null) append(" error")
            append("\" role=\"status\" aria-live=\"polite\">")
            if (error != null) append(escapeHtml(error))
            append("</p>")
            append("<button id=\"phone-send\" type=\"submit\">发送到电视</button>")
            append("<button id=\"phone-retry\" class=\"secondary\" type=\"button\" hidden>重试</button>")
            append("</form>")
            append(
                "<script>(function(){var f=document.getElementById('phone-form'),i=document.getElementById('phone-keyword'),s=document.getElementById('phone-status'),b=document.getElementById('phone-send'),r=document.getElementById('phone-retry'),busy=false,seq=0;function show(m,c){s.textContent=m;s.className='status '+c;}function succeed(){busy=true;b.disabled=true;b.hidden=true;r.hidden=true;i.readOnly=true;show('????????????????????','ok');}function fail(m,retry){busy=!retry;b.disabled=!retry;b.hidden=!retry;b.textContent='?????';r.hidden=!retry;show(m,'error');}function send(){if(busy)return;var v=i.value;if(!v.trim()){show('???????','error');i.focus();return;}var id=++seq,settled=false,controller=null;try{controller=new AbortController();}catch(e){controller=null;}var timer=setTimeout(function(){if(settled||id!==seq)return;settled=true;if(controller){try{controller.abort();}catch(e){}}fail('????????????????????????????????????????',true);},12000);busy=true;b.disabled=true;b.textContent='????';r.hidden=true;show('?????','hint');var opts={method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8','Accept':'application/json'},body:'keyword='+encodeURIComponent(v),cache:'no-store',credentials:'omit'};if(controller)opts.signal=controller.signal;function finish(fn){if(settled||id!==seq)return;settled=true;clearTimeout(timer);fn();}fetch(f.getAttribute('action'),opts).then(function(res){return res.text().then(function(t){return {res:res,t:t};});}).then(function(o){finish(function(){var d=null;try{d=JSON.parse(o.t);}catch(e){}if(o.res.ok&&d&&d.ok){succeed();return;}var terminal=!d&&(o.res.status===404||o.res.status===409||o.res.status===410);var m=(d&&d.message)||(terminal?'??????????????????????':('?????'+o.res.status+'??????'));fail(m,d?d.retryable!==false:!terminal);});}).catch(function(){finish(function(){fail('????????????????????????????????????????',true);});});}f.addEventListener('submit',function(e){e.preventDefault();send();});r.addEventListener('click',send);i.addEventListener('input',function(){if(s.className.indexOf('error')>=0){s.textContent='';s.className='status';}});})();</script>"
            )
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
            ".status{min-height:24px;margin:0 0 12px;color:#b6c9e2}.status.error{color:#ff8a80}.status.ok{color:#9be7c4}" +
            ".ok{color:#9be7c4;font-size:18px;margin-top:24px}" +
            "input{box-sizing:border-box;width:100%;height:52px;margin:18px 0 12px;padding:0 14px;border-radius:10px;border:1px solid #34435c;background:#141b26;color:#fff;font-size:18px}" +
            "button{width:100%;height:52px;border:0;border-radius:10px;background:#3b82f6;color:#fff;font-size:17px;font-weight:600}" +
            "button.secondary{margin-top:10px;background:transparent;border:1px solid #34435c;color:#b6c9e2}" +
            "button:disabled{opacity:.6}[hidden]{display:none}" +
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
