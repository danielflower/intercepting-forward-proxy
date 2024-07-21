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
import com.danielflower.ifp.*;
import javax.net.ssl.HandshakeCompletedEvent;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

public class MyForwardProxy {

  public static void main(String[] args) {

    var config = new InterceptingForwardProxyConfig();
    config.setPort(2048);
    var proxy = InterceptingForwardProxy.start(config, new ConnectionInterceptor() {

      @Override
      public ConnectionInfo acceptConnection(Socket socket, String method, String requestTarget, String httpVersion) {
        System.out.println("Got connection from " + socket.getRemoteSocketAddress() + " to " + requestTarget);

        // Based on the parameters, we can decide to accept this connection by returning an SSLContext
        // or else return null to reject the connection.

        // The SSL Context is used to both provide a certificate back to the client, and as the source of
        // the trust store to use when verifying SSL certificates of target services.

        // Note that the clients need to opt in to being intercepted by this proxy by trusting the certificate
        // that is returned here.

        // In this example we accept and provide an SSL Context from the classpath. Real implementations
        // should probably be cached.
        TrustManagerFactory trustManagerFactory;
        try {
          trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
          trustManagerFactory.init((KeyStore) null);
        } catch (NoSuchAlgorithmException | KeyStoreException e) {
          throw new RuntimeException("Error setting up keystore", e);
        }
        SSLContext context = InterceptingForwardProxy.createSSLContext("PKCS12",
                "/test-certs/proxy-server.p12", "password".toCharArray(), trustManagerFactory.getTrustManagers()[0]);
        // Specify the target - here we use the target requested by the client but we can send connections wherever
        // we want.
        InetSocketAddress targetAddress = ConnectionInfo.requestTargetToSocketAddress(requestTarget);
        return new MyConnectionInfo(context, targetAddress);
      }

      @Override
      public void onRequestHeadersReady(ConnectionInfo connection, HttpRequest request) {
        System.out.println("Proxying connection " + connection + " for request " + request);
      }

      @Override
      public void onBytesToProxy(ConnectionInfo connection, HttpRequest request, byte[] array, int offset, int length) {
        System.out.println("Sending " + length + " request body bytes");
      }

      @Override
      public void onConnectionEnded(ConnectionInfo connection, Exception clientToTargetException, Exception targetToClientException) {
        System.out.println("Connection ended");
      }

    });
    System.out.println("Started proxy at " + proxy.address());
    Runtime.getRuntime().addShutdownHook(new Thread(proxy::close));
  }

  record MyConnectionInfo(SSLContext sslContext, InetSocketAddress targetAddress) implements ConnectionInfo {
    @Override
    public void onClientHandshakeComplete(HandshakeCompletedEvent event) {
      System.out.println("Client TLS connection with " + event.getCipherSuite());
    }
    @Override
    public void onTargetHandshakeComplete(HandshakeCompletedEvent event) {
      System.out.println("Target TLS connection with " + event.getCipherSuite());
    }
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
* HTTP connections use HTTP 1.1, even if the target supports H2
* It only supports HTTPS targets
* It does not use non-blocking IO, and each connection is bound to a thread (however this is mitigated by using
  virtual threads by default).
