package com.ezzdigi.ezzgameboost

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// App-locked config format. The "ساخت کانفیگ" tab exports "EZZ1:<base64>" instead of a plain
// WireGuard .conf: other WireGuard clients cannot read it, only this app can open it
// (AES-256-GCM, key built into the app). This stops casual copy-and-paste into other clients;
// it is not a defence against someone who reverse-engineers the APK.
internal object EzzLock {
    private const val PREFIX = "EZZ1:"
    private val AAD = "EzzGameBOOST/config/v1".toByteArray(Charsets.UTF_8)

    private fun key(): SecretKeySpec {
        val seed = "ezz" + "Lock" + "|" + "v1" + "|" + "gameboost" + "|" + "wg-config"
        return SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8)), "AES")
    }

    fun isSealed(s: String): Boolean = s.trim().startsWith(PREFIX)

    fun seal(plain: String): String {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        c.updateAAD(AAD)
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(iv + ct, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    // Returns the plain config, or null when the text is not a valid sealed config.
    fun open(s: String): String? = try {
        val raw = Base64.decode(s.trim().removePrefix(PREFIX), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val iv = raw.copyOfRange(0, 12)
        val ct = raw.copyOfRange(12, raw.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        c.updateAAD(AAD)
        String(c.doFinal(ct), Charsets.UTF_8)
    } catch (_: Throwable) {
        null
    }
}
