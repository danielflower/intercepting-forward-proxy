package com.danielflower.ifp

import io.muserver.HttpsConfigBuilder
import io.muserver.Method
import io.muserver.MuServerBuilder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager

class InterceptingForwardProxyTest {

    private val trustManager = InterceptingForwardProxy.createTrustManager(
        classpathPath = "/test-certs/ca.p12",
        password = "password".toCharArray()
    )
    private val serverSslContext = InterceptingForwardProxy.createSSLContext(
        classpathPath = "/test-certs/proxy-server.p12",
        password = "password".toCharArray(),
        trustManager = trustManager
    )

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
                ) = serverSslContext

                override fun onRequestHeadersReady(request: HttpRequest) {
                    request.addHeader("added-by-interceptor", "it was")
                }

                override fun onBytesToProxy(array: ByteArray, offset: Int, length: Int) {
                    Assertions.assertTrue(false, "Should not be any bytes to proxy")
                }
            }

            InterceptingForwardProxy.start(connectionInterceptor = listener).use { proxy ->
                val client = okHttpClient(proxy)
                for (i in 1..2) {
                    client.call(target.uri().resolve("/hey").toRequest()).use { resp ->
                        MatcherAssert.assertThat(resp.code, Matchers.equalTo(200))
                        MatcherAssert.assertThat(resp.body?.string(), Matchers.equalTo("A target says what? it was"))
                    }
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
                ) = serverSslContext
            }
            InterceptingForwardProxy.start(connectionInterceptor = listener).use { proxy ->
                val client = okHttpClient(proxy)
                for (i in 1..2) {
                    val body = "0123456789-".repeat(10000 * i)
                    client.call(
                        target.uri().resolve("/hey").toRequest()
                            .post(body.toRequestBody("text/plain;charset=utf-8".toMediaType()))
                    ).use { resp ->
                        MatcherAssert.assertThat(resp.code, Matchers.equalTo(200))
                        MatcherAssert.assertThat(resp.body?.string(), Matchers.equalTo("A target says what? $body"))
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
                ) = serverSslContext
            }
            InterceptingForwardProxy.start(connectionInterceptor = listener).use { proxy ->
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
                        MatcherAssert.assertThat(resp.code, Matchers.equalTo(200))
                        MatcherAssert.assertThat(
                            resp.body?.string(),
                            Matchers.equalTo("A target says what? 你好 $body The final chunk")
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

        val target = anHttpsServer()
            .addHandler(Method.POST, "/hey") { req, resp, _ ->
                resp.write("A target says what? ${req.readBodyAsString()}\n")
            }
            .start()

        try {
            val listener = object : ConnectionInterceptor {
                override fun acceptConnection(
                    clientSocket: Socket,
                    method: String,
                    requestTarget: String,
                    httpVersion: String
                ) = serverSslContext
            }
            InterceptingForwardProxy.start(connectionInterceptor = listener).use { proxy ->

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
                            MatcherAssert.assertThat(connectResp, Matchers.equalTo(expectedConnectResp))

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

                                    sslInputStream.bufferedReader().use { reader ->
                                        var line = reader.readLine()

                                        val lines = mutableListOf<String>()
                                        lines.addLast(line)

                                        while (line != null && lines.size < 12) {
                                            line = reader.readLine()
                                            lines.addLast(line)
                                        }
                                        MatcherAssert.assertThat(
                                            lines, Matchers.contains(
                                                Matchers.equalTo("HTTP/1.1 200 OK"),
                                                Matchers.startsWith("date: "),
                                                Matchers.equalTo("content-type: text/plain;charset=utf-8"),
                                                Matchers.equalTo("content-length: 30"),
                                                Matchers.equalTo(""),
                                                Matchers.equalTo("A target says what? Wikipedia"),
                                                Matchers.equalTo("HTTP/1.1 200 OK"),
                                                Matchers.startsWith("date: "),
                                                Matchers.equalTo("content-type: text/plain;charset=utf-8"),
                                                Matchers.equalTo("content-length: 30"),
                                                Matchers.equalTo(""),
                                                Matchers.equalTo("A target says what? Wikipedia"),
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


    private fun okHttpClient(proxy: InterceptingForwardProxy): OkHttpClient {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .proxy(Proxy(Proxy.Type.HTTP, proxy.address()))
            .build()
    }


    private fun anHttpsServer(): MuServerBuilder = MuServerBuilder.httpsServer()
        .withHttpsConfig(
            HttpsConfigBuilder.httpsConfig()
                .withKeystoreFromClasspath("/test-certs/target-server.p12")
                .withKeystoreType("PKCS12")
                .withKeystorePassword("password")
                .withKeyPassword("password")
        )


}

private fun URI.toRequest() = Request.Builder().url(this.toURL())
private fun OkHttpClient.call(request: Request.Builder) = this.newCall(request.build()).execute()
private fun String.ascii() = this.toByteArray(StandardCharsets.US_ASCII)
