package com.danielflower

import io.muserver.HttpsConfigBuilder
import io.muserver.MuServerBuilder
import io.muserver.MuServerBuilder.httpsServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager


class InterceptingForwardProxyTest {


    @Test
    fun `it can proxy stuff`() {


        val target = anHttpsServer()
            .addHandler { req, resp ->
                println("Got $req with ${req.headers()}")
                resp.status(200)
                resp.write("A target says what? ${req.headers().get("added-by-interceptor")}")
                true
            }
            .start()

        try {
            val listener = object : RequestStreamListener {
                override fun onRequestHeadersReady(request: HttpRequest) {
                    request.addHeader("added-by-interceptor", "it was")
                }

                override fun onBytesToProxy(array: ByteArray, offset: Int, length: Int) {
                }
            }

            InterceptingForwardProxy.start(port = 0, bindAddress = InetAddress.getLocalHost(), listener).use { proxy ->

                val trustManager = InterceptingForwardProxy.createTrustManager()
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustManager), null)

                val client = OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.socketFactory, trustManager)
                    .hostnameVerifier { _, _ -> true }
                    .proxy(Proxy(Proxy.Type.HTTP, proxy.address()))
                    .build()

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

    private fun anHttpsServer(): MuServerBuilder = httpsServer()
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