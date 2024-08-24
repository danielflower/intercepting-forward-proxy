// This is the code used in the README.md

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
            public void onRequestBodyBytes(ConnectionInfo connection, HttpRequest request, BodyBytesType type, byte[] array, int offset, int length) {
                // This includes all bytes of a request body, including things like chunked encoding markings. To inspect only
                // the actual body content, only bytes where the type parameter is BodyBytesType.CONTENT can be used.
                System.out.println("Going to send " + length + " bytes of type " + type + " to the target");
            }

            @Override
            public void onRequestEnded(ConnectionInfo connection, HttpRequest request) {
                System.out.println("request completed: " + request);
            }

            @Override
            public void onResponseHeadersReady(ConnectionInfo connection, HttpRequest request, HttpResponse response) {
                System.out.println("Response headers: " + response);
            }

            @Override
            public void onResponseBodyBytes(ConnectionInfo connection, HttpRequest request, HttpResponse response, BodyBytesType type, byte[] array, int offset, int length) {
                // This includes all bytes of a response body, including things like chunked encoding markings. To inspect only
                // the actual body content, only bytes where the type parameter is BodyBytesType.CONTENT can be used.
                System.out.println("Response bytes of type " + type + " sending to client: " + length);
            }

            @Override
            public void onResponseEnded(ConnectionInfo connection, HttpRequest request, HttpResponse response) {
                System.out.println("Response ended: " + response);
            }

            @Override
            public void onResponseError(ConnectionInfo connection, HttpRequest request, HttpResponse response, Exception error) {
                System.out.println("Error while proxying response to " + request + ": " + error);
            }

            @Override
            public void onRequestError(ConnectionInfo connection, HttpRequest request, Exception error) {
                System.out.println("Error while proxying request " + request + ": " + error);
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