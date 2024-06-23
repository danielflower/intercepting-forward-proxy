package com.danielflower

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.ParseException

class Http1RequestParser {

    private var state : ParseState = ParseState.START
    private var buffer = StringBuilder()
    private var request : HttpRequest = HttpRequest.empty()
    private var headerName : String? = null

    fun feed(bytes: ByteArray, offset: Int, length: Int, listener: RequestStreamListener) {

        println(">> ${String(bytes, offset, length)}")
        var i = offset
        while (i < offset + length) {
            val b = bytes[i]
            println(">>> i=$i state=$state b=$b (${b.toChar()})")

            when (state) {
                ParseState.START -> {
                    if (b.isTChar()) {
                        state = ParseState.METHOD
                        buffer.setLength(0)
                        buffer.appendChar(b)
                    } else throw ParseException("state=$state b=$b", i)
                }
                ParseState.METHOD -> {
                    if (b.isTChar()) {
                        buffer.appendChar(b)
                    } else if (b.isSpace()) {
                        request.method = buffer.consume()
                        state = ParseState.REQUEST_TARGET
                    } else throw ParseException("state=$state b=$b", i)
                }
                ParseState.REQUEST_TARGET -> {
                    if (b.isVChar()) { // todo: only allow valid target chars
                        buffer.appendChar(b)
                    } else if (b.isSpace()) {
                        request.url = buffer.consume()
                        state = ParseState.HTTP_VERSION
                    } else throw ParseException("state=$state b=$b", i)
                }
                ParseState.HTTP_VERSION -> {
                    if (b.isCR()) {
                        state = ParseState.REQUEST_LINE_ENDING
                    } else {
                        if (b.isVChar()) {
                            buffer.appendChar(b)
                        } else throw ParseException("state=$state b=$b", i)
                    }
                }
                ParseState.REQUEST_LINE_ENDING -> {
                    if (b.isLF()) {
                        request.httpVersion = buffer.consume()
                        state = ParseState.HEADER_START
                    } else throw ParseException("state=$state b=$b", i)
                }
                ParseState.HEADER_START -> {
                    if (b.isTChar()) {
                        buffer.appendChar(b.toLower())
                        state = ParseState.HEADER_NAME
                    } else if (b.isCR()) {
                        state = ParseState.HEADERS_ENDING
                    } else throw ParseException("state=$state b=$b", i)
                }
                ParseState.HEADER_NAME -> {
                    if (b.isTChar()) {
                        buffer.appendChar(b.toLower())
                    } else if (b == 58.toByte()) {
                        headerName = buffer.consume()
                        if (headerName!!.isEmpty()) throw ParseException("Empty header name", i)
                        state = ParseState.HEADER_NAME_ENDED
                    }
                }
                ParseState.HEADER_NAME_ENDED -> {
                    if (b.isOWS()) {
                        // skip it
                    } else if (b.isVChar()) {
                        buffer.appendChar(b)
                        state = ParseState.HEADER_VALUE
                    } else throw ParseException("Invalid header value $b", i)
                }
                ParseState.HEADER_VALUE -> {
                    if (b.isVChar() || b.isOWS()) {
                        buffer.appendChar(b)
                    } else if (b.isCR()) {
                        state = ParseState.HEADER_VALUE_ENDING
                    }
                }
                ParseState.HEADER_VALUE_ENDING -> {
                    if (b.isLF()) {
                        val value = buffer.consume().trimEnd()
                        if (value.isEmpty()) throw ParseException("No header value for header $headerName", i)
                        request.addHeader(headerName!!, value)
                        state = ParseState.HEADER_START
                    } else throw ParseException("No LF after CR at $state", i)
                }
                ParseState.HEADERS_ENDING -> {
                    if (b.isLF()) {
                        val req = request
                        if (req.hasBody()) {
                            state = ParseState.BODY
                        } else {
                            onRequestEnded()
                        }
                        listener.onRequestHeadersReady(req)
                    } else throw ParseException("state=$state b=$b", i)
                }
                ParseState.BODY -> TODO()
            }
            i++
        }
    }

    private fun onRequestEnded() {
        this.state = ParseState.START
        this.request = HttpRequest.empty()
    }

    private enum class RequestPart {
        START, REQUEST_LINE, HEADERS, BODY
    }

    private enum class ParseState(part: RequestPart) {
        START(RequestPart.START),
        METHOD(RequestPart.REQUEST_LINE),
        REQUEST_TARGET(RequestPart.REQUEST_LINE),
        HTTP_VERSION(RequestPart.REQUEST_LINE),
        REQUEST_LINE_ENDING(RequestPart.REQUEST_LINE),
        HEADER_START(RequestPart.HEADERS),
        HEADER_NAME(RequestPart.HEADERS),
        HEADER_NAME_ENDED(RequestPart.HEADERS),
        HEADER_VALUE(RequestPart.HEADERS),
        HEADER_VALUE_ENDING(RequestPart.HEADERS),
        HEADERS_ENDING(RequestPart.HEADERS),
        BODY(RequestPart.BODY),
    }

    companion object {
        private fun Byte.isVChar() : Boolean {
            return this >= 0x21.toByte() && this <= 0x7E.toByte()
        }
        internal fun Byte.isTChar() : Boolean {
            // tchar = '!' / '#' / '$' / '%' / '&' / ''' / '*' / '+' / '-' / '.' /
            //    '^' / '_' / '`' / '|' / '~' / DIGIT / ALPHA
            return this == 33.toByte()
                    || (this in 35.toByte()..39.toByte())
                    || this == 42.toByte() || this == 43.toByte() || this == 45.toByte() || this == 46.toByte()
                    || (this in 48.toByte()..57.toByte()) // 0-9
                    || (this in 65.toByte()..90.toByte()) // A-Z
                    || (this in 94.toByte()..122.toByte()) // ^, ), `, a-z
                    || this == 124.toByte() || this == 126.toByte()
        }
        private fun Byte.isSpace() = this == 32.toByte()
        private fun Byte.isCR() = this == 13.toByte()
        private fun Byte.isLF() = this == 10.toByte()
        private fun Byte.isHtab() = this == 9.toByte()
        private fun Byte.isOWS() = this.isSpace() || this.isHtab()
        private fun Byte.toLower() : Byte {
            return if (this < 65 || this > 90) this
            else (this + 32).toByte()
        }
        private fun StringBuilder.appendChar(b: Byte) {
            this.append(b.toInt().toChar())
        }
        private fun StringBuilder.consume(): String {
            val v = this.toString()
            this.setLength(0)
            return v
        }
    }
}

interface RequestStreamListener {
    fun onRequestHeadersReady(request: HttpRequest)
    fun onBytesToProxy(array: ByteArray, offset: Int, length: Int)
}

data class HttpRequest(var method: String, var url: String, var httpVersion: String, private val headers: MutableList<Pair<String,String>> = mutableListOf()) {
    fun hasBody(): Boolean {
        val contentLength = header("content-length")?.toLongOrNull()
        if (contentLength != null && contentLength > 0L) return true
        return header("transfer-encoding") == "chunked"
    }

    // note: all header names are converted to lowercase at the parse stage
    fun header(name: String) : String? = headers.firstOrNull { it.first == name }?.second

    fun hasHeader(name: String) = headers.any { it.first == name }
    fun addHeader(name: String, value: String) {
        headers.addLast(Pair(name, value))
    }

    fun writeTo(out: OutputStream) {
        out.write(method.headerBytes())
        out.write(' '.code)
        out.write(url.headerBytes())
        out.write(' '.code)
        out.write(httpVersion.headerBytes())
        out.crlf()
        for (header in headers) {
            out.write(header.first.headerBytes())
            out.write(byteArrayOf(58.toByte(), 32.toByte()))
            out.write(header.second.headerBytes())
            out.crlf()
        }
        out.crlf()
    }

    companion object {
        private fun OutputStream.crlf() {
            this.write(byteArrayOf(13.toByte(), 10.toByte()))
        }
        private fun String.headerBytes() = this.toByteArray(StandardCharsets.US_ASCII)
        fun empty() = HttpRequest("", "", "")
    }

}

