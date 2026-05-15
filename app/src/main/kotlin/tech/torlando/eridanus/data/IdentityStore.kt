// SPDX-License-Identifier: MPL-2.0

package tech.torlando.eridanus.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import tech.torlando.eridanus.rns.RnsIdentity
import tech.torlando.eridanus.rns.RnsIdentityFactory

class IdentityStore(context: Context, private val identities: RnsIdentityFactory) {

    companion object {
        private const val TAG = "IdentityStore"
        private const val FILE_NAME = "ara_identity_store"
        private const val KEY_HUB_IDENTITY = "hub_identity"
        private const val KEY_CLIENT_IDENTITY = "client_identity"
    }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun saveHubIdentity(identity: RnsIdentity) {
        prefs.edit().putString(KEY_HUB_IDENTITY, identity.getPrivateKey().toHexString()).apply()
        Log.d(TAG, "Hub identity saved")
    }

    fun loadHubIdentity(): RnsIdentity? {
        val hex = prefs.getString(KEY_HUB_IDENTITY, null) ?: return null
        return try {
            identities.fromBytes(hex.hexToByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load hub identity", e)
            null
        }
    }

    fun saveClientIdentity(identity: RnsIdentity) {
        prefs.edit().putString(KEY_CLIENT_IDENTITY, identity.getPrivateKey().toHexString()).apply()
        Log.d(TAG, "Client identity saved")
    }

    fun loadClientIdentity(): RnsIdentity? {
        val hex = prefs.getString(KEY_CLIENT_IDENTITY, null) ?: return null
        return try {
            identities.fromBytes(hex.hexToByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load client identity", e)
            null
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(length / 2) { i ->
            ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte()
        }
    }
}
