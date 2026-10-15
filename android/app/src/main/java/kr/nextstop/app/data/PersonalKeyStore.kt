package kr.nextstop.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kr.nextstop.app.domain.Provider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PersonalKeyStore internal constructor(context: Context, private val testKey: (() -> SecretKey)? = null) {
    private val preferences = context.getSharedPreferences("personal_api_keys", Context.MODE_PRIVATE)
    private val alias = "nextstop.personal.keys.v1"

    @Synchronized private fun encryptionKey(): SecretKey {
        testKey?.let { return it() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized fun save(provider: Provider, raw: String) {
        val value = raw.trim()
        require(value.isNotEmpty() && value.length <= 2048 && value.none { it.isWhitespace() }) { "API 키의 공백과 길이를 확인해 주세요." }
        if (provider == Provider.SEOUL) require(value.all { it.isLetterOrDigit() }) { "서울 API 키 형식을 확인해 주세요." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(provider.name, blob).commit()) { "키를 저장할 수 없습니다." }
    }
    @Synchronized fun read(provider: Provider): String? {
        val blob = preferences.getString(provider.name, null) ?: return null
        return try {
            val pieces = blob.split(":")
            if (pieces.size != 2) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }
    fun has(provider: Provider) = read(provider) != null
    fun delete(provider: Provider) { check(preferences.edit().remove(provider.name).commit()) }
}
