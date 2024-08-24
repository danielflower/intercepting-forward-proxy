package com.danielflower.ifp

import java.net.Socket

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
     * @return Connection info to use if the connection should be accepted; otherwise `null` to reject the connection.
     */
    fun acceptConnection(clientSocket: Socket, method: String, requestTarget: String, httpVersion: String): ConnectionInfo?

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
     * This includes all possible data in a request body, e.g. the content bytes and chunked encoding markers. If you wish
     * to just inspect the content of a request, then check that [type] is [BodyBytesType.CONTENT].
     */
    fun onRequestBodyBytes(connection: ConnectionInfo, request: HttpRequest, type: BodyBytesType, array: ByteArray, offset: Int, length: Int) {}

    /**
     * Called when an HTTP request message has ended (this does not imply anything about the response which will usually
     * not be completed).
     */
    fun onRequestEnded(connection: ConnectionInfo, request: HttpRequest) {}

    /**
     * Called when the connection ends.
     *
     * @param connection The connection that is ended
     * @param clientToTargetException `null` if the connection was shut down gracefully, otherwise an exception from piping
     * bytes from the client to the target
     * @param targetToClientException `null` if the connection was shut down gracefully, otherwise an exception from piping
     * bytes from the target to the client
     */
    fun onConnectionEnded(
        connection: ConnectionInfo,
        clientToTargetException: Exception?,
        targetToClientException: Exception?
    ) {}

    /**
     * Called before the response headers are sent back to the client.
     *
     * This callback can alter the values in the [response] parameter to modify the response status code and headers.
     */
    fun onResponseHeadersReady(connection: ConnectionInfo, request: HttpRequest, response: HttpResponse) {}

    /**
     * Called before response body bytes are being sent back to the client.
     *
     * This includes all possible data in a response body, e.g. the content bytes and chunked encoding markers. If you wish
     * to just inspect the content of a request, then check that [type] is [BodyBytesType.CONTENT].
     */
    fun onResponseBodyBytes(connection: ConnectionInfo, request: HttpRequest, response: HttpResponse, type: BodyBytesType, array: ByteArray, offset: Int, length: Int) {}

    /**
     * Called after a response has ended.
     */
    fun onResponseEnded(connection: ConnectionInfo, request: HttpRequest, response: HttpResponse) {}

    /**
     * Called when a request is not completed, for example the client or target disconnects before the request is completed
     */
    fun onRequestError(connection: ConnectionInfo, request: HttpRequest, error: Exception) {}

    /**
     * Called when a request is not completed, for example the client or target disconnects before the request is completed
     */
    fun onResponseError(connection: ConnectionInfo, request: HttpRequest, response: HttpResponse, error: Exception) {}


}

