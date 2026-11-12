package ai.cashdrive.app.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class TokenVaultException(cause: Throwable? = null) : IOException("Saved sandbox login is unavailable", cause)

/** Encrypts the active sandbox login with an Android Keystore AES-GCM key. */
class TokenVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun save(login: SandboxLogin) {
        if (login.mode != "sandbox" || login.accountId.isBlank() || login.installationId.isBlank() || login.accessToken.isBlank()) {
            throw IllegalArgumentException("Only a valid sandbox login can be stored")
        }
        val cleartext = JSONObject().put("accountId", login.accountId)
            .put("installationId", login.installationId)
            .put("accessToken", login.accessToken)
            .put("mode", login.mode)
            .toString().toByteArray(StandardCharsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(cleartext)
            val ok = prefs.edit()
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit()
            if (!ok) throw TokenVaultException()
        } catch (e: TokenVaultException) {
            throw e
        } catch (e: Exception) {
            throw TokenVaultException(e)
        } finally {
            cleartext.fill(0)
        }
    }

    @Synchronized
    fun load(): SandboxLogin? {
        val ivText = prefs.getString(KEY_IV, null) ?: return null
        val ciphertextText = prefs.getString(KEY_CIPHERTEXT, null) ?: run {
            clear()
            throw TokenVaultException()
        }
        var cleartext: ByteArray? = null
        try {
            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            val ciphertext = Base64.decode(ciphertextText, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cleartext = cipher.doFinal(ciphertext)
            val json = JSONObject(String(cleartext, StandardCharsets.UTF_8))
            val login = SandboxLogin(
                json.getString("accountId"),
                json.getString("installationId"),
                json.getString("accessToken"),
                json.getString("mode"),
            )
            if (login.mode != "sandbox" || login.accountId.isBlank() || login.accessToken.isBlank()) {
                throw TokenVaultException()
            }
            return login
        } catch (e: TokenVaultException) {
            clear()
            throw e
        } catch (e: Exception) {
            clear()
            throw TokenVaultException(e)
        } finally {
            cleartext?.fill(0)
        }
    }

    @Synchronized
    fun clear() {
        if (!prefs.edit().remove(KEY_IV).remove(KEY_CIPHERTEXT).commit()) throw TokenVaultException()
    }

    private fun getExistingKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return store.getKey(KEY_ALIAS, null) as? SecretKey ?: throw TokenVaultException()
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ai.cashdrive.sandbox-login.v1"
        const val PREFS_NAME = "cash_drive_secure_session"
        const val KEY_IV = "cipher_iv"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
