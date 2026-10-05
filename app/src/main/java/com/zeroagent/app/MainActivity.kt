package com.zeroagent.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zeroagent.app.wallet.WalletManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wallet = WalletManager(this)
        setContent { ZeroAgentApp(wallet) }
    }
}

@Composable
fun ZeroAgentApp(wallet: WalletManager) {
    var status by remember { mutableStateOf("READY") }
    var walletStatus by remember { mutableStateOf(if (wallet.hasWallet()) "WALLET READY" else "NO WALLET") }
    var address by remember { mutableStateOf(wallet.getReceiveAddress() ?: "未作成") }
    var mode by remember { mutableStateOf("HYBRID") }

    MaterialTheme {
        Scaffold { padding ->
            Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("ZERO AGENT", style = MaterialTheme.typography.headlineLarge)
                Text("Creator Agent  $status")
                Text("操作モード: $mode")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { mode = "AGENT" }) { Text("AI") }
                    Button(onClick = { mode = "HUMAN"; status = "HUMAN CONTROL" }) { Text("人間が操作") }
                    Button(onClick = { mode = "HYBRID"; status = "READY" }) { Text("HYBRID") }
                }

                HorizontalDivider()
                Text("Bitcoin Wallet", style = MaterialTheme.typography.titleLarge)
                Text(walletStatus)
                Text("受取アドレス: $address")
                if (!wallet.hasWallet()) {
                    Button(onClick = {
                        wallet.createWallet()
                        walletStatus = "WALLET READY"
                        address = wallet.getReceiveAddress() ?: "生成エラー"
                    }) { Text("ウォレットを作成") }
                }

                HorizontalDivider()
                Button(
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    onClick = { status = "PLANNING" }
                ) { Text("企画生成・拡散") }
                Text("OpenRouter・Connector実行は次の実装段階で接続します。")
            }
        }
    }
}
