package com.danielflower

import java.net.Socket

/**
 * Listeners to allow introspection and interception of requests.
 *
 * Implement this to do things such as change request headers, allow or deny certain connections, or
 * inspect request body data.
 */
interface ConnectionInterceptor {

    /**
     * Called when a request line and headers have been read, before it is forwarded to the target server.
     *
     * Any changes made to the request, for example adding a header, will be reflected in the request sent
     * to the target server.
     *
     * Note that for a request with a body, such as a POST with data, this is sent before the request body
     * is read.
     */
    fun onRequestHeadersReady(request: HttpRequest) {}

    /**
     * Called before request body bytes are being sent to the target server.
     *
     * Note this is raw data, e.g. it may contain HTTP chunked encoding markers, and trailers.
     */
    fun onBytesToProxy(array: ByteArray, offset: Int, length: Int) {}

    /**
     * Called when a new connection is made from a client socket.
     *
     * Return false to not accept the connection (the connection will the reset).
     */
    fun acceptConnection(clientSocket: Socket, method: String, requestTarget: String, httpVersion: String): Boolean = true

}