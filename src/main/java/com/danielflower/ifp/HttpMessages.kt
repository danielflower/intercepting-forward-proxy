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
    fun getAll(name: String): List<String> = headers.filter { it.first == name }.map { it.second }
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

internal sealed interface HttpMessage {
    var httpVersion: String
    fun headers() : HttpHeaders
    fun bodyTransferSize() : BodySize
    companion object {
        internal fun fixedBodyLength(cl: List<String>): BodySize {
            if (cl.size > 1) {
                // 6.3.5 but ignoring the fact there may be multiple with all the same value
                throw IllegalStateException("Multiple content-length headers")
            }
            // 6.3.5
            val len = try {
                cl[0].toLong()
            } catch (e: NumberFormatException) {
                throw IllegalStateException("Invalid content-length")
            }
            if (len < 0L) throw IllegalStateException("Negative content length $len")
            // 6.3.6
            return if (len == 0L) BodySize.NONE else BodySize(BodyType.FIXED_SIZE, len)
        }
    }

}

/**
 * The type of request or response body
 */
enum class BodyType {

    /**
     * The size of the body is known
     */
    FIXED_SIZE,

    /**
     * The body is sent with `transfer-encoding: chunked`
     */
    CHUNKED,

    /**
     * There is a body, but the size is not known and it is not chunked, so the body are all bytes until the connection closes
     */
    UNSPECIFIED,

    /**
     * There is no body
     */
    NONE
}
data class BodySize(val type: BodyType, val bytes: Long?) {
    companion object {
        val NONE = BodySize(BodyType.NONE, null)
        val CHUNKED = BodySize(BodyType.CHUNKED, null)
        val UNSPECIFIED = BodySize(BodyType.UNSPECIFIED, null)
    }
}

data class HttpRequest(
    var method: String,
    var url: String,
    override var httpVersion: String,
    private val headers: HttpHeaders = HttpHeaders(),
) : HttpMessage {
    fun isWebsocketUpgrade() = headers.hasHeaderValue("upgrade", "websocket")

    override fun bodyTransferSize() : BodySize {
        // numbers referring to sections in https://httpwg.org/specs/rfc9112.html#message.body.length
        val cl = headers.getAll("content-length")
        val isChunked = headers.hasChunkedBody()
        // 6.3.3
        if (isChunked && cl.isNotEmpty()) throw IllegalStateException("A request has chunked encoding and content-length $cl")
        // 6.3.4, except this assumes only a single transfer-encoding value
        if (isChunked) return BodySize.CHUNKED

        return if (cl.isEmpty()) {
            // 6.3.5
            BodySize.NONE
        } else {
            HttpMessage.fixedBodyLength(cl)
        }
    }

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
    var request: HttpRequest?,
    override var httpVersion: String,
    var statusCode: Int,
    var reason: String,
    private val headers: HttpHeaders = HttpHeaders(),
) : HttpMessage {

    fun isInformational() = statusCode / 100 == 1

    override fun bodyTransferSize() : BodySize {
        // numbers referring to sections in https://httpwg.org/specs/rfc9112.html#message.body.length

        // 6.3.1
        if (isInformational() || statusCode == 204 || statusCode == 304) return BodySize.NONE
        val req = request ?: throw IllegalStateException("Cannot tell the size without the request")
        if (req.method == "HEAD") return BodySize.NONE

        // 6.3.2
        if (req.method == "CONNECT") return BodySize.UNSPECIFIED

        // 6.3.3
        val cl = headers.getAll("content-length")
        val isChunked = headers.hasChunkedBody()
        if (isChunked && cl.isNotEmpty()) throw IllegalStateException("A response has chunked encoding and content-length $cl")
        // 6.3.4, except this assumes only a single transfer-encoding value
        if (isChunked) return BodySize.CHUNKED
        return if (cl.isEmpty()) {
            // 6.3.8
            BodySize.UNSPECIFIED
        } else {
            HttpMessage.fixedBodyLength(cl)
        }
    }

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
        internal fun empty() = HttpResponse(null, "", 0, "")
    }

}