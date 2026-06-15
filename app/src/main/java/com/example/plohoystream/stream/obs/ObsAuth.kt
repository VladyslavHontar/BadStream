package com.example.plohoystream.stream.obs

import java.security.MessageDigest
import java.util.Base64

/** obs-websocket v5 authentication string: base64(sha256( base64(sha256(password+salt)) + challenge )). */
object ObsAuth {
    fun compute(password: String, salt: String, challenge: String): String {
        val secret = b64(sha256(password + salt))
        return b64(sha256(secret + challenge))
    }
    private fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
