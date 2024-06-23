package com.danielflower

import com.danielflower.Http1RequestParser.Companion.isTChar
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

class Http1RequestParserTest {
    @Test
    fun tcharsAreValid() {
        val chars = arrayOf('!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~', '0', '9', 'a', 'z', 'A', 'Z')
        for (char in chars) {
            assertThat(char.code.toByte().isTChar(), equalTo(true))
        }
        for (c in '0'..'9') {
            assertThat(c.code.toByte().isTChar(), equalTo(true))
        }

        for (c in 'a'..'z') {
            assertThat(c.code.toByte().isTChar(), equalTo(true))
        }
        for (c in 'A'..'Z') {
            assertThat(c.code.toByte().isTChar(), equalTo(true))
        }
        for (i in 0..32) {
            assertThat(i.toByte().isTChar(), equalTo(false))
        }
        val nots = arrayOf(34, 40, 41, 44, 47, 58, 59, 60, 61, 62, 63, 64, 91, 92, 93, 123, 125)
        for (not in nots) {
            assertThat(34.toByte().isTChar(), equalTo(false))
        }

        for (i in 127..256) {
            assertThat(i.toByte().isTChar(), equalTo(false))
        }
    }
}