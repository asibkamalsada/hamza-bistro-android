package de.hamzabistro.printstation.security

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import de.hamzabistro.printstation.core.Account
import de.hamzabistro.printstation.core.SessionStore
import de.hamzabistro.printstation.core.StoredSession
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONException
import org.json.JSONObject

/**
 * The refresh token, encrypted with AES-256-GCM under a key that lives in the
 * Android Keystore — in the phone's secure hardware where it has some
 * (StrongBox, else the TEE) — and never leaves it. The file on disk is
 * useless without this very device, and it is kept out of every backup.
 *
 * The key is not bound to the screen being unlocked: the station has to
 * refresh its session with the tablet locked. It is available from the first
 * unlock after a boot, which is also when Android starts the station.
 */
class KeystoreSessionStore(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    @Synchronized
    override fun load(): StoredSession? {
        val sealed = prefs.getString(SESSION, null) ?: return null
        val plain =
            try {
                open(sealed)
            } catch (e: GeneralSecurityException) {
                if (!e.isPermanent()) throw IOException("the Keystore did not answer", e)
                // The key is gone (the Keystore was reset) or the file does
                // not belong to it: signing in again is the only way forward.
                clear()
                return null
            } catch (e: ProviderException) {
                // The Keystore itself failing, for a moment: try again later
                // rather than throwing the session away.
                throw IOException("the Keystore did not answer", e)
            } catch (e: IllegalArgumentException) {
                clear()
                return null
            }
        return try {
            val json = JSONObject(String(plain, Charsets.UTF_8))
            StoredSession(
                refreshToken = json.getString("refresh_token"),
                account = Account(json.getString("user_id"), json.optString("email").ifEmpty { null }),
            )
        } catch (e: JSONException) {
            clear()
            null
        }
    }

    private fun GeneralSecurityException.isPermanent() =
        this is AEADBadTagException || this is KeyPermanentlyInvalidatedException || this is UnrecoverableKeyException

    // commit, not apply: see below.
    @SuppressLint("ApplySharedPref")
    @Synchronized
    override fun save(session: StoredSession) {
        val json =
            JSONObject()
                .put("refresh_token", session.refreshToken)
                .put("user_id", session.account.userId)
                .put("email", session.account.email ?: "")
        // commit, not apply: the old refresh token is spent the moment the
        // new one arrives, and a process killed before an apply lands would
        // be signed out.
        prefs.edit().putString(SESSION, seal(json.toString().toByteArray(Charsets.UTF_8))).commit()
    }

    // commit, not apply: a sign-out is not undone by the process dying a moment later.
    @SuppressLint("ApplySharedPref")
    @Synchronized
    override fun clear() {
        prefs.edit().remove(SESSION).commit()
    }

    private fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain)
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + sealed, Base64.NO_WRAP)
    }

    private fun open(stored: String): ByteArray {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        require(bytes.isNotEmpty()) { "empty session" }
        val ivLength = bytes[0].toInt()
        require(ivLength > 0 && bytes.size > 1 + ivLength) { "damaged session" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 1, ivLength))
        cipher.updateAAD(AAD)
        return cipher.doFinal(bytes, 1 + ivLength, bytes.size - 1 - ivLength)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return try {
            generate(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec =
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setIsStrongBoxBacked(strongBox)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "session"
        const val SESSION = "session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128

        /** Binds the ciphertext to what it is, so it cannot be passed off as anything else. */
        val AAD = "hamza-bistro print station session v1".toByteArray(Charsets.UTF_8)
    }
}
