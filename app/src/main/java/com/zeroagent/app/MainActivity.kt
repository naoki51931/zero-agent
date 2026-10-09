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
import com.zeroagent.app.openrouter.OpenRouterModel
import com.zeroagent.app.wallet.WalletManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZeroAgentApp(WalletManager(this), LocalSettings(this)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZeroAgentApp(wallet: WalletManager, settings: LocalSettings) {
    val scope = rememberCoroutineScope()
    val openRouter = remember { OpenRouterClient() }
    val providers = listOf("OpenAI", "Gemini", "Claude", "Grok", "その他")
    val reasoningOptions = listOf("low", "medium", "high")
    var status by remember { mutableStateOf("READY") }
    var mode by remember { mutableStateOf(settings.get("mode", "HYBRID")) }
    var apiKey by remember { mutableStateOf(settings.get("apiKey", "")) }
    var provider by remember { mutableStateOf(settings.get("provider", "OpenAI")) }
    var providerMenu by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<OpenRouterModel>>(emptyList()) }
    var model by remember { mutableStateOf(settings.get("model", "")) }
    var modelMenu by remember { mutableStateOf(false) }
    var modelLoadMessage by remember { mutableStateOf("モデル一覧を読み込んでいます…") }
    var reasoning by remember { mutableStateOf(settings.get("reasoning", "high")) }
    var reasoningMenu by remember { mutableStateOf(false) }
    var objective by remember { mutableStateOf(settings.get("objective", "オリジナルコンテンツを企画し、必要な制作物と配布先を決め、各サービスで実行可能な行動計画を作る")) }
    var result by remember { mutableStateOf("") }
    var guideSteps by remember { mutableStateOf<List<String>>(emptyList()) }
    var guideMessage by remember { mutableStateOf("") }
    var browserOpen by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(wallet.getReceiveAddress() ?: "未作成") }
    var balance by remember { mutableLongStateOf(wallet.getBalanceSats()) }

    suspend fun loadModels() {
        status = "MODELS LOADING"
        modelLoadMessage = "OpenRouterからモデル一覧を取得中…"
        try {
            val loaded = openRouter.fetchModels(apiKey)
            models = loaded
            modelLoadMessage = "${loaded.size}件のモデルを取得しました"
            status = "READY"
        } catch (e: Exception) {
            modelLoadMessage = e.message ?: "モデル取得エラー"
            status = "FAILED"
        }
    }

    // Persist selections immediately; restore them on the next app launch.
    LaunchedEffect(mode) { settings.put("mode", mode) }
    LaunchedEffect(apiKey) { settings.put("apiKey", apiKey) }
    LaunchedEffect(provider) { settings.put("provider", provider) }
    LaunchedEffect(model) { settings.put("model", model) }
    LaunchedEffect(reasoning) { settings.put("reasoning", reasoning) }
    LaunchedEffect(objective) { settings.put("objective", objective) }

    LaunchedEffect(Unit) { loadModels() }

    fun matchesProvider(id: String): Boolean = when (provider) {
        "OpenAI" -> id.startsWith("openai/")
        "Gemini" -> id.startsWith("google/") && id.contains("gemini", true)
        "Claude" -> id.startsWith("anthropic/") && id.contains("claude", true)
        "Grok" -> (id.startsWith("x-ai/") || id.startsWith("xai/")) && id.contains("grok", true)
        else -> !id.startsWith("openai/") && !(id.startsWith("google/") && id.contains("gemini", true)) && !(id.startsWith("anthropic/") && id.contains("claude", true)) && !((id.startsWith("x-ai/") || id.startsWith("xai/")) && id.contains("grok", true))
    }
    val filteredModels = models.filter { matchesProvider(it.id) }

    MaterialTheme {
        if (browserOpen && guideSteps.isNotEmpty()) {
            GuidedBrowser(guideSteps) { browserOpen = false }
        } else Scaffold { padding ->
            Column(Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                if (!wallet.hasWallet()) Button(onClick = { wallet.createWallet(); address = wallet.getReceiveAddress() ?: "生成エラー"; balance = wallet.getBalanceSats() }) { Text("Testnetウォレットを作成") }
                Text("※ Testnet専用。実BTCは送らないでください。")
                HorizontalDivider()
                Text("OpenRouter AI", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key（企画生成時に必要）") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Button(enabled = status != "MODELS LOADING", onClick = { scope.launch { loadModels() } }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (status == "MODELS LOADING") "取得中…" else "最新モデル一覧を再取得")
                }
                Text(modelLoadMessage, style = MaterialTheme.typography.bodySmall)

                ExposedDropdownMenuBox(expanded = providerMenu, onExpandedChange = { providerMenu = !providerMenu }) {
                    OutlinedTextField(value = provider, onValueChange = {}, readOnly = true, label = { Text("AI系統") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerMenu) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                        providers.forEach { p -> DropdownMenuItem(text = { Text(p) }, onClick = { provider = p; model = ""; providerMenu = false }) }
                    }
                }

                ExposedDropdownMenuBox(expanded = modelMenu, onExpandedChange = { if (filteredModels.isNotEmpty()) modelMenu = !modelMenu }) {
                    OutlinedTextField(value = model.ifBlank { if (models.isEmpty()) "モデル一覧を取得中" else if (filteredModels.isEmpty()) "該当モデルなし" else "モデルを選択" }, onValueChange = {}, readOnly = true, label = { Text("モデル (${filteredModels.size})") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelMenu) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        filteredModels.forEach { m -> DropdownMenuItem(text = { Text("${m.name}\n${m.id}") }, onClick = { model = m.id; modelMenu = false }) }
                    }
                }

                ExposedDropdownMenuBox(expanded = reasoningMenu, onExpandedChange = { reasoningMenu = !reasoningMenu }) {
                    OutlinedTextField(value = reasoning, onValueChange = {}, readOnly = true, label = { Text("思考レベル") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(reasoningMenu) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = reasoningMenu, onDismissRequest = { reasoningMenu = false }) { reasoningOptions.forEach { r -> DropdownMenuItem(text = { Text(r) }, onClick = { reasoning = r; reasoningMenu = false }) } }
                }
                Text("使用モデル: ${model.ifBlank { "未選択" }}")
                OutlinedTextField(value = objective, onValueChange = { objective = it }, label = { Text("目的") }, modifier = Modifier.fillMaxWidth())
                Button(modifier = Modifier.fillMaxWidth().height(64.dp), enabled = status != "PLANNING" && apiKey.isNotBlank() && model.isNotBlank(), onClick = {
                    scope.launch { status = "PLANNING"; result = ""; try { result = openRouter.createPlan(apiKey, model, objective, reasoning); guideSteps = result.lines().map { it.trim() }.filter { it.isNotBlank() && (it.matches(Regex("^([0-9]+[.．)、]|[-•]).*")) || it.startsWith("ステップ") || it.startsWith("手順")) }.take(12).ifEmpty { result.split("\\n\\n").map { it.trim() }.filter { it.isNotBlank() }.take(8) }; status = "PLAN READY"; guideMessage = "Chromeを開き、次の操作を吹き出しで案内できます" } catch (e: Exception) { result = e.message ?: "エラー"; status = "FAILED" } }
                }) { Text("企画生成・拡散案を作る") }
                if (result.isNotBlank()) { Text("生成結果", style = MaterialTheme.typography.titleMedium); Text(result) }
                if (guideSteps.isNotEmpty()) {
                    Button(onClick = { browserOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("アプリ内ブラウザで操作案内")
                    }
                    Text(guideMessage)
                }

                Text("設定は端末内に暗号化保存し、次回起動時に復元します。モデル一覧は起動時に更新します。")
            }
        }
    }
}
