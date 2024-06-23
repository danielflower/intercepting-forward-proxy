package com.danielflower

import java.io.OutputStream
import java.nio.charset.StandardCharsets

data class HttpRequest(var method: String, var url: String, var httpVersion: String, private val headers: MutableList<Pair<String,String>> = mutableListOf()) {

    fun hasChunkedBody() = hasHeaderValue("transfer-encoding", "chunked")
    fun hasFixedLengthBody() : Boolean {
        val len = contentLength()
        return (len != null && len > 0L)
    }

    fun contentLength() : Long? = header("content-length")?.toLongOrNull()

    // note: all header names are converted to lowercase at the parse stage
    fun header(name: String) : String? = headers.firstOrNull { it.first == name }?.second

    fun hasHeader(name: String) = headers.any { it.first == name }
    fun addHeader(name: String, value: String) {
        headers.addLast(Pair(name, value))
    }
    fun hasHeaderValue(name: String, value: String) = header(name) == value

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