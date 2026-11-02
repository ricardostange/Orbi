package br.com.assistente.teste

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ApiKeyStore(
    context: Context,
    private val entry: String = "deepseek",
    private val alias: String = "orbi.deepseek.api.v1"
) {
    private val prefs = context.getSharedPreferences("api_credentials", Context.MODE_PRIVATE)
    fun hasKey() = prefs.contains(entry)
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
        check(create) { "Chave indisponível. Configure a API novamente." }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes("GCM").setEncryptionPaddings("NoPadding").build())
        }.generateKey()
    }
    fun save(value: String) {
        require(value.isNotBlank())
        if (entry == "deepseek") require(value.none { it.isWhitespace() })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val record = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(prefs.edit().putString(entry, record).commit())
    }
    fun read(): String? {
        val record = prefs.getString(entry, null) ?: return null
        val parts = record.split(':')
        check(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        }
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
    fun remove() { check(prefs.edit().remove(entry).commit()) }
}
