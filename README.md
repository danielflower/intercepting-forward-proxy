Intercepting Forward Proxy
==========================

A Java library that allows you to create a forward proxy for HTTPS connections. You can inspect outgoing requests,
alter the request parameters (method, path, and headers), inspect the request bodies, and decide whether or not to
accept connections to destinations.

Usage
-----

Add the dependency:

```xml
<dependency>
    <groupId>com.danielflower</groupId>
    <artifactId>intercepting-forward-proxy</artifactId>
    <version>RELEASE</version>
</dependency>
```

And then start a server:

```java
import com.danielflower.ConnectionInterceptor;
import com.danielflower.HttpRequest;
import com.danielflower.InterceptingForwardProxy;

import javax.net.ssl.TrustManagerFactory;
import java.net.Socket;
import java.security.KeyStore;

public class MyForwardProxy {

    public static void main(String[] args) throws Exception {

        // You need to provide an SSL Context - in this example a PKCS12 cert is loaded from the classpath
        // and the default trust manager is used.
        var trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init((KeyStore)null);
        var sslContext = InterceptingForwardProxy.createSSLContext("PKCS12", "/test-certs/proxy-server.p12", 
                "password".toCharArray(), trustManagerFactory.getTrustManagers()[0]);

        // Start the proxy
        var proxy = InterceptingForwardProxy.start(2048, sslContext, new ConnectionInterceptor() {
            public boolean acceptConnection(Socket socket, String method, String requestTarget, String httpVersion) {
                // here you can decide whether or not to accept a connection from a client wanting to connect to a
                // certain target
                System.out.println("Got connection from " + socket.getRemoteSocketAddress() + " to " + requestTarget);
                return true;
            }
            
            public void onRequestHeadersReady(HttpRequest request) {
                // here you can look at requests and do things like inspect or change request headers
                System.out.println("Proxying request " + request);
            }

            public void onBytesToProxy(byte[] array, int offset, int length) {
                // here you can inspect request body data
                System.out.println("Sending " + length + " request body bytes");
            }
        });
        System.out.println("Started proxy at " + proxy.address());
        Runtime.getRuntime().addShutdownHook(new Thread(proxy::close));
    }
}
```

Example cURL request:

    curl -kis --proxy http://localhost:2048 https://example.org

Note that this is performing man-in-the-middle interception on HTTPS calls. Therefore:

* You need an SSL context which provides an SSL certificate the clients use to connect
* Clients will need to trust the CA that signed the certificate (or, which is not recommended, disable TLS verification)
  in order to connect to the proxy.
* The proxy's SSL context needs to trust whichever CA(s) signed target calls.

Note the following limitations:

* It requires Java 21 or later
* It only supports HTTPS targets
* It does not use non-blocking IO, and each connection is bound to a thread (however this is mitigated by using
  virtual threads by default).
