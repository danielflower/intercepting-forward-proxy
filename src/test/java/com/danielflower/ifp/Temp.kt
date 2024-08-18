package com.danielflower.ifp

import com.danielflower.ifp.InterceptingForwardProxyTest.Companion.trustManager
import java.net.SocketException
import java.net.URI
import javax.net.ssl.SSLServerSocket

object Temp {
    @JvmStatic
    fun main(args: Array<String>) {
        val sslContext = InterceptingForwardProxy.createSSLContext(
            classpathPath = "/test-certs/proxy-server.p12",
            password = "password".toCharArray(),
            trustManager = trustManager
        )

        val server = sslContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket

        val serverUrl = URI("https://localhost:${server.localPort}")
        println("Started at $serverUrl")
        server.accept().use { socket ->
            val reqThread = Thread {

                socket.getInputStream().bufferedReader().use { reqStream ->
                    var line: String?
                    try {
                        while (reqStream.readLine().also { line = it } != null ){
                            println(">> $line")
                        }
                        println(">>>end")
                    } catch (e: SocketException) {
                        println(">>>enderror ${e.message}")
                    }
                }

            }
            reqThread.start()

            socket.getOutputStream().writer().use { writer ->
                writer.write("HTTP/1.1 200 OK\r\n")
                writer.write("content-type: text/plain\r\n")
                writer.write("connection: close\r\n")
                writer.write("\r\n")
                writer.flush()
                writer.write("Hello ")
                writer.flush()
                writer.write("world".repeat(10000))
                writer.flush()
                writer.write("\r\n")
            }
        }
    }
}