package com.danielflower

import java.text.ParseException

class Http1RequestParser {

    private var remainingBytesToProxy: Long = 0L
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
                        val contentLength = req.contentLength()
                        if (contentLength != null && contentLength > 0) {
                            state = ParseState.FIXED_SIZE_BODY
                            this.remainingBytesToProxy = contentLength
                        } else if (req.hasChunkedBody()) {
                            state = ParseState.CHUNK_START
                        } else {
                            onRequestEnded()
                        }
                        listener.onRequestHeadersReady(req)
                    } else throw ParseException("state=$state b=$b", i)
                }
                ParseState.FIXED_SIZE_BODY -> {
                    if (remainingBytesToProxy == 0L) {
                        onRequestEnded()
                    } else {
                        val numberToSendNow = minOf(remainingBytesToProxy, length.toLong()).toInt()
                        listener.onBytesToProxy(bytes, offset + i, numberToSendNow)
                        i += numberToSendNow - 1 // subtracting one because there is an i++ below
                    }
                }
                ParseState.CHUNK_START -> {

                }
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
        FIXED_SIZE_BODY(RequestPart.BODY),
        CHUNK_START(RequestPart.BODY),
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
    fun onRequestHeadersReady(request: HttpRequest) {}
    fun onBytesToProxy(array: ByteArray, offset: Int, length: Int) {}
}

