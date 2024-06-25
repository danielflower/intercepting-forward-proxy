package com.danielflower.ifp

import com.danielflower.ifp.Http1RequestParser.Companion.isTChar
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test

class Http1RequestParserTest {
    @Test
    fun tcharsAreValid() {
        val chars = arrayOf('!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~', '0', '9', 'a', 'z', 'A', 'Z')
        for (char in chars) {
            MatcherAssert.assertThat(char.code.toByte().isTChar(), Matchers.equalTo(true))
        }
        for (c in '0'..'9') {
            MatcherAssert.assertThat(c.code.toByte().isTChar(), Matchers.equalTo(true))
        }

        for (c in 'a'..'z') {
            MatcherAssert.assertThat(c.code.toByte().isTChar(), Matchers.equalTo(true))
        }
        for (c in 'A'..'Z') {
            MatcherAssert.assertThat(c.code.toByte().isTChar(), Matchers.equalTo(true))
        }
        for (i in 0..32) {
            MatcherAssert.assertThat(i.toByte().isTChar(), Matchers.equalTo(false))
        }
        val nots = arrayOf(34, 40, 41, 44, 47, 58, 59, 60, 61, 62, 63, 64, 91, 92, 93, 123, 125)
        for (not in nots) {
            MatcherAssert.assertThat(34.toByte().isTChar(), Matchers.equalTo(false))
        }

        for (i in 127..256) {
            MatcherAssert.assertThat(i.toByte().isTChar(), Matchers.equalTo(false))
        }
    }
}