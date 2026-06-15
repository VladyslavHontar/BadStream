package com.example.plohoystream.stream.obs

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class ObsAuthTest {
    private fun expected(pw: String, salt: String, challenge: String): String {
        val sha = { s: String -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()) }
        val secret = Base64.getEncoder().encodeToString(sha(pw + salt))
        return Base64.getEncoder().encodeToString(sha(secret + challenge))
    }

    @Test fun matchesTwoStepAlgorithm() {
        val pw = "supersecret"; val salt = "lM3Gwd+H1NM4F2pdGcAA9w=="; val challenge = "+IxH4CnCleJIBO6bxKf2Lw=="
        assertEquals(expected(pw, salt, challenge), ObsAuth.compute(pw, salt, challenge))
    }

    @Test fun deterministic_and_nonEmpty() {
        val a = ObsAuth.compute("p", "s", "c")
        assertEquals(a, ObsAuth.compute("p", "s", "c"))
        assert(a.isNotEmpty())
    }
}
