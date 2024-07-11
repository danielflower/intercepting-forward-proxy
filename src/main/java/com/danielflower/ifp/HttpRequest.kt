package com.danielflower.ifp

import java.io.OutputStream
import java.nio.charset.StandardCharsets

data class HttpRequest(
    var method: String,
    var url: String,
    var httpVersion: String,
    /**
     * Headers where all header names are lowercase
     */
    private val headers: MutableList<Pair<String, String>> = mutableListOf()
) {
    internal fun hasChunkedBody() = hasHeaderValue("transfer-encoding", "chunked")
    fun contentLength(): Long? = header("content-length")?.toLongOrNull()
    fun header(name: String): String? = headers.firstOrNull { it.first == name }?.second
    fun hasHeader(name: String) = headers.any { it.first == name }
    fun addHeader(name: String, value: String) {
        headers.addLast(Pair(name, value))
    }
    fun setHeader(name: String, value: String) {
        headers.removeAll { it.first == name }
        addHeader(name, value)
    }
    fun headers() : List<Pair<String, String>> = headers
    fun isWebsocketUpgrade() = hasHeaderValue("upgrade", "websocket")

    fun hasHeaderValue(name: String, value: String) = header(name) == value

    internal fun writeTo(out: OutputStream) {
        out.write(method.headerBytes())
        out.write(' '.code)
        out.write(url.headerBytes())
        out.write(' '.code)
        out.write(httpVersion.headerBytes())
        out.write(CRLF)
        for (header in headers) {
            out.write(header.first.headerBytes())
            out.write(COLON_SP)
            out.write(header.second.headerBytes())
            out.write(CRLF)
        }
        out.write(CRLF)
    }

    companion object {
        private fun String.headerBytes() = this.toByteArray(StandardCharsets.US_ASCII)
        internal fun empty() = HttpRequest("", "", "")
    }

}