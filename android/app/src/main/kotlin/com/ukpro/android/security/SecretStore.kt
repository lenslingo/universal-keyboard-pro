package com.ukpro.android.security

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.ukpro.core.Json
import com.ukpro.core.TrustedHub
import com.ukpro.core.list
import com.ukpro.core.long
import com.ukpro.core.str
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores paired laptops. Pairing secrets are encrypted with an AES-256-GCM key that lives in the
 * Android Keystore (non-exportable). Nothing here is included in backups (data_extraction_rules.xml).
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("ukpro_secure", Context.MODE_PRIVATE)
    private val lock = Any()

    val deviceId: String = prefs.getString("device_id", null) ?: ("and-" + UUID.randomUUID().toString().replace("-", "").take(12))
        .also { prefs.edit().putString("device_id", it).apply() }

    val deviceName: String = run {
        val custom = prefs.getString("device_name", null)
        custom ?: (Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?: "${Build.MANUFACTURER} ${Build.MODEL}")
    }

    @Volatile private var cache: List<TrustedHub>? = null

    fun hubs(): List<TrustedHub> = synchronized(lock) {
        cache?.let { return it }
        val blob = prefs.getString("hubs", null) ?: return emptyList()
        return try {
            val json = String(decrypt(Base64.decode(blob, Base64.NO_WRAP)), Charsets.UTF_8)
            @Suppress("UNCHECKED_CAST")
            val list = (Json.parse(json) as List<Map<String, Any?>>).map {
                TrustedHub(it.str("id"), it.str("name"), Base64.decode(it.str("secret"), Base64.NO_WRAP), it.str("fp"),
                    it.list("hosts").map { h -> h.toString() }, it.long("port", 47821).toInt())
            }
            cache = list
            list
        } catch (e: Exception) { emptyList() } // key invalidated (e.g. device restore) → pair again
    }

    fun save(hub: TrustedHub) = synchronized(lock) {
        val all = hubs().filter { it.hubId != hub.hubId } + hub
        write(all)
    }

    /** Updates the remembered addresses after a successful connection (keeps the secret). */
    fun updateHosts(hubId: String, hosts: List<String>) = synchronized(lock) {
        val all = hubs().map { if (it.hubId == hubId) TrustedHub(it.hubId, it.name, it.secret, it.fingerprint, hosts, it.port) else it }
        write(all)
    }

    fun remove(hubId: String) = synchronized(lock) { write(hubs().filter { it.hubId != hubId }) }

    private fun write(all: List<TrustedHub>) {
        cache = all
        val json = Json.write(all.map {
            mapOf("id" to it.hubId, "name" to it.name, "secret" to Base64.encodeToString(it.secret, Base64.NO_WRAP),
                "fp" to it.fingerprint, "hosts" to it.hosts, "port" to it.port.toLong())
        })
        prefs.edit().putString("hubs", Base64.encodeToString(encrypt(json.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    private companion object { const val ALIAS = "ukpro_pairing_master" }
}
