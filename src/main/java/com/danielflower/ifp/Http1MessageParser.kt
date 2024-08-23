package com.danielflower.ifp

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.text.ParseException
import java.util.*

internal const val SP = 32.toByte()
internal const val CR = 13.toByte()
internal const val LF = 10.toByte()
private const val HTAB = 9.toByte()
private const val A = 65.toByte()
private const val A_LOWER = 97.toByte()
private const val F = 70.toByte()
private const val F_LOWER = 102.toByte()
private const val Z = 90.toByte()
internal const val COLON = 58.toByte()
private const val SEMICOLON = 59.toByte()
internal val COLON_SP = byteArrayOf(COLON, SP)
internal val CRLF = byteArrayOf(CR, LF)
private const val ZERO = 48.toByte()
private const val NINE = 57.toByte()

internal interface HttpMessageListener {
    fun onHeaders(connectionInfo: ConnectionInfo, exchange: HttpMessage)

    fun onRawBytes(connection: ConnectionInfo, exchange: HttpMessage, array: ByteArray, offset: Int, length: Int)

    fun onContentBytes(connection: ConnectionInfo, exchange: HttpMessage, array: ByteArray, offset: Int, length: Int)

    fun onMessageEnded(connectionInfo: ConnectionInfo, exchange: HttpMessage)
    fun onError(connectionInfo: ConnectionInfo, exchange: HttpMessage, error: Exception)
}

internal class Http1MessageParser(private val connectionInfo: ConnectionInfo, type: HttpMessageType, private val requestQueue: Queue<HttpRequest>) {

    private var remainingBytesToProxy: Long = 0L
    private var state : ParseState
    private var buffer = StringBuilder()
    private var exchange : HttpMessage
    private var headerName : String? = null
    private var copyFrom : Int? = null
    init {
        if (type == HttpMessageType.REQUEST) {
            exchange = HttpRequest.empty()
            state = ParseState.REQUEST_START
        } else {
            exchange = HttpResponse.empty()
            state = ParseState.RESPONSE_START
        }
    }

    private val log : Logger = LoggerFactory.getLogger(Http1MessageParser::class.java)

    fun feed(bytes: ByteArray, offset: Int, length: Int, listener: HttpMessageListener) {
        if (log.isDebugEnabled) log.debug("${if (exchange is HttpRequest) "REQ" else "RESP"} fed $length bytes at $state")
        var i = offset
        while (i < offset + length) {
            val b = bytes[i]
            when (state) {
                ParseState.REQUEST_START -> {
                    if (b.isUpperCase()) {
                        requestQueue.offer(exchange as HttpRequest)
                        state = ParseState.METHOD
                        buffer.appendChar(b)
                    } else throw ParseException("state=$state b=$b", i)
                }
                ParseState.METHOD -> {
                    if (b.isUpperCase()) {
                        buffer.appendChar(b)
                    } else if (b == SP) {
                        request().method = buffer.consume()
                        state = ParseState.REQUEST_TARGET
                    } else throw ParseException("state=$state b=$b", i)
                }
                ParseState.REQUEST_TARGET -> {
                    if (b.isVChar()) { // todo: only allow valid target chars
                        buffer.appendChar(b)
                    } else if (b == SP) {
                        request().url = buffer.consume()
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
                        exchange.httpVersion = buffer.consume()
                        state = ParseState.HEADER_START
                    } else throw ParseException("state=$state b=$b", i)
                }
                ParseState.RESPONSE_START -> {
                    if (b == SP) {
                        exchange.httpVersion = buffer.consume()
                        state = ParseState.STATUS_CODE
                    } else {
                        if (b.isVChar()) {
                            buffer.appendChar(b)
                        } else throw ParseException("state=$state b=$b", i)
                    }
                }
                ParseState.STATUS_CODE -> {
                    if (b.isDigit()) {
                        buffer.appendChar(b)
                        if (buffer.length > 4) throw ParseException("status code too long", i)
                    } else if (b == SP) {
                        val code = buffer.consume().toInt()
                        response().statusCode = code
                        if (code >= 200 || code == 101) {
                            response().request = requestQueue.poll() ?: throw ParseException("Got a response without a request", i)
                        }
                        state = ParseState.REASON_PHRASE
                    } else throw ParseException("state=$state b=$b", i)
                }
                ParseState.REASON_PHRASE -> {
                    if (b.isVChar() || b.isOWS()) {
                        buffer.appendChar(b)
                    } else if (b == CR) {
                        state = ParseState.STATUS_LINE_ENDING
                    } else throw ParseException("state=$state b=$b", i)
                }
                ParseState.STATUS_LINE_ENDING -> {
                    if (b.isLF()) {
                        response().reason = buffer.consume()
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
                        exchange.headers().addHeader(headerName!!, value)
                        state = ParseState.HEADER_START
                    } else throw ParseException("No LF after CR at $state", i)
                }
                ParseState.HEADERS_ENDING -> {
                    if (b.isLF()) {
                        val body = exchange.bodyTransferSize()
                        when (body.type) {
                            BodyType.FIXED_SIZE -> {
                                val len = body.bytes!!
                                state = ParseState.FIXED_SIZE_BODY
                                remainingBytesToProxy = len
                            }
                            BodyType.CHUNKED -> {
                                state = ParseState.CHUNK_START
                            }
                            BodyType.UNSPECIFIED -> {
                                state = ParseState.UNSPECIFIED_BODY
                                remainingBytesToProxy = Long.MAX_VALUE
                            }
                            BodyType.NONE -> {}
                        }

                        listener.onHeaders(connectionInfo, exchange)
                        if (body.type == BodyType.NONE) {
                            onMessageEnded(listener)
                        }
                    } else throw ParseException("state=$state b=$b", i)
                }
                ParseState.FIXED_SIZE_BODY -> {
                    val numberSent = sendBytes(listener, bytes, offset, length, i, i, remainingBytesToProxy)
                    i += numberSent - 1 // subtracting one because there is an i++ below
                    if (remainingBytesToProxy == 0L) {
                        onMessageEnded(listener)
                    }
                }
                ParseState.UNSPECIFIED_BODY -> {
                    val numberSent = sendBytes(listener, bytes, offset, length, i, i, remainingBytesToProxy)
                    i += numberSent - 1 // subtracting one because there is an i++ below
                }
                ParseState.CHUNK_START -> {
                    copyFrom = i
                    if (b.isHexDigit()) {
                        state = ParseState.CHUNK_SIZE
                        buffer.appendChar(b)
                    } else throw ParseException("state=$state b=$b", i)
                }
                ParseState.CHUNK_SIZE -> {
                    if (b.isHexDigit()) {
                        buffer.appendChar(b)
                    } else if (b == SEMICOLON) {
                        state = ParseState.CHUNK_EXTENSIONS
                    } else if (b.isCR()) {
                        state = ParseState.CHUNK_HEADER_ENDING
                    } else throw ParseException("state=$state b=$b", i)
                }
                ParseState.CHUNK_EXTENSIONS -> {
                    if (b.isVChar() || b.isOWS()) {
                        // todo: only allow valid extension characters
                    } else if (b.isCR()) {
                        state = ParseState.CHUNK_HEADER_ENDING
                    }
                }
                ParseState.CHUNK_HEADER_ENDING -> {
                    if (b.isLF()) {
                        val chunkDataSize = buffer.consume().toLong(16)
                        val consumeTrailingCRLF = chunkDataSize > 0

                        // Well, this is complicated. Basically we have been skipping over the chunk metadata. Now we want
                        // to send it all, along with as much of the actual chunk as possible, plus the ending CRLF.
                        // So we rewind the pointer back to the beginning of the chunk metadata, and then just proxy straight
                        // from there until the end of the chunk.
                        // Of course, all of these might be going over buffer boundaries. So the furthest back we can go is
                        // i=0 (if this is the case, then at the end of the parse loop on the previous buffer it would have
                        // written everything that was remaining already).
                        val start = copyFrom!!
                        val chunkHeaderLen = i - start + 1
                        remainingBytesToProxy = chunkDataSize + chunkHeaderLen + (if (consumeTrailingCRLF) 2 else 0)
                        i = maxOf(0, i - chunkHeaderLen + 1)
                        val written = sendBytes(listener, bytes, offset, length, i, i + chunkHeaderLen, chunkDataSize)
                        i += written
                        state = if (remainingBytesToProxy == 0L && chunkDataSize == 0L) {
                            i--
                            ParseState.LAST_CHUNK
                        } else if (remainingBytesToProxy == 0L) {
                            i--
                            ParseState.CHUNK_START
                        } else {
                            ParseState.CHUNK_DATA
                        }
                        copyFrom = null
                    } else throw ParseException("state=$state b=$b", i)
                }
                ParseState.CHUNK_DATA -> {
                    val numberSent = sendBytes(listener, bytes, offset, length, i, i, remainingBytesToProxy - 2)
                    i += numberSent - 1 // subtracting one because there is an i++ below
                    if (remainingBytesToProxy == 0L) {
                        state = ParseState.CHUNK_START
                    }
                }
                ParseState.LAST_CHUNK -> {
                    if (b.isCR()) {
                        state = ParseState.CHUNKED_BODY_ENDING
                    } else if (b.isTChar()) {
                        buffer.appendChar(b)
                        state = ParseState.TRAILERS
                    } else throw ParseException("state=$state b=$b", i)
                }
                ParseState.CHUNKED_BODY_ENDING -> {
                    if (b.isLF()) {
                        listener.onRawBytes(connectionInfo, exchange, CRLF, i-1, 2)
                        onMessageEnded(listener)
                    } else throw ParseException("state=$state b=$b", i)
                }
                ParseState.TRAILERS -> {
                    if (b.isOWS() || b.isVChar() || b.isCR()) {
                        buffer.appendChar(b)
                    } else if (b.isLF()) {
                        buffer.appendChar(b)
                        val trailerPart = buffer.toString()
                        if (trailerPart.endsWith("\r\n\r\n")) {
                            buffer.setLength(0)
                            val trailerBytes = trailerPart.toByteArray(StandardCharsets.US_ASCII)
                            listener.onRawBytes(connectionInfo, exchange, trailerBytes, 0, trailerBytes.size)
                            onMessageEnded(listener)
                        }
                    } else throw ParseException("state=$state b=$b", i)
                }
                ParseState.WEBSOCKET -> {
                    val remaining = length - i
                    listener.onRawBytes(connectionInfo, exchange, bytes, i, remaining)
                    i += remaining - 1 // -1 because there is a i++ below
                }
            }
            i++
        }

        // When reading the chunk metadata, we read in the data before proxying. If we get to the end of the current
        // buffer, then we just need to send whatever we were up to.
        val start = copyFrom
        if (start != null) {
            assert(state == ParseState.CHUNK_START || state == ParseState.CHUNK_EXTENSIONS || state == ParseState.CHUNK_SIZE || state == ParseState.CHUNK_HEADER_ENDING)
            val numToCopy = length - start
            if (numToCopy > 0) {
                listener.onRawBytes(connectionInfo, exchange, bytes, start, numToCopy)
            }
            copyFrom = 0
        }
    }

    fun eof(listener: HttpMessageListener) {
        if (state.eofAction == EOFAction.COMPLETE) {
            listener.onMessageEnded(connectionInfo, exchange)
        } else if (state.eofAction == EOFAction.ERROR) {
            listener.onError(connectionInfo, exchange, EOFException("EOF when state is $state"))
        }
    }

    private fun request() = (exchange as HttpRequest)
    private fun response() = (exchange as HttpResponse)

    private fun sendBytes(listener: HttpMessageListener, bytes: ByteArray, originalOffset: Int, originalLength: Int, currentIndex: Int, contentStartIndex: Int, contentLength: Long): Int {
        val remainingInBuffer = originalLength - (currentIndex - originalOffset)
        val numberToTransfer = minOf(remainingBytesToProxy, remainingInBuffer.toLong()).toInt()
        val exc = exchange
        listener.onRawBytes(connectionInfo, exc, bytes, currentIndex, numberToTransfer)
        if (contentLength > 0) {
            val remainingContentInBuffer = originalLength - (contentStartIndex - originalOffset)
            val contentToTransfer = minOf(remainingContentInBuffer.toLong(), contentLength).toInt()
            listener.onContentBytes(connectionInfo, exc, bytes, contentStartIndex, contentToTransfer)
        }
        remainingBytesToProxy -= numberToTransfer
        return numberToTransfer
    }

    private fun onMessageEnded(listener: HttpMessageListener) {
        val exc = exchange
        this.state = if (exc is HttpRequest) {
            if (exc.isWebsocketUpgrade()) {
                ParseState.WEBSOCKET
            } else {
                this.exchange = HttpRequest.empty()
                ParseState.REQUEST_START
            }
        } else {
            if (exc.headers().hasHeaderValue("upgrade", "websocket")) {
                ParseState.WEBSOCKET
            } else {
                this.exchange = HttpResponse.empty()
                ParseState.RESPONSE_START
            }
        }
        if (state != ParseState.WEBSOCKET && !(exc is HttpResponse && exc.statusCode == 100)) {
            listener.onMessageEnded(connectionInfo, exc)
        }
    }

    fun error(e: Exception, messageListener: HttpMessageListener) {
        messageListener.onError(connectionInfo, exchange, e)
    }

    private enum class ParseState(val eofAction: EOFAction) {
        REQUEST_START(EOFAction.NOTHING),
        RESPONSE_START(EOFAction.NOTHING),
        METHOD(EOFAction.ERROR),
        REQUEST_TARGET(EOFAction.ERROR),
        HTTP_VERSION(EOFAction.ERROR),
        REQUEST_LINE_ENDING(EOFAction.ERROR),
        STATUS_CODE(EOFAction.ERROR),
        REASON_PHRASE(EOFAction.ERROR),
        STATUS_LINE_ENDING(EOFAction.ERROR),
        HEADER_START(EOFAction.ERROR),
        HEADER_NAME(EOFAction.ERROR),
        HEADER_NAME_ENDED(EOFAction.ERROR),
        HEADER_VALUE(EOFAction.ERROR),
        HEADER_VALUE_ENDING(EOFAction.ERROR),
        HEADERS_ENDING(EOFAction.ERROR),
        FIXED_SIZE_BODY(EOFAction.ERROR),
        UNSPECIFIED_BODY(EOFAction.COMPLETE),
        CHUNK_START(EOFAction.ERROR),
        CHUNK_SIZE(EOFAction.ERROR),
        CHUNK_EXTENSIONS(EOFAction.ERROR),
        CHUNK_HEADER_ENDING(EOFAction.ERROR),
        CHUNK_DATA(EOFAction.ERROR),
        LAST_CHUNK(EOFAction.ERROR),
        CHUNKED_BODY_ENDING(EOFAction.ERROR),
        TRAILERS(EOFAction.ERROR),
        WEBSOCKET(EOFAction.COMPLETE),
    }

    private enum class EOFAction { NOTHING, ERROR, COMPLETE }

    override fun toString(): String {
        return javaClass.simpleName + " ${this.state}"
    }

    companion object {
        private fun Byte.isVChar() = this >= 0x21.toByte() && this <= 0x7E.toByte()
        internal fun Byte.isTChar(): Boolean {
            // tchar = '!' / '#' / '$' / '%' / '&' / ''' / '*' / '+' / '-' / '.' /
            //    '^' / '_' / '`' / '|' / '~' / DIGIT / ALPHA
            return this == 33.toByte()
                    || (this in 35.toByte()..39.toByte())
                    || this == 42.toByte() || this == 43.toByte() || this == 45.toByte() || this == 46.toByte()
                    || (this in ZERO..NINE) // 0-9
                    || (this in A..Z) // A-Z
                    || (this in 94.toByte()..122.toByte()) // ^, ), `, a-z
                    || this == 124.toByte() || this == 126.toByte()
        }

        private fun Byte.isUpperCase() = this in A..Z
        private fun Byte.isCR() = this == CR
        private fun Byte.isLF() = this == LF
        private fun Byte.isOWS() = this == SP || this == HTAB
        private fun Byte.toLower(): Byte = if (this < A || this > Z) this else (this + 32).toByte()
        private fun Byte.isDigit() = this in ZERO..NINE
        private fun Byte.isHexDigit() = this in A..F || this in ZERO..NINE || this in A_LOWER..F_LOWER
        private fun StringBuilder.appendChar(b: Byte) {
            this.append(b.toInt().toChar())
            if (this.length > 16384) throw IllegalStateException("Buffer is ${this.length} bytes")
        }

        private fun StringBuilder.consume(): String {
            val v = this.toString()
            this.setLength(0)
            return v
        }
    }
}

internal enum class HttpMessageType { REQUEST, RESPONSE }
