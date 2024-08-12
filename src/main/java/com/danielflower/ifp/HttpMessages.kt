package com.danielflower.ifp

import com.danielflower.ifp.HttpHeaders.Companion.headerBytes
import java.io.OutputStream
import java.nio.charset.StandardCharsets

data class HttpHeaders(
    /**
     * Headers where all header names are lowercase
     */
    private val headers: MutableList<Pair<String, String>> = mutableListOf(),
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
    fun hasHeaderValue(name: String, value: String) = header(name) == value

    internal fun writeTo(out: OutputStream) {
        for (header in headers) {
            out.write(header.first.headerBytes())
            out.write(COLON_SP)
            out.write(header.second.headerBytes())
            out.write(CRLF)
        }
    }
    companion object {
        internal fun String.headerBytes() = this.toByteArray(StandardCharsets.US_ASCII)
    }

}

internal sealed interface HttpExchange {
    var httpVersion: String
    fun headers() : HttpHeaders
}

data class HttpRequest(
    var method: String,
    var url: String,
    override var httpVersion: String,
    private val headers: HttpHeaders = HttpHeaders(),
) : HttpExchange {
    fun isWebsocketUpgrade() = headers.hasHeaderValue("upgrade", "websocket")

    internal fun writeTo(out: OutputStream) {
        out.write(method.headerBytes())
        out.write(' '.code)
        out.write(url.headerBytes())
        out.write(' '.code)
        out.write(httpVersion.headerBytes())
        out.write(CRLF)
        headers.writeTo(out)
        out.write(CRLF)
    }

    companion object {
        internal fun empty() = HttpRequest("", "", "")
    }

    override fun headers() = headers

}

data class HttpResponse(
    override var httpVersion: String,
    var statusCode: Int,
    var reason: String,
    private val headers: HttpHeaders = HttpHeaders(),
) : HttpExchange {

    internal fun writeTo(out: OutputStream) {
        out.write(httpVersion.headerBytes())
        out.write(' '.code)
        out.write(statusCode.toString().headerBytes())
        out.write(' '.code)
        out.write(reason.headerBytes())
        out.write(CRLF)
        headers.writeTo(out)
        out.write(CRLF)
    }
    override fun headers() = headers

    companion object {
        internal fun empty() = HttpResponse("", 0, "")
    }

}