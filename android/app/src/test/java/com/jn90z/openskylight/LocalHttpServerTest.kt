package com.jn90z.openskylight

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

class LocalHttpServerTest {
    @Test fun parsesRpcAndEnforcesRequestBounds() {
        LocalHttpServer(InetAddress.getLoopbackAddress(), 0, 1) { req, res ->
            res.text(200, "${req.method} ${req.target} ${req.body()}")
        }.use { server ->
            fun send(text: String): String = Socket(InetAddress.getLoopbackAddress(), server.port).use { client ->
                client.soTimeout = 3000
                client.getOutputStream().write(text.toByteArray())
                client.getInputStream().bufferedReader().readText()
            }
            val ok = send("POST /api/rpc/people:list HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2\r\n\r\n{}")
            assertTrue(ok.startsWith("HTTP/1.1 200"))
            assertTrue(ok.endsWith("POST /api/rpc/people:list {}"))
            assertTrue(send("POST / HTTP/1.1\r\nContent-Length: 65537\r\n\r\n").startsWith("HTTP/1.1 413"))
            assertTrue(send("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n").startsWith("HTTP/1.1 400"))
            assertTrue(send("GET / HTTP/1.1\r\nHost: one\r\nHost: two\r\n\r\n").startsWith("HTTP/1.1 400"))
        }
    }
}
