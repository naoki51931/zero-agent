package com.zeroagent.app.wallet

import android.content.Context
import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.base.ScriptType
import org.bitcoinj.wallet.Wallet
import java.io.File

/**
 * Bitcoin TESTNET wallet for development only.
 * Testnet coins have no monetary value. Mainnet is intentionally disabled.
 */
class WalletManager(private val context: Context) {
    private val walletFile = File(context.filesDir, "zero-agent-testnet.wallet")
    private var wallet: Wallet? = null

    fun hasWallet(): Boolean = walletFile.exists()

    @Synchronized
    fun createWallet() {
        if (hasWallet()) {
            loadWallet()
            return
        }
        val created = Wallet.createDeterministic(BitcoinNetwork.TESTNET, ScriptType.P2WPKH)
        created.saveToFile(walletFile)
        wallet = created
    }

    @Synchronized
    fun loadWallet(): Wallet? {
        wallet?.let { return it }
        if (!walletFile.exists()) return null
        return Wallet.loadFromFile(walletFile).also { wallet = it }
    }

    fun getReceiveAddress(): String? = loadWallet()?.currentReceiveAddress()?.toString()

    fun getBalanceSats(): Long = loadWallet()?.balance?.value ?: 0L

    /**
     * Returns BIP39 recovery words for an explicit backup screen.
     * Never send this value to OpenRouter, logs, analytics, or connectors.
     */
    fun recoveryWords(): List<String>? = loadWallet()?.keyChainSeed?.mnemonicCode
}
