package com.danielflower

import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.net.ssl.*


/**
 * A forward HTTP proxy that intercepts SSL traffic.
 */
class InterceptingForwardProxy private constructor(
    private val socketServer: ServerSocket,
    private val executorService: ExecutorService,
    private val shutdownExecutorOnClose: Boolean,
    private val sslSocketFactory: SSLSocketFactory,
    private val listener: ConnectionInterceptor,
) : AutoCloseable {
    private var acceptThread: Thread? = null
    private var isRunning = true

    companion object {
        private val log = LoggerFactory.getLogger(InterceptingForwardProxy::class.java)!!

        /**
         * Starts a forward proxy.
         *
         * @param port the port to bind to. Default is `0`.
         * @param backlog requested maximum queue length for connections on the server socket. Default is `50`.
         * @param bindAddress the address to bind the server to. The default is `localhost`.
         * @param executorService the executor to use to handle connections on. Note that this is a blocking server and
         * one thread per connection is used. The default is [Executors.newVirtualThreadPerTaskExecutor]
         * @param shutdownExecutorOnClose if true, then when [close] is called the executorServer will also be closed.
         * Default is `true`.
         * @param sslContext the SSL context to use, which should include a trust store to verify connections to target
         * servers and a key manager for the TLS server that clients connect to.
         * See [createTrustManager] and [createSSLContext] for a couple of helper methods that allow loading these from
         * the classpath.
         * @param connectionInterceptor a listener that allows you to allow or deny connections, inspect requests and
         * body data, and change request parameters such as headers.
         */
        @JvmStatic
        @JvmOverloads
        fun start(port: Int = 0, bindAddress: InetAddress = InetAddress.getLocalHost(), backlog: Int = 50,
                  executorService: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(), shutdownExecutorOnClose: Boolean = true,
                  sslContext: SSLContext, connectionInterceptor: ConnectionInterceptor): InterceptingForwardProxy {
            val socketServer = ServerSocket(port, backlog, bindAddress)
            val proxy = InterceptingForwardProxy(
                socketServer, executorService, shutdownExecutorOnClose,
                sslContext.socketFactory, connectionInterceptor
            )
            proxy.start()
            return proxy
        }

        /**
         * Creates an SSL Context by loading a key store from the classpath
         */
        @JvmStatic
        fun createSSLContext(type: String = "PKCS12", classpathPath: String, password: CharArray, trustManager: TrustManager) : SSLContext {
            val keyStore = KeyStore.getInstance(type)
            InterceptingForwardProxy::class.java.getResourceAsStream(classpathPath).use { keyStoreStream ->
                keyStore.load(keyStoreStream, password)
            }
            val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keyManagerFactory.init(keyStore, password)
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(keyManagerFactory.keyManagers, arrayOf(trustManager), null)
            return sslContext
        }

        /**
         * Creates a trust manager by loading a CA file from the classpath
         */
        @JvmStatic
        fun createTrustManager(type: String = "PKCS12", classpathPath: String, password: CharArray): X509TrustManager {
            val certificateAuthorityStore = KeyStore.getInstance(type)
            InterceptingForwardProxy::class.java.getResourceAsStream(classpathPath).use { caStream ->
                certificateAuthorityStore.load(caStream, password)
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
                val requestParts = requestLine.split(Pattern.compile(" ")).dropLastWhile { it.isEmpty() }
                val method = requestParts[0]
                val url = requestParts[1]
                val httpVersion = requestParts[2]

                if (!listener.acceptConnection(clientSocket, method, url, httpVersion)) {
                    clientSocket.close()
                } else if (httpVersion != "HTTP/1.1" && httpVersion != "HTTP/1.0") {
                    log.info("Unsupported HTTP version: $httpVersion")
                    clientSocket.getOutputStream().use { it.write("HTTP/1.1 505 HTTP Version Not Supported\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)) }
                    clientSocket.close()
                } else if (method == "CONNECT") {
                    val hostPort = url.split(":", limit = 2)
                    val host = hostPort[0]
                    val port = hostPort[1].toInt()
                    handleConnect(host, port, clientSocket)
                } else {
                    log.info("Unsupported method: $method")
                    clientSocket.getOutputStream().use { it.write("HTTP/1.1 405 Method Not Supported\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)) }
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
    ) {
        val targetSocket = Socket(targetHost, targetPort)
        val clientOS = clientSocket.getOutputStream()
        clientOS.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        clientOS.flush()

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
            requestParser.feed(buffer, 0, bytesRead, object : ConnectionInterceptor {
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

