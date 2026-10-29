package com.zeroagent.app

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
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
    var result by remember { mutableStateOf(settings.get("lastPlan", "")) }
    var planTitle by remember { mutableStateOf(settings.get("lastPlanTitle", "")) }
    var planExpanded by remember { mutableStateOf(false) }
    var followupAnswer by remember { mutableStateOf(settings.get("planFollowupDraft", "")) }
    var guideSteps by remember { mutableStateOf<List<String>>(emptyList()) }
    fun stepsFromPlan(plan: String): List<String> {
        val lines = plan.lines().map { it.trim() }
        val numbered = lines.filter { it.matches(Regex("^(?:[0-9]{1,2}[.．)、:]|[-•]\\s+|ステップ[0-9０-９]+).*")) }
            .map { it.replace(Regex("^[0-9]{1,2}[.．)、:]\\s*"), "").replace(Regex("^[-•]\\s*"), "") }
            .filter { it.length >= 8 && it != "---" }.take(12)
        return numbered.ifEmpty {
            lines.filter { it.length >= 12 && !it.startsWith("#") && it != "---" }.take(8)
        }.ifEmpty { listOf("目標を確認し、必要な情報を検索する") }
    }
    fun titleFromPlan(plan: String, goal: String): String {
        val candidate = plan.lines().map { it.trim().trimStart('#', ' ', '*') }
            .firstOrNull { it.isNotBlank() && !it.matches(Regex("^[0-9]+[.．)、].*")) }
            .orEmpty().ifBlank { goal }
        return candidate.replace(Regex("^[【「『]|[】」』]$"), "").take(45)
    }
    var guideMessage by remember { mutableStateOf("") }
    var browserOpen by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(wallet.getReceiveAddress() ?: "未作成") }
    var balance by remember { mutableLongStateOf(wallet.getBalanceSats()) }
    var backupPassword by remember { mutableStateOf("") }
    var backupMessage by remember { mutableStateOf("") }
    fun saveCurrentPlanToSettings() {
        settings.put("objective", objective)
        settings.put("lastPlan", result)
        settings.put("lastPlanTitle", planTitle)
        settings.put("planFollowupDraft", followupAnswer)
        settings.put("apiKey", apiKey)
        settings.put("model", model)
        settings.put("provider", provider)
        settings.put("reasoning", reasoning)
        settings.put("mode", mode)
    }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try {
                SettingsBackup.export(context, uri, backupPassword, settings)
                backupMessage = "暗号化バックアップを保存しました"
            } catch (e: Exception) {
                backupMessage = "保存失敗: ${e.message ?: "不明なエラー"}"
            }
        }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                SettingsBackup.restore(context, uri, backupPassword, settings)
                apiKey = settings.get("apiKey")
                objective = settings.get("objective")
                model = settings.get("model")
                provider = settings.get("provider", "OpenAI")
                reasoning = settings.get("reasoning", "high")
                mode = settings.get("mode", "HYBRID")
                result = settings.get("lastPlan", "")
                planTitle = settings.get("lastPlanTitle", "")
                followupAnswer = settings.get("planFollowupDraft", "")
                guideSteps = if (result.isNotBlank()) stepsFromPlan(result) else emptyList()
                planExpanded = result.isNotBlank()
                backupMessage = "復元しました。モデル一覧は必要に応じて再取得してください"
            } catch (e: Exception) {
                backupMessage = "復元失敗（パスワード・ファイルを確認）: ${e.message ?: "不明なエラー"}"
            }
        }
    }


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
    LaunchedEffect(result) { settings.put("lastPlan", result) }
    LaunchedEffect(planTitle) { settings.put("lastPlanTitle", planTitle) }
    LaunchedEffect(followupAnswer) { settings.put("planFollowupDraft", followupAnswer) }
    LaunchedEffect(Unit) { if (result.isNotBlank()) guideSteps = stepsFromPlan(result) }

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
            GuidedBrowser(guideSteps, apiKey, model, objective) { browserOpen = false }
        } else Scaffold { padding ->
            Column(Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ZERO AGENT", style = MaterialTheme.typography.headlineLarge)
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Chrome AI操作を有効化（ユーザー補助設定）") }
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
                    scope.launch { status = "PLANNING"; try { result = openRouter.createPlan(apiKey, model, objective, reasoning); planTitle = titleFromPlan(result, objective); guideSteps = stepsFromPlan(result); planExpanded = true; status = "PLAN READY"; guideMessage = "Chromeを開き、次の操作を吹き出しで案内できます" } catch (e: Exception) { result = e.message ?: "エラー"; status = "FAILED" } }
                }) { Text("企画生成・拡散案を作る") }
                if (result.isNotBlank()) {
                    ElevatedCard(onClick = { planExpanded = !planExpanded }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(planTitle.ifBlank { "生成した企画" }, style = MaterialTheme.typography.titleMedium)
                            Text(if (planExpanded) "タップして閉じる" else "タップして提案とブラウザ操作を開く", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (planExpanded) {
                        Text("AIの提案", style = MaterialTheme.typography.titleMedium)
                        Text(result)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = backupPassword.length >= 12,
                                onClick = {
                                    saveCurrentPlanToSettings()
                                    exportBackup.launch("zero-agent-settings.json")
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("企画を保存") }
                            OutlinedButton(
                                enabled = backupPassword.isNotEmpty(),
                                onClick = { importBackup.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                                modifier = Modifier.weight(1f)
                            ) { Text("企画を読み込み") }
                        }
                        Text("下の外部バックアップ欄でパスワードを設定してください。企画・モデル・APIキーを暗号化してzero-agent-settings.jsonに保存します。", style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text("AIからの質問に回答", style = MaterialTheme.typography.titleMedium)
                        Text("上の企画で聞かれた内容を入力し、回答を反映した企画を再生成できます。")
                        OutlinedTextField(
                            value = followupAnswer,
                            onValueChange = { followupAnswer = it },
                            label = { Text("回答・追加指示") },
                            placeholder = { Text("例：CP名は〇〇、参考アカウントは△△、画風はアニメ調") },
                            minLines = 3,
                            maxLines = 8,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            enabled = followupAnswer.isNotBlank() && status != "PLANNING" && apiKey.isNotBlank() && model.isNotBlank(),
                            onClick = {
                                val answer = followupAnswer.trim()
                                val previousPlan = result.take(6000)
                                scope.launch {
                                    status = "PLANNING"
                                    try {
                                        val refinedGoal = objective + "\n\n前回の企画・AIの質問:\n" + previousPlan +
                                            "\n\nユーザーからの回答・追加指示:\n" + answer +
                                            "\n\n上記の回答を踏まえて、具体的な次のアクションを含む更新版企画を作成してください。"
                                        val updated = openRouter.createPlan(apiKey, model, refinedGoal, reasoning)
                                        result = updated
                                        planTitle = titleFromPlan(updated, objective)
                                        guideSteps = stepsFromPlan(updated)
                                        followupAnswer = ""
                                        planExpanded = true
                                        status = "PLAN READY"
                                    } catch (e: Exception) {
                                        guideMessage = "回答の送信に失敗: ${e.message ?: "不明なエラー"}"
                                        status = "FAILED"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("回答を送って企画を更新") }
                        Button(onClick = { browserOpen = true }, enabled = guideSteps.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Text("この企画をアプリ内ブラウザで進める")
                        }
                    }
                }
                if (guideMessage.isNotBlank()) Text(guideMessage)

                HorizontalDivider()
                Text("外部バックアップ（暗号化）", style = MaterialTheme.typography.titleLarge)
                Text("APIキー・行動目標・モデル・AI系統・思考レベル・操作モード・現在の企画・回答下書きを、選んだ保存先に暗号化して書き出します。")
                OutlinedTextField(
                    value = backupPassword,
                    onValueChange = { backupPassword = it },
                    label = { Text("バックアップ用パスワード（12文字以上）") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = backupPassword.length >= 12,
                    onClick = { saveCurrentPlanToSettings(); exportBackup.launch("zero-agent-settings.json") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("外部ファイルに暗号化保存") }
                OutlinedButton(
                    enabled = backupPassword.isNotEmpty(),
                    onClick = { importBackup.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("外部ファイルから復元") }
                if (backupMessage.isNotBlank()) Text(backupMessage)
                Text("バックアップ用パスワードを忘れると復元できません。ファイルとパスワードは別々に管理してください。")
                Text("通常の設定は端末内に暗号化保存し、次回起動時に復元します。モデル一覧は起動時に更新します。")
            }
        }
    }
}
