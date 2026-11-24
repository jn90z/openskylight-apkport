package com.jn90z.openskylight

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Media3 uses the host/port overload for its interleaved RTSP connection. */
internal class RtspTlsSocketFactory(
    private val expectedHost: String,
    private val expectedPort: Int
) : SocketFactory() {
    override fun createSocket(host: String, port: Int): Socket = connect(host, port, null)

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        connect(host, port, InetSocketAddress(localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket(host.hostAddress ?: expectedHost, port)

    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket(host.hostAddress ?: expectedHost, port, localHost, localPort)

    private fun connect(host: String, port: Int, local: InetSocketAddress?): Socket {
        if (!host.equals(expectedHost, ignoreCase = true) || port != expectedPort) {
            throw javax.net.ssl.SSLException("RTSPS redirect changed the camera endpoint")
        }
        val raw = Socket()
        try {
            if (local != null) raw.bind(local)
            raw.connect(InetSocketAddress(host, port), 5_000)
            raw.soTimeout = 5_000
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tls = factory.createSocket(raw, host, port, true) as SSLSocket
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.startHandshake()
            tls.soTimeout = 0
            return tls
        } catch (error: Exception) {
            raw.close()
            throw error
        }
    }
}
