package com.danielflower

import org.slf4j.LoggerFactory
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.security.KeyStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.net.ssl.*


class InterceptingForwardProxy(
    private val socketServer: ServerSocket,
    private val executorService: ExecutorService,
    private val shutdownExecutorOnClose: Boolean,
    private val sslSocketFactory: SSLSocketFactory,
) : AutoCloseable {
    private var acceptThread: Thread? = null
    private var isRunning = true

    companion object {
        private val log = LoggerFactory.getLogger(InterceptingForwardProxy::class.java)!!
        fun start(port: Int, bindAddress: InetAddress, backlog: Int = 50,
                  executorService: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(), shutdownExecutorOnClose: Boolean = true): InterceptingForwardProxy {
            val socketServer = ServerSocket(port, backlog, bindAddress)

            val sslContext = createSSLContext()

            val proxy = InterceptingForwardProxy(
                socketServer, executorService, shutdownExecutorOnClose,
                sslContext.socketFactory
            )
            proxy.start()
            return proxy
        }

        fun createSSLContext() : SSLContext {
            val keyStore = KeyStore.getInstance("PKCS12")
            InterceptingForwardProxy::class.java.getResourceAsStream("/test-certs/proxy-server.p12").use { keyStoreStream ->
                keyStore.load(keyStoreStream, "password".toCharArray())
            }
            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(keyStore, "password".toCharArray())
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(keyManagerFactory.keyManagers, arrayOf<TrustManager>(createTrustManager()), null)
            return sslContext
        }

        fun createTrustManager(): X509TrustManager {
            val certificateAuthorityStore = KeyStore.getInstance("PKCS12")
            InterceptingForwardProxy::class.java.getResourceAsStream("/test-certs/ca.p12").use { caStream ->
                certificateAuthorityStore.load(
                    caStream,
                    "password".toCharArray()
                )
            }
            val trustManagerFactory = TrustManagerFactory.getInstance("PKIX")
            trustManagerFactory.init(certificateAuthorityStore)
            return trustManagerFactory.trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull() ?: throw RuntimeException("Could not find the certificate authority trust store")

        }


    }

    private fun handleClientSocket(clientSocket:Socket) = Runnable {
        try {
            log.info("Handling ${clientSocket.remoteSocketAddress}")

            // Read just the first line, which is in plaintext (rest of input is expected to be encrypted)
            val requestLine = BufferedReader(InputStreamReader(clientSocket.getInputStream())).readLine()

            if (requestLine == null) {
                clientSocket.close()
            } else {
                log.info("Request line is $requestLine")
                val clientWriter = BufferedWriter(OutputStreamWriter(clientSocket.getOutputStream()))
                val requestParts = requestLine.split(Pattern.compile(" ")).dropLastWhile { it.isEmpty() }
                val method = requestParts[0]
                val url = requestParts[1]
                val httpVersion = requestParts[2]
                if (httpVersion != "HTTP/1.1") {
                    clientWriter.append("HTTP/1.1 505 HTTP Version Not Supported\r\n\r\n").close()
                    clientSocket.close()
                } else if (method == "CONNECT") {
                    val hostPort = url.split(":", limit = 2)
                    val host = hostPort[0]
                    val port = hostPort[1].toInt()
                    handleConnect(host, port, clientSocket, clientWriter)
                } else {
                    clientWriter.append("HTTP/1.1 405 Method Not Supported\r\n\r\n").close()
                    clientSocket.close()
                }

            }
        } catch (e: Exception) {
            log.error("Error while handling client socket", e)
        }

    }

    private fun handleConnect(
        targetHost: String,
        targetPort: Int,
        clientSocket: Socket,
        clientWriter: BufferedWriter
    ) {
        val targetSocket = Socket(targetHost, targetPort)
        clientWriter.write("HTTP/1.1 200 Connection Established\r\n\r\n")
        clientWriter.flush()


        val sslClientSocket = sslSocketFactory.createSocket(clientSocket, null, clientSocket.port, true) as SSLSocket
        sslClientSocket.useClientMode = false
        sslClientSocket.addHandshakeCompletedListener { listener ->
            log.info("client handshake done {}", listener)
        }
        sslClientSocket.startHandshake()

        val sslTargetSocket = sslSocketFactory.createSocket(targetSocket, targetHost, targetPort, true) as SSLSocket
        sslTargetSocket.useClientMode = true
        sslTargetSocket.addHandshakeCompletedListener { listener ->
            log.info("target handshake done " + listener)
        }

        sslTargetSocket.startHandshake()


        sslClientSocket.inputStream.use { clientIn ->
            sslClientSocket.outputStream.use { clientOut ->
                sslTargetSocket.inputStream.use { serverIn ->
                    sslTargetSocket.outputStream.use { serverOut ->
                        log.info("Starting to copy between client ${sslClientSocket} and target ${sslTargetSocket}")
                        val t1: Future<*> = executorService.submit { transferData(clientIn, serverOut) }
                        val t2: Future<*> = executorService.submit { transferData(serverIn, clientOut) }
                        t1.get()
                        log.info("t1 joined")
                        t2.get()
                        log.info("t2 joined")
                    }
                }
            }
        }
    }

    private fun transferData(source: InputStream, out: OutputStream) {
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (source.read(buffer).also { bytesRead = it } != -1) {
            out.write(buffer, 0, bytesRead)
            out.flush()
        }
    }

    private fun start() {
        val t = Thread {
            while (isRunning) {
                try {
                    val clientSocket = socketServer.accept()!!
                    executorService.submit(handleClientSocket(clientSocket))
                } catch (e: Exception) {
                    if (isRunning) log.warn("Error while waiting for socket", e)
                }
            }
        }
        t.name = "proxy-acceptor-thread"
        t.isDaemon = false
        t.start()
        acceptThread = t
    }

    override fun close() {
        isRunning = false
        acceptThread?.interrupt()
        if (shutdownExecutorOnClose) {
            executorService.shutdownNow()
            executorService.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    fun address(): SocketAddress = socketServer.localSocketAddress
}