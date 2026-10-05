package com.zeroagent.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zeroagent.app.openrouter.OpenRouterClient
import com.zeroagent.app.wallet.WalletManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZeroAgentApp(WalletManager(this)) }
    }
}

@Composable
fun ZeroAgentApp(wallet: WalletManager) {
    val scope = rememberCoroutineScope()
    val openRouter = remember { OpenRouterClient() }
    var status by remember { mutableStateOf("READY") }
    var mode by remember { mutableStateOf("HYBRID") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("openrouter/auto") }
    var objective by remember { mutableStateOf("オリジナルコンテンツを企画し、X向けの投稿案を作る") }
    var result by remember { mutableStateOf("") }
    var address by remember { mutableStateOf(wallet.getReceiveAddress() ?: "未作成") }
    var balance by remember { mutableLongStateOf(wallet.getBalanceSats()) }

    MaterialTheme {
        Scaffold { padding ->
            Column(
                Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("ZERO AGENT", style = MaterialTheme.typography.headlineLarge)
                Text("Creator Agent: $status")
                Text("操作モード: $mode")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { mode = "AGENT" }) { Text("AI") }
                    Button(onClick = { mode = "HUMAN"; status = "HUMAN CONTROL" }) { Text("人間") }
                    Button(onClick = { mode = "HYBRID"; status = "READY" }) { Text("HYBRID") }
                }

                HorizontalDivider()
                Text("Bitcoin TESTNET", style = MaterialTheme.typography.titleLarge)
                Text("残高: $balance sats")
                Text("受取: $address")
                if (!wallet.hasWallet()) {
                    Button(onClick = {
                        wallet.createWallet()
                        address = wallet.getReceiveAddress() ?: "生成エラー"
                        balance = wallet.getBalanceSats()
                    }) { Text("Testnetウォレットを作成") }
                }
                Text("※ Testnet専用。実BTCは送らないでください。")

                HorizontalDivider()
                Text("OpenRouter", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key（この画面では保存しません）") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = objective, onValueChange = { objective = it }, label = { Text("目的") }, modifier = Modifier.fillMaxWidth())

                Button(
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    enabled = status != "PLANNING",
                    onClick = {
                        scope.launch {
                            status = "PLANNING"
                            result = ""
                            try {
                                result = openRouter.createPlan(apiKey, model, objective)
                                status = "PLAN READY"
                            } catch (e: Exception) {
                                result = e.message ?: "エラー"
                                status = "FAILED"
                            }
                        }
                    }
                ) { Text("企画生成・拡散案を作る") }

                if (result.isNotBlank()) {
                    Text("生成結果", style = MaterialTheme.typography.titleMedium)
                    Text(result)
                }
                Text("外部SNSへの自動投稿はまだ無効です。生成結果を確認してからConnectorを接続します。")
            }
        }
    }
}
