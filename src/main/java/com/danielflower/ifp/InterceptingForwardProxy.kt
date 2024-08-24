package com.danielflower.ifp

import org.slf4j.LoggerFactory
import java.io.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.*
import java.util.concurrent.*
import javax.net.ssl.*


/**
 * A forward HTTP proxy that intercepts SSL traffic.
 */
class InterceptingForwardProxy private constructor(
    private val socketServer: ServerSocket,
    private val executorService: ExecutorService,
    private val shutdownExecutorOnClose: Boolean,
    private val listener: ConnectionInterceptor,
) : AutoCloseable {
    private var acceptThread: Thread? = null
    private var isRunning = true

    companion object {
        private val log = LoggerFactory.getLogger(InterceptingForwardProxy::class.java)!!

        /**
         * Starts a forward proxy with the given config.
         * @param connectionInterceptor A listener that allows you to allow or deny connections, inspect requests and
         * body data, and change request parameters such as headers. This is required.
         */
        @JvmStatic
        fun start(config: InterceptingForwardProxyConfig, connectionInterceptor: ConnectionInterceptor): InterceptingForwardProxy {

            val port = config.port ?: 0
            val bindAddress = config.bindAddress ?: InetAddress.getLoopbackAddress()
            val backlog = config.backlog ?: 50
            val executorService = config.executorService ?: Executors.newVirtualThreadPerTaskExecutor()
            val shutdownExecutorOnClose = config.shutdownExecutorOnClose ?: true

            val socketServer = ServerSocket(port, backlog, bindAddress)
            val proxy = InterceptingForwardProxy(
                socketServer, executorService, shutdownExecutorOnClose, connectionInterceptor
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
                val requestParts = requestLine.split(' ').dropLastWhile { it.isEmpty() }
                val method = requestParts[0]
                val url = requestParts[1]
                val httpVersion = requestParts[2]

                val connectionInfo = listener.acceptConnection(clientSocket, method, url, httpVersion)
                if (connectionInfo == null) {
                    clientSocket.close()
                } else if (httpVersion != "HTTP/1.1" && httpVersion != "HTTP/1.0") {
                    log.info("Unsupported HTTP version: $httpVersion")
                    clientSocket.getOutputStream().use { it.write("HTTP/1.1 505 HTTP Version Not Supported\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)) }
                    clientSocket.close()
                } else if (method == "CONNECT") {
                    handleConnect(connectionInfo, clientSocket)
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

    private val connectionEstablishedResponseBytes = "HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
    private fun handleConnect(
        connectionInfo: ConnectionInfo,
        clientSocket: Socket,
    ) {
        val dest = connectionInfo.targetAddress()
        val targetSocket = Socket(dest.address, dest.port)
        val clientOS = clientSocket.getOutputStream()
        clientOS.write(connectionEstablishedResponseBytes)
        clientOS.flush()

        val ssf = connectionInfo.sslContext().socketFactory
        val sslClientSocket = ssf.createSocket(clientSocket, null, clientSocket.port, true) as SSLSocket
        sslClientSocket.useClientMode = false
        sslClientSocket.addHandshakeCompletedListener(connectionInfo::onClientHandshakeComplete)
        sslClientSocket.startHandshake()

        val sslTargetSocket = ssf.createSocket(targetSocket, dest.hostString, dest.port, true) as SSLSocket
        sslTargetSocket.useClientMode = true
        sslTargetSocket.addHandshakeCompletedListener(connectionInfo::onTargetHandshakeComplete)
        sslTargetSocket.startHandshake()

        val requestPipeline : Queue<HttpRequest> = ConcurrentLinkedQueue()
        val t1: Future<*> = executorService.submit {
            transferDataFromClientToTarget(connectionInfo, sslClientSocket.inputStream, sslTargetSocket.outputStream, requestPipeline)
            sslClientSocket.shutdownInputQuietly()
            sslTargetSocket.shutdownOutputQuietly()
            log.debug("Finished from client to target")
        }
        val t2: Future<*> = executorService.submit {
            transferDataFromTargetToClient(connectionInfo, sslTargetSocket.inputStream, sslClientSocket.outputStream, requestPipeline)
            sslTargetSocket.shutdownInputQuietly()
            sslClientSocket.shutdownOutputQuietly()
            log.debug("Finished from target to client")
        }

        val clientToTargetException : Exception? = try {
            t1.get()
            null
        } catch (e: Exception) {
            log.debug("clientToTargetException", e)
            e
        }

        val targetToClientException: Exception? = try {
            t2.get()
            null
        } catch (e: Exception) {
            log.debug("targetToClientException", e)
            e
        }


        sslTargetSocket.closeQuietly()
        sslClientSocket.closeQuietly()

        listener.onConnectionEnded(connectionInfo, clientToTargetException, targetToClientException)

    }

    private fun transferDataFromClientToTarget(context: ConnectionInfo, source: InputStream, out: OutputStream, requestPipeline: Queue<HttpRequest>) {
        val requestListener = object : HttpMessageListener {

            override fun onHeaders(connectionInfo: ConnectionInfo, exchange: HttpMessage) {
                val request = exchange as HttpRequest
                listener.onRequestHeadersReady(connectionInfo, request)
                request.writeTo(out)
            }

            override fun onBodyBytes(connection: ConnectionInfo, exchange: HttpMessage, type: BodyBytesType, array: ByteArray, offset: Int, length: Int) {
                listener.onRequestBodyBytes(connection, exchange as HttpRequest, type, array, offset, length)
                out.write(array, offset, length)
            }

            override fun onMessageEnded(connectionInfo: ConnectionInfo, exchange: HttpMessage) {
                listener.onRequestEnded(connectionInfo, exchange as HttpRequest)
            }

            override fun onError(connectionInfo: ConnectionInfo, exchange: HttpMessage, error: Exception) {
                val req = exchange as HttpRequest
                if (req.headers().size() > 0) {
                    listener.onRequestError(connectionInfo, req, error)
                }
            }
        }

        val requestParser = Http1MessageParser(context, HttpMessageType.REQUEST, requestPipeline)

        pipeIt(source, requestParser, requestListener, out)
    }

    private fun transferDataFromTargetToClient(context: ConnectionInfo, source: InputStream, out: OutputStream, requestPipeline: Queue<HttpRequest>) {
        val responseParserListener = object : HttpMessageListener {

            override fun onHeaders(connectionInfo: ConnectionInfo, exchange: HttpMessage) {
                val resp = exchange as HttpResponse
                if (resp.statusCode != 100) {
                    listener.onResponseHeadersReady(connectionInfo, resp.request!!, resp)
                }
                resp.writeTo(out)
            }

            override fun onBodyBytes(connection: ConnectionInfo, exchange: HttpMessage, type: BodyBytesType, array: ByteArray, offset: Int, length: Int) {
                val resp = exchange as HttpResponse
                listener.onResponseBodyBytes(connection, resp.request!!, resp, type, array, offset, length)
                out.write(array, offset, length)
            }

            override fun onMessageEnded(connectionInfo: ConnectionInfo, exchange: HttpMessage) {
                val resp = exchange as HttpResponse
                listener.onResponseEnded(connectionInfo, resp.request!!, resp)
            }

            override fun onError(connectionInfo: ConnectionInfo, exchange: HttpMessage, error: Exception) {
                val response = exchange as HttpResponse
                listener.onResponseError(connectionInfo, response.request!!, response, error)
            }
        }
        val responseParser = Http1MessageParser(context, HttpMessageType.RESPONSE, requestPipeline)

        pipeIt(source, responseParser, responseParserListener, out)
    }

    private fun pipeIt(source: InputStream, parser: Http1MessageParser, parserListener: HttpMessageListener, destination: OutputStream) {
        val buffer = ByteArray(8192)
        var bytesRead: Int
        try {
            while (source.read(buffer).also { bytesRead = it } != -1) {
                parser.feed(buffer, 0, bytesRead, parserListener)
                destination.flush()
            }
            parser.eof(parserListener)
        } catch (e: EOFException) {
            parser.eof(parserListener)
        } catch (e: Exception) {
            parser.error(e, parserListener)
            throw e
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

private fun Closeable.closeQuietly() {
    try {
        this.close()
    } catch (_: IOException) {
    }
}
private fun Socket.shutdownInputQuietly() {
    try { this.shutdownInput() }
    catch (_: IOException) { }
}
private fun Socket.shutdownOutputQuietly() {
    try { this.shutdownOutput() }
    catch (_: IOException) { }
}

/**
 * Config for the proxy
 */
class InterceptingForwardProxyConfig {

    /**
     * The port to bind to. Default is `0`.
     */
    var port: Int? = null
    /**
     * The address to bind the server to. The default is [InetAddress.getLoopbackAddress] which
     * only allows connections from localhost.
     */
    var bindAddress: InetAddress? = null
    /**
     * Requested maximum queue length for connections on the server socket. Default is `50`.
     */
    var backlog: Int? = null
    /**
     * The executor to use to handle connections on. Note that this is a blocking server and
     * one thread per connection is used. The default is [Executors.newVirtualThreadPerTaskExecutor]
     */
    var executorService: ExecutorService? = null
    /**
     * If `true`, then when [InterceptingForwardProxy.close] is called the executorServer will also be closed. Default is `true`.
     */
    var shutdownExecutorOnClose: Boolean? = null
}


interface ConnectionInfo {
    fun sslContext() : SSLContext
    fun targetAddress() : InetSocketAddress
    fun onClientHandshakeComplete(event: HandshakeCompletedEvent) {
        log.info("Handshake to ${event.socket} for ${targetAddress()} complete with cipher ${event.cipherSuite}")
    }
    fun onTargetHandshakeComplete(event: HandshakeCompletedEvent) {
        log.info("Handshake to ${event.socket} for ${targetAddress()} complete with cipher ${event.cipherSuite}")
    }

    companion object {
        private val log = LoggerFactory.getLogger(ConnectionInfo::class.java)
        @JvmStatic
        fun requestTargetToSocketAddress(requestTarget: String) : InetSocketAddress {
            val bits = requestTarget.split(":")
            if (bits.size != 2) throw IllegalArgumentException("requestTarget is not in format host:port")
            val port = bits[1].toIntOrNull() ?: throw IllegalArgumentException("requestTarget is not in format host:port")
            return InetSocketAddress(bits[0], port)
        }
    }
}

internal object DummyConnectionInfo : ConnectionInfo {
    override fun sslContext(): SSLContext = throw NotImplementedError()
    override fun targetAddress(): InetSocketAddress = throw NotImplementedError()
}
