package com.zeroagent.app

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Human-reviewed SeaArt handoff; imported images stay in private app storage. */
class CharacterDraftActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var body: LinearLayout
    private lateinit var store: CharacterDraftStore
    private var pendingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CharacterDraftStore(this)
        val scroll = ScrollView(this).apply { fitsSystemWindows = true }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(18, 24, 18, 32)
        }
        scroll.addView(body)
        setContentView(scroll)
        render()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun label(value: String, size: Float = 15f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.BLACK); setPadding(8, 12, 8, 12)
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; setOnClickListener { action() }
    }
    private fun notify(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun render() {
        body.removeAllViews()
        body.addView(label("SeaArt画像生成・投稿準備", 22f))
        body.addView(label("Xで抽出した未検証のいいね数からAIがオリジナルのアニメキャラ案を選びます。いいね数の予測は保証されません。"))
        body.addView(label("SeaArt操作状態: " + LocalSettings(this).get("seaartPendingState", "未開始") + "。生成後はSeaArtのダウンロードを自動操作します。Chromeのダウンロード完了後、下のボタンから画像を選んで投稿待ちへ取り込んでください。"))
        body.addView(button("AIにキャラを選ばせて生成案を作成（OpenRouter料金あり）") {
            val settings = LocalSettings(this)
            if (settings.get("apiKey").isBlank() || settings.get("model").isBlank()) {
                notify("OpenRouter APIキーとモデルを設定してください")
                return@button
            }
            scope.launch {
                try {
                    val observations = ResearchEvidenceStore(this@CharacterDraftActivity).use { it.imageLikes() }
                    val draft = CharacterDraftPlanner.select(settings, observations)
                    store.add(draft)
                    render()
                } catch (e: Exception) { notify("キャラ選択失敗: " + e.message.orEmpty().take(130)) }
            }
        })
        val rows = store.list()
        body.addView(label("保存済み生成案: " + rows.length() + " 件", 18f))
        for (i in rows.length() - 1 downTo 0) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("id")
            body.addView(label(row.optString("title") + " / " + row.optString("status"), 18f))
            body.addView(label(row.optString("rationale")))
            body.addView(label("生成プロンプト:\n" + row.optString("prompt")))
            body.addView(label("除外プロンプト:\n" + row.optString("negative_prompt")))
            body.addView(label("投稿用下書き:\n" + row.optString("caption")))
            val modelInput = EditText(this).apply {
                hint = "SeaArtモデル"
                setSingleLine(true)
                setText(row.optString("seaart_model", "GR-Illustrious 3in1"))
            }
            val loraInput = EditText(this).apply {
                hint = "LoRA名（任意）"
                setSingleLine(true)
                setText(row.optString("seaart_lora"))
            }
            body.addView(label("AIのLoRA候補: " + row.optString("lora_hint", "未提案")))
            body.addView(modelInput)
            body.addView(loraInput)
            body.addView(button("モデル・LoRAを保存") {
                store.updateGenerationSettings(id, modelInput.text.toString(), loraInput.text.toString())
                notify("モデル・LoRA設定を保存しました")
            })
            body.addView(button("AndroidでSeaArt生成を開始（Chrome自動操作）") {
                val settings = LocalSettings(this)
                settings.put("seaartPendingId", id)
                settings.put("seaartPendingPrompt", row.optString("prompt"))
                settings.put("seaartPendingModel", modelInput.text.toString().ifBlank { "GR-Illustrious 3in1" })
                settings.put("seaartPendingLora", loraInput.text.toString())
                settings.put("seaartPendingState", "awaiting_editor")
                settings.put("seaartPendingSince", System.currentTimeMillis().toString())
                notify("ChromeのSeaArtを開きます。ログイン済みであることを確認してください")
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.seaart.ai/ja/")))
                } catch (e: Exception) {
                    settings.put("seaartPendingState", "")
                    notify("SeaArtを開けません: " + e.message)
                }
            })
            body.addView(button("SeaArt自動操作を中止") {
                LocalSettings(this).put("seaartPendingState", "")
                notify("SeaArt操作を中止しました")
            })
            body.addView(button("プロンプトをコピーしてSeaArtを開く") {
                val prompt = row.optString("prompt") + "\nNegative: " + row.optString("negative_prompt")
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("SeaArt prompt", prompt))
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.seaart.ai/ja/")))
                } catch (e: Exception) { notify("SeaArtを開けません: " + e.message) }
            })
            body.addView(button("ダウンロード済みSeaArt画像を取り込む（投稿待ち）") {
                pendingId = id
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }, 101)
            })
            if (row.optString("status") == "ready_for_post_review") {
                body.addView(label("画像保存済み・次工程の投稿レビュー待ち。自動投稿は行いません。"))
            }
        }
    }
    @Deprecated("Legacy document picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 101 || resultCode != RESULT_OK) return
        val id = pendingId ?: return
        pendingId = null
        val uri = data?.data ?: return
        try {
            if (store.attachImage(id, uri)) {
                notify("画像を端末内に保存しました。投稿準備完了")
                render()
            }
        } catch (e: Exception) { notify("画像保存失敗: " + e.message.orEmpty().take(100)) }
    }
}
