package com.danielflower

import org.slf4j.LoggerFactory
import java.io.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
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
    private val listener: RequestStreamListener,
) : AutoCloseable {
    private var acceptThread: Thread? = null
    private var isRunning = true

    companion object {
        private val log = LoggerFactory.getLogger(InterceptingForwardProxy::class.java)!!
        fun start(port: Int = 0, bindAddress: InetAddress = InetAddress.getLocalHost(), listener: RequestStreamListener, backlog: Int = 50,
                  executorService: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(), shutdownExecutorOnClose: Boolean = true): InterceptingForwardProxy {
            val socketServer = ServerSocket(port, backlog, bindAddress)

            val sslContext = createSSLContext()

            val proxy = InterceptingForwardProxy(
                socketServer, executorService, shutdownExecutorOnClose,
                sslContext.socketFactory, listener
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
            // Read just the first line, which is in plaintext (rest of input is expected to be encrypted)
            val requestLine = BufferedReader(InputStreamReader(clientSocket.getInputStream())).readLine()
            log.info("Handling ${clientSocket.remoteSocketAddress} with requestLine $requestLine")

            if (requestLine == null) {
                clientSocket.close()
            } else {
                val clientWriter = BufferedWriter(OutputStreamWriter(clientSocket.getOutputStream()))
                val requestParts = requestLine.split(Pattern.compile(" ")).dropLastWhile { it.isEmpty() }
                val method = requestParts[0]
                val url = requestParts[1]
                val httpVersion = requestParts[2]
                if (httpVersion != "HTTP/1.1" && httpVersion != "HTTP/1.0") {
                    log.info("Unsupported HTTP version: $httpVersion")
                    clientWriter.append("HTTP/1.1 505 HTTP Version Not Supported\r\n\r\n").close()
                    clientSocket.close()
                } else if (method == "CONNECT") {
                    val hostPort = url.split(":", limit = 2)
                    val host = hostPort[0]
                    val port = hostPort[1].toInt()
                    handleConnect(host, port, clientSocket, clientWriter)
                } else {
                    log.info("Unsupported method: $method")
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
        sslClientSocket.startHandshake()

        val sslTargetSocket = sslSocketFactory.createSocket(targetSocket, targetHost, targetPort, true) as SSLSocket
        sslTargetSocket.useClientMode = true
        sslTargetSocket.startHandshake()


        sslClientSocket.inputStream.use { clientIn ->
            sslClientSocket.outputStream.use { clientOut ->
                sslTargetSocket.inputStream.use { serverIn ->
                    sslTargetSocket.outputStream.buffered().use { serverOut ->
                        val t1: Future<*> = executorService.submit { transferDataFromClientToTarget(clientIn, serverOut) }
                        val t2: Future<*> = executorService.submit { transferDataFromTargetToClient(serverIn, clientOut) }
                        try {
                            t1.get()
                            t2.get()
                        } catch (e: InterruptedException) {
                            // we done
                        }
                    }
                }
            }
        }

        sslTargetSocket.close()
        sslClientSocket.close()

    }

    private fun transferDataFromClientToTarget(source: InputStream, out: OutputStream) {
        val buffer = ByteArray(8192)
        var bytesRead: Int
        val requestParser = Http1RequestParser()
        while (source.read(buffer).also { bytesRead = it } != -1) {
            requestParser.feed(buffer, 0, bytesRead, object : RequestStreamListener {
                override fun onRequestHeadersReady(request: HttpRequest) {
                    listener.onRequestHeadersReady(request)
                    request.writeTo(out)
                }

                override fun onBytesToProxy(array: ByteArray, offset: Int, length: Int) {
                    listener.onBytesToProxy(array, offset, length)
                    out.write(array, offset, length)
                }

            })
            out.flush()
        }
    }

    private fun transferDataFromTargetToClient(source: InputStream, out: OutputStream) {
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (source.read(buffer).also { bytesRead = it } != -1) {
            out.write(buffer, 0, bytesRead)
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

    fun address(): InetSocketAddress = socketServer.localSocketAddress as InetSocketAddress
}

