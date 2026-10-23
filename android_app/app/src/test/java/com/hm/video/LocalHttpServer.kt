package com.hm.video

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/** Test-only HTTP fixture; never contacts the source site or a television. */
class LocalHttpServer(private val response: (String) -> String) : AutoCloseable {
    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val baseUrl = "http://127.0.0.1:${socket.localPort}"
    val requests = CopyOnWriteArrayList<String>()
    private val thread = Thread({
        while (!socket.isClosed) {
            try {
                socket.accept().use { client ->
                    client.soTimeout = 3000
                    val reader = client.getInputStream().bufferedReader()
                    val path = reader.readLine().split(' ')[1]
                    while (!reader.readLine().isNullOrEmpty()) { /* consume headers */ }
                    requests.add(path)
                    val bytes = response(path).toByteArray(Charsets.UTF_8)
                    client.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            } catch (error: Exception) {
                if (!socket.isClosed) throw error
            }
        }
    }, "hm-test-http").apply { isDaemon = true; start() }

    override fun close() {
        socket.close()
        thread.join(4000)
    }
}
