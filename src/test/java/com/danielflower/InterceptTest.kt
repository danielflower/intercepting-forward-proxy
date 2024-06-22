package com.danielflower

import io.muserver.HttpsConfigBuilder
import io.muserver.MuServerBuilder.httpsServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.Proxy
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager


class InterceptTest {


    @Test
    fun `it can proxy stuff`() {


        val target = httpsServer()
            .withHttpsConfig(HttpsConfigBuilder.httpsConfig()
                .withKeystoreFromClasspath("/test-certs/target-server.p12")
                .withKeystoreType("PKCS12")
                .withKeystorePassword("password")
                .withKeyPassword("password")
            )
            .addHandler { req, resp ->
                println("Got $req with ${req.headers()}")
                resp.status(200)
                resp.write("A target says what?")
                true
            }
            .start()

        try {
            InterceptingForwardProxy.start(port = 0, bindAddress = InetAddress.getLocalHost()).use { proxy ->

                val trustManager = InterceptingForwardProxy.createTrustManager()
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustManager), null)

                val client = OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.socketFactory, trustManager)
                    .hostnameVerifier { hostname, session ->
                        println("hostname=$hostname")
                        true
                    }
                    .proxy(Proxy(Proxy.Type.HTTP, proxy.address()))
                    .build()

                for (i in 1..2) {
                    client.newCall(
                        Request.Builder()
                            .url(target.uri().resolve("/hey").toURL())
                            .build()
                    ).execute().use { resp ->
                        assertThat(resp.code, equalTo(200))
                        assertThat(resp.body?.string(), equalTo("A target says what?"))
                    }
                }
            }
        } finally {
            target.stop()

        }
    }



}