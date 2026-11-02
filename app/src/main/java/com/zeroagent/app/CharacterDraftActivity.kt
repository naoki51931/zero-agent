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
import android.text.InputType
import android.provider.DocumentsContract
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
    override fun onResume() {
        super.onResume()
        scope.launch {
            val result = SeaArtDownloadImporter.scan(this@CharacterDraftActivity)
            if (result.status == "imported") {
                notify("SeaArt画像を投稿待ちへ自動登録: " + result.detail)
                render()
            }
        }
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
        body.addView(label("直近の操作診断: " + LocalSettings(this).get("seaartLastError", "なし")))
        body.addView(button("SeaArt操作ログを表示・更新") {
            val log = LocalSettings(this).get("seaartActionLog")
            android.app.AlertDialog.Builder(this)
                .setTitle("SeaArt自動操作ログ")
                .setMessage(log.ifBlank { "まだ操作ログがありません。自動操作を開始してください。" })
                .setPositiveButton("閉じる", null)
                .setNeutralButton("コピー") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("SeaArt操作ログ", log))
                    notify("操作ログをコピーしました")
                }
                .show()
        })
        body.addView(button("SeaArt操作ログをクリア") {
            LocalSettings(this).put("seaartActionLog", "")
            notify("操作ログをクリアしました")
        })
        body.addView(label("SeaArt操作状態: " + LocalSettings(this).get("seaartPendingState", "未開始") + "。生成後はSeaArtのダウンロードを自動操作します。SeaArt名を含む画像を日時で照合し、指定フォルダに投稿用コピーを作成して投稿待ちへ自動取り込みします。候補が複数の場合は手動選択してください。"))
        body.addView(button("SeaArtダウンロード・投稿用コピー保存フォルダを選択") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, 102)
        })
        body.addView(button("SeaArtダウンロードを照合して自動取り込み") {
            scope.launch {
                val result = SeaArtDownloadImporter.scan(this@CharacterDraftActivity)
                notify(when (result.status) {
                    "imported" -> "投稿待ちに登録: " + result.detail
                    "permission_needed" -> "ダウンロードフォルダの選択が必要です"
                    "ambiguous" -> "候補が複数あります。手動で画像を選択してください"
                    "not_found" -> "条件に合うSeaArt画像がまだありません"
                    else -> "照合結果: " + result.status
                })
                render()
            }
        })
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
            val repeatInput = EditText(this).apply { hint = "生成回数（1〜20）"; inputType = InputType.TYPE_CLASS_NUMBER; setText("1") }
            body.addView(repeatInput)
            body.addView(button("SeaArt指定回数生成を開始") {
                val count = repeatInput.text.toString().toIntOrNull()
                if (count == null || count !in 1..20) { notify("1〜20回を指定してください"); return@button }
                val settings = LocalSettings(this)
                settings.put("seaartPendingId", id)
                settings.put("seaartPendingPrompt", row.optString("prompt"))
                settings.put("seaartPendingModel", modelInput.text.toString().ifBlank { "GR-Illustrious 3in1" })
                settings.put("seaartPendingLora", loraInput.text.toString())
                settings.put("seaartRepeatTotal", count.toString())
                settings.put("seaartRepeatCompleted", "0")
                settings.put("seaartImportedNames", "")
                settings.put("seaartLastError", "")
                settings.put("seaartActionLog", "SeaArt自動操作開始: 下部の創作タブを優先\nChrome起動を要求しました。画面イベント待機中\n")
                settings.put("seaartPendingState", "awaiting_create")
                settings.put("seaartPendingSince", System.currentTimeMillis().toString())
                notify("SeaArtで創作→モデル→LoRA→生成を自動操作します。ログイン済みであることを確認してください")
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.seaart.ai/ja/")))
                } catch (e: Exception) {
                    settings.put("seaartPendingState", "")
                    settings.put("seaartActionLog", settings.get("seaartActionLog") + "Chrome起動失敗: " + e.javaClass.simpleName + "\\n")
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
        if (requestCode == 102 && resultCode == RESULT_OK) {
            val tree = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                LocalSettings(this).put("seaartDownloadTree", tree.toString())
                notify("投稿用コピーの保存先フォルダを設定しました（読み書き許可）")
            } catch (e: Exception) { notify("フォルダ許可を保存できません: " + e.message) }
            return
        }
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
