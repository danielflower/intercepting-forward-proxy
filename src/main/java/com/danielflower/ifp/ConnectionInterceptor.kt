package com.danielflower.ifp

import java.net.Socket
import javax.net.ssl.SSLContext

/**
 * Listeners to allow introspection and interception of requests.
 *
 * Implement this to do things such as change request headers, allow or deny certain connections, or
 * inspect request body data.
 */
interface ConnectionInterceptor {

    /**
     * Called when a new connection is made from a client socket. In order to accept a connection, an SSL context
     * for SSL connections needs to be returned. If `null` is returned, then the connection is aborted.
     *
     * The SSLContext is used for two purposes:
     *
     * * it provides the trust manager used when connecting to the target server
     * * it provides the certificate back to the client
     *
     * Note that this proxy performs man-in-the-middle interception on a TLS connection, therefore the client will
     * only allow this connection to continue if they trust the certificate returned from this method. Therefore you
     * cannot intercept TLS connections unless clients opt-in by trusting the certs.
     *
     * @param clientSocket the socket that has made the connection, pre-handshake
     * @param method the method used, which is always CONNECT
     * @param requestTarget the target server, which is generally of the format `host:port`
     * @param httpVersion the HTTP version used which is either `HTTP/1.1` or `HTTP/1.0`
     * @return The SSL Connection to use if the connection should be accepted; otherwise `null` to reject the connection.
     */
    fun acceptConnection(clientSocket: Socket, method: String, requestTarget: String, httpVersion: String): SSLContext?

    /**
     * Called when a request line and headers have been read, before it is forwarded to the target server.
     *
     * Any changes made to the request, for example adding a header, will be reflected in the request sent
     * to the target server.
     *
     * Note that for a request with a body, such as a POST with data, this is sent before the request body
     * is read.
     */
    fun onRequestHeadersReady(connection: ConnectionInfo, request: HttpRequest) {}

    /**
     * Called before request body bytes are being sent to the target server.
     *
     * Note this is raw data, e.g. it may contain HTTP chunked encoding markers, and trailers.
     */
    fun onBytesToProxy(connection: ConnectionInfo, request: HttpRequest, array: ByteArray, offset: Int, length: Int) {}


}