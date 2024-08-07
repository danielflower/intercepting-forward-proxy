package com.danielflower.ifp

import io.muserver.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.net.*
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager

class InterceptingForwardProxyTest {


    @Test
    fun `it can proxy requests without bodies`() {

        val target = anHttpsServer()
            .addHandler(Method.GET, "/hey") { req, resp, _ ->
                resp.write("A target says what? ${req.headers().get("added-by-interceptor")}")
            }
            .start()

        try {
            val listener = object : ConnectionInterceptor {
                override fun acceptConnection(
                    clientSocket: Socket,
                    method: String,
                    requestTarget: String,
                    httpVersion: String
                ) = ConnectionInfoImpl(ConnectionInfo.requestTargetToSocketAddress(requestTarget), serverSslContext)

                override fun onRequestHeadersReady(connection: ConnectionInfo, request: HttpRequest) {
                    request.addHeader("added-by-interceptor", "it was")
                }

                override fun onRequestBodyRawBytes(connection: ConnectionInfo, request: HttpRequest, array: ByteArray, offset: Int, length: Int) {
                    Assertions.assertTrue(false, "Should not be any bytes to proxy")
                }
            }

            InterceptingForwardProxy.start(InterceptingForwardProxyConfig(),  listener).use { proxy ->
                val client = okHttpClient(proxy)
                for (i in 1..2) {
                    client.call(target.uri().resolve("/hey").toRequest()).use { resp ->
                        assertThat(resp.code, equalTo(200))
                        assertThat(resp.body?.string(), equalTo("A target says what? it was"))
                    }
                }
            }
        } finally {
            target.stop()
        }
    }

    @Test
    fun `the destination server can be changed`() {

        // we start target at a local host url
        // then request to somewhere else (example.org url)
        // but return the local target server in the acceptConnection function

        val target = anHttpsServer()
            .addHandler(Method.POST, "/hey") { req, resp, _ ->
                resp.write("Request host is ${req.uri().host} ${req.readBodyAsString()}")
            }
            .start()

        try {
            val listener = object : ConnectionInterceptor {
                override fun acceptConnection(
                    clientSocket: Socket,
                    method: String,
                    requestTarget: String,
                    httpVersion: String
                ) = ConnectionInfoImpl(InetSocketAddress("localhost", target.uri().port), serverSslContext)
            }
            InterceptingForwardProxy.start(InterceptingForwardProxyConfig(),  listener).use { proxy ->
                val client = okHttpClient(proxy)
                val body = "0123456789-".repeat(10)
                client.call(
                    URI.create("https://original-target.example.org:1234/hey").toRequest()
                        .post(body.toRequestBody("text/plain;charset=utf-8".toMediaType()))
                ).use { resp ->
                    assertThat(resp.code, equalTo(200))
                    assertThat(resp.body?.string(), equalTo("Request host is original-target.example.org $body"))
                }
            }
        } finally {
            target.stop()
        }
    }


    @Test
    fun `it can proxy fixed size request bodies`() {

        val target = anHttpsServer()
            .addHandler(Method.POST, "/hey") { req, resp, _ ->
                resp.write("A target says what? ${req.readBodyAsString()}")
            }
            .start()

        try {
            val listener = object : ConnectionInterceptor {
                override fun acceptConnection(
                    clientSocket: Socket,
                    method: String,
                    requestTarget: String,
                    httpVersion: String
                ) = ConnectionInfoImpl.fromTarget(requestTarget)
            }
            InterceptingForwardProxy.start(InterceptingForwardProxyConfig(),  listener).use { proxy ->
                val client = okHttpClient(proxy)
                for (i in 1..2) {
                    val body = "0123456789-".repeat(10000 * i)
                    client.call(
                        target.uri().resolve("/hey").toRequest()
                            .post(body.toRequestBody("text/plain;charset=utf-8".toMediaType()))
                    ).use { resp ->
                        assertThat(resp.code, equalTo(200))
                        assertThat(resp.body?.string(), equalTo("A target says what? $body"))
                    }
                }
            }
        } finally {
            target.stop()
        }
    }

    @Test
    fun `it can proxy chunked request bodies`() {

        val target = anHttpsServer()
            .addHandler(Method.POST, "/hey") { req, resp, _ ->
                resp.write("A target says what? ${req.readBodyAsString()}")
            }
            .start()

        try {
            val listener = object : ConnectionInterceptor {
                override fun acceptConnection(
                    clientSocket: Socket,
                    method: String,
                    requestTarget: String,
                    httpVersion: String
                ) = ConnectionInfoImpl.fromTarget(requestTarget)
            }
            InterceptingForwardProxy.start(InterceptingForwardProxyConfig(),  listener).use { proxy ->
                val client = okHttpClient(proxy)
                for (i in 1..2) {
                    val body = "0123456789".repeat(10000 * i)
                    client.call(
                        target.uri().resolve("/hey").toRequest()
                            .post(object : RequestBody() {
                                override fun contentType() = "text/plain;charset=utf-8".toMediaType()
                                override fun writeTo(sink: BufferedSink) {
                                    sink.writeString("你好 ", StandardCharsets.UTF_8)
                                    sink.flush()
                                    sink.writeString(body, StandardCharsets.UTF_8)
                                    sink.flush()
                                    sink.writeString(" The final chunk", StandardCharsets.UTF_8)
                                    sink.flush()
                                }
                            })
                    ).use { resp ->
                        assertThat(resp.code, equalTo(200))
                        assertThat(
                            resp.body?.string(),
                            equalTo("A target says what? 你好 $body The final chunk")
                        )
                    }
                }
            }
        } finally {
            target.stop()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [ true, false ])
    fun `it can proxy chunked request bodies with trailers`(oneCharAtATime: Boolean) {
        val responseDate = Mutils.toHttpDate(Date())

        val target = anHttpsServer()
            .addHandler(Method.POST, "/hey") { req, resp, _ ->
                resp.headers().set(HeaderNames.DATE, responseDate)
                resp.write("A target says what? ${req.readBodyAsString()}\n")
            }
            .start()

        val rawBodyBytes = ByteArrayOutputStream()
        val contentBodyBytes = ByteArrayOutputStream()
        val requestBodyContents = mutableListOf<String>()
        try {
            val listener = object : ConnectionInterceptor {
                override fun acceptConnection(
                    clientSocket: Socket,
                    method: String,
                    requestTarget: String,
                    httpVersion: String
                ) = ConnectionInfoImpl.fromTarget(requestTarget)

                override fun onRequestBodyRawBytes(connection: ConnectionInfo, request: HttpRequest, array: ByteArray, offset: Int, length: Int) {
                    rawBodyBytes.write(array, offset, length)
                }

                override fun onRequestBodyContentBytes(connection: ConnectionInfo, request: HttpRequest, array: ByteArray, offset: Int, length: Int) {
                    contentBodyBytes.write(array, offset, length)
                }

                override fun onRequestEnded(request: HttpRequest) {
                    requestBodyContents.add(contentBodyBytes.toString(StandardCharsets.UTF_8))
                    contentBodyBytes.reset()
                }
            }
            InterceptingForwardProxy.start(InterceptingForwardProxyConfig(),  listener).use { proxy ->

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
                val plainTextSocket = Socket(proxy.address().address, proxy.address().port)
                plainTextSocket.inputStream.use { plaintextInputStream ->
                    plainTextSocket.outputStream.use { plainTextOutputStream ->

                        (sslContext.socketFactory.createSocket(
                            plainTextSocket,
                            plaintextInputStream,
                            false
                        ) as SSLSocket).use { clientSocket ->
                            clientSocket.useClientMode = true

                            plainTextOutputStream.write("CONNECT localhost:${target.uri().port} HTTP/1.1\r\n".ascii())
                            plainTextOutputStream.flush()

                            val expectedConnectResp = "HTTP/1.1 200 Connection Established\r\n\r\n"
                            val connectResp = String(
                                plaintextInputStream.readNBytes(expectedConnectResp.length),
                                StandardCharsets.US_ASCII
                            )
                            assertThat(connectResp, equalTo(expectedConnectResp))

                            clientSocket.outputStream.use { sslOutputStream ->
                                clientSocket.inputStream.use { sslInputStream ->
                                    val chunkedRequest = """
                                        POST /hey HTTP/1.1
                                        Host: ${target.uri().authority}
                                        transfer-encoding: chunked
                                        Trailer: Expires, X-Signature
                                        
                                        4
                                        Wiki
                                        5;name="some value"
                                        pedia
                                        0
                                        Expires: "Wed, 21 Oct 2020 07:28:00 GMT"
                                        X-Signature: abc123
                                        
                                        POST /hey HTTP/1.1
                                        Host: ${target.uri().authority}
                                        transfer-encoding: chunked
                                        
                                        4;charset=utf8
                                        Wiki
                                        5
                                        pedia
                                        0
                                        
                                        
                                        """.trimIndent().replace("\n", "\r\n")

                                    if (oneCharAtATime) {
                                        val ascii = chunkedRequest.ascii()
                                        for (byte in ascii) {
                                            sslOutputStream.write(byteArrayOf(byte))
                                            sslOutputStream.flush()
                                        }
                                    } else {
                                        sslOutputStream.write(chunkedRequest.ascii())
                                        sslOutputStream.flush()
                                    }
                                    Thread.sleep(1000)

                                    assertThat(
                                        rawBodyBytes.toString(StandardCharsets.UTF_8), equalTo(
                                            """
                                    4
                                    Wiki
                                    5;name="some value"
                                    pedia
                                    0
                                    Expires: "Wed, 21 Oct 2020 07:28:00 GMT"
                                    X-Signature: abc123
                                    
                                    4;charset=utf8
                                    Wiki
                                    5
                                    pedia
                                    0
                                    

                                    """.trimIndent().replace("\n", "\r\n")
                                        )
                                    )

                                    assertThat(requestBodyContents, contains("Wikipedia", "Wikipedia"))


                                    sslInputStream.bufferedReader().use { reader ->
                                        var line = reader.readLine()

                                        val lines = mutableListOf<String>()
                                        lines.addLast(line)

                                        while (line != null && lines.size < 12) {
                                            line = reader.readLine()
                                            lines.addLast(line)
                                        }
                                        assertThat(
                                            lines, Matchers.contains(
                                                equalTo("HTTP/1.1 200 OK"),
                                                equalTo("date: $responseDate"),
                                                equalTo("content-type: text/plain;charset=utf-8"),
                                                equalTo("content-length: 30"),
                                                equalTo(""),
                                                equalTo("A target says what? Wikipedia"),
                                                equalTo("HTTP/1.1 200 OK"),
                                                equalTo("date: $responseDate"),
                                                equalTo("content-type: text/plain;charset=utf-8"),
                                                equalTo("content-length: 30"),
                                                equalTo(""),
                                                equalTo("A target says what? Wikipedia"),
                                            )
                                        )
                                    }

                                }
                            }

                        }
                    }
                }
            }
        } finally {
            target.stop()
        }
    }


    @Test
    fun `it can proxy web sockets`() {

        val actual = StringBuffer()
        val onSendBytesCount = AtomicInteger(0)
        val messagesReceivedLatch = CountDownLatch(3)

        val target = anHttpsServer()
            .addHandler(WebSocketHandlerBuilder.webSocketHandler()
                .withPath("/somepath/")
                .withWebSocketFactory { _, _ ->
                    object : BaseWebSocket() {
                        override fun onText(message: String, isLast: Boolean, onComplete: DoneCallback?) {
                            session().sendText(message.uppercase(), onComplete)
                        }

                        override fun onBinary(buffer: ByteBuffer, isLast: Boolean, onComplete: DoneCallback?) {
                            val text = buffer.toByteString().string(StandardCharsets.UTF_8)
                            session().sendText(text, onComplete)
                        }
                    }
                }
            )
            .start()

        val bigText = "一. yī. one · 二. èr. two · 三. sān. three · 四. sì. four · 五. wǔ. five · 六. liù. six"
            .repeat(200)

        try {
            val listener = object : ConnectionInterceptor {
                override fun acceptConnection(
                    clientSocket: Socket,
                    method: String,
                    requestTarget: String,
                    httpVersion: String
                ) = ConnectionInfoImpl.fromTarget(requestTarget)

                override fun onRequestHeadersReady(connection: ConnectionInfo, request: HttpRequest) {
                    actual.append("websocket? ${request.isWebsocketUpgrade()}\n")
                }

                override fun onRequestBodyRawBytes(connection: ConnectionInfo, request: HttpRequest, array: ByteArray, offset: Int, length: Int) {
                    onSendBytesCount.incrementAndGet()
                }
            }

            InterceptingForwardProxy.start(InterceptingForwardProxyConfig(),  listener).use { proxy ->
                val client = okHttpClient(proxy)
                val wssUrl = target.uri().toString().replaceFirst("http", "ws") + "/somepath/"
                val req = Request.Builder().url(wssUrl).build()
                val newWebSocket = client.newWebSocket(req, object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        actual.append("Got back $text\n")
                        messagesReceivedLatch.countDown()
                    }
                })
                newWebSocket.send("Well hello there, ")
                newWebSocket.send("world")
                newWebSocket.send(bigText.encodeUtf8())
                messagesReceivedLatch.await(20, TimeUnit.SECONDS)
                newWebSocket.close(1000, "Finished")
            }
        } finally {
            target.stop()
        }
        assertThat(actual.toString(), equalTo("""
            websocket? true
            Got back WELL HELLO THERE, 
            Got back WORLD
            Got back $bigText
            
        """.trimIndent()))
        assertThat(onSendBytesCount.get(), greaterThanOrEqualTo(3))
    }

    @Test
    fun `the onEnded callback happens at the end`() {

        val target = anHttpsServer()
            .addHandler(Method.GET, "/hey") { req, resp, _ ->
                resp.write("A target says what?")
            }
            .start()

        val onEndCalled = CountDownLatch(1)
        var onEndClientToTargetException : Exception? = null
        var onEndTargetToClientException : Exception? = null

        try {
            val listener = object : ConnectionInterceptor {
                override fun acceptConnection(
                    clientSocket: Socket,
                    method: String,
                    requestTarget: String,
                    httpVersion: String
                ) = ConnectionInfoImpl(ConnectionInfo.requestTargetToSocketAddress(requestTarget), serverSslContext)

                override fun onConnectionEnded(
                    connection: ConnectionInfo,
                    clientToTargetException: Exception?,
                    targetToClientException: Exception?
                ) {
                    onEndClientToTargetException = clientToTargetException
                    onEndTargetToClientException = targetToClientException
                    onEndCalled.countDown()
                }
            }

            InterceptingForwardProxy.start(InterceptingForwardProxyConfig(),  listener).use { proxy ->
                javaNetHttpClient(proxy).use { client ->
                    for (i in 1..2) {
                        val resp = client.send(java.net.http.HttpRequest.newBuilder(target.uri().resolve("/hey")).build(), HttpResponse.BodyHandlers.ofString())
                        assertThat(resp.statusCode(), equalTo(200))
                        assertThat(resp.body(), equalTo("A target says what?"))
                    }
                }
            }
        } finally {
            target.stop()
        }

        assertThat(onEndCalled.await(10, TimeUnit.SECONDS), equalTo(true))
        assertThat(onEndClientToTargetException, nullValue())
        // TODO: why is this not closing gracefully?
        //assertThat(onEndTargetToClientException, nullValue())

    }





    companion object {
        val trustManager = InterceptingForwardProxy.createTrustManager(
            classpathPath = "/test-certs/ca.p12",
            password = "password".toCharArray()
        )
        val serverSslContext = InterceptingForwardProxy.createSSLContext(
            classpathPath = "/test-certs/proxy-server.p12",
            password = "password".toCharArray(),
            trustManager = trustManager
        )

        fun okHttpClient(proxy: InterceptingForwardProxy): OkHttpClient {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }
                .proxy(Proxy(Proxy.Type.HTTP, proxy.address()))
                .build()
        }

        fun javaNetHttpClient(proxy: InterceptingForwardProxy): HttpClient {
            System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true")
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
            return HttpClient.newBuilder()
                .sslContext(sslContext)
                .proxy(ProxySelector.of(proxy.address()))
                .build()
        }

        data class ConnectionInfoImpl(
            val targetAddress: InetSocketAddress,
            val sslContext: SSLContext,
        ) : ConnectionInfo {
            override fun sslContext() = sslContext
            override fun targetAddress() = targetAddress
            companion object {
                fun fromTarget(target: String) = ConnectionInfoImpl(ConnectionInfo.requestTargetToSocketAddress(target), serverSslContext)

            }
        }
    }
}

fun URI.toRequest() = Request.Builder().url(this.toURL())
fun OkHttpClient.call(request: Request.Builder) = this.newCall(request.build()).execute()
fun String.ascii() = this.toByteArray(StandardCharsets.US_ASCII)
fun anHttpsServer(): MuServerBuilder = MuServerBuilder.httpsServer()
    .withHttpsConfig(
        HttpsConfigBuilder.httpsConfig()
            .withKeystoreFromClasspath("/test-certs/target-server.p12")
            .withKeystoreType("PKCS12")
            .withKeystorePassword("password")
            .withKeyPassword("password")
    )


