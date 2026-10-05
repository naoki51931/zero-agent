package com.zeroagent.app.wallet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest

/**
 * MVP wallet foundation.
 *
 * Generates a secp256r1 key in Android Keystore and never exposes the private key.
 * IMPORTANT: this is NOT yet a spendable Bitcoin mainnet wallet because Bitcoin uses
 * secp256k1 and requires BIP32/BIP39/BIP84/address/transaction support.
 * The displayed identifier is intentionally prefixed za_test_ so it cannot be mistaken
 * for a Bitcoin address.
 */
class WalletManager(private val context: Context) {
    private val alias = "zero_agent_wallet_v1"
    private val prefs = context.getSharedPreferences("wallet", Context.MODE_PRIVATE)

    fun hasWallet(): Boolean = keyStore().containsAlias(alias)

    fun createWallet() {
        if (hasWallet()) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        val pair = generator.generateKeyPair()
        val digest = MessageDigest.getInstance("SHA-256").digest(pair.public.encoded)
        val id = Base64.encodeToString(digest.copyOfRange(0, 16), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.edit().putString("receive_id", "za_test_$id").apply()
    }

    fun getReceiveAddress(): String? = prefs.getString("receive_id", null)

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
