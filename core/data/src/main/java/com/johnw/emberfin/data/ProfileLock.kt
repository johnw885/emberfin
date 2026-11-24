package com.johnw.emberfin.data

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Per-profile options kept on this TV only: kids mode (a simplified Home; the server's parental
 * rating policy is what actually limits the content) and an optional 4-digit PIN that guards
 * opening the profile and changing its options. Stored apart from the account, so a profile that
 * has to sign in again keeps them.
 */
@Serializable
data class ProfileLock(
    val kids: Boolean = false,
    val pinHash: String? = null,
    val pinSalt: String? = null,
) {
    val hasPin: Boolean get() = pinHash != null && pinSalt != null

    fun checkPin(pin: String): Boolean = hasPin && Pin.hash(pin, pinSalt!!) == pinHash

    fun withPin(pin: String?): ProfileLock =
        if (pin == null) copy(pinHash = null, pinSalt = null)
        else Pin.newSalt().let { salt -> copy(pinHash = Pin.hash(pin, salt), pinSalt = salt) }
}

/** PIN hashing. A 4-digit PIN is a convenience lock, not a password: this only keeps it out of plain sight. */
object Pin {
    const val LENGTH = 4

    fun isValid(pin: String): Boolean = pin.length == LENGTH && pin.all { it in '0'..'9' }

    fun newSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()

    fun hash(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var bytes = (salt + ":" + pin).toByteArray()
        repeat(10_000) { bytes = md.digest(bytes) }
        return bytes.toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
