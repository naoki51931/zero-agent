package com.zeroagent.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** Read-only screen for locally saved, unverified X research observations. */
class XResearchResultsActivity : Activity() {
    private lateinit var body: LinearLayout

    private fun text(value: String, size: Float = 15f): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.BLACK)
        setPadding(18, 12, 18, 12)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "X調査結果"
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(12, 16, 12, 20)
        }
        scroll.addView(body)
        setContentView(scroll)
        showResults()
    }

    private fun showResults() {
        body.removeAllViews()
        body.addView(text("X調査結果（端末内保存）", 22f))
        body.addView(text("画面から抽出した未検証の候補です。個別投稿の真偽・いいね数は確定していません。", 13f))
        try {
            val rows = ResearchEvidenceStore(this).use { store ->
                store.exportJson().optJSONArray("records")
            }
            val total = rows?.length() ?: 0
            body.addView(text("保存済み画面証拠候補: $total 件", 18f))
            body.addView(text("※ 同一投稿の重複を含む可能性があります。", 12f))
            if (total == 0) body.addView(text("画面証拠候補はまだ保存されていません。"))
            // Newest evidence first. Avoid loading screenshot bitmaps into the UI.
            for (index in total - 1 downTo 0) {
                val row = rows?.optJSONObject(index) ?: continue
                val query = row.optString("query")
                val candidate = row.optString("candidate_text")
                val review = row.optString("image_review").takeIf { it != "null" && it.isNotBlank() }
                val likes = if (row.isNull("likes_estimate")) "未取得" else row.optLong("likes_estimate").toString()
                val label = "検索語: $query\\nいいね数: $likes（未検証）" +
                    (if (review != null) "\\n画像判定: $review" else "") +
                    "\\n候補テキスト: $candidate"
                body.addView(text(label, 14f).apply {
                    setBackgroundColor(if (index % 2 == 0) 0xFFF1F2F5.toInt() else 0xFFFFFFFF.toInt())
                })
                val uri = row.optString("screenshot_uri")
                if (uri.startsWith("content://")) {
                    body.addView(Button(this).apply {
                        text = "保存スクリーンショットを見る"
                        setOnClickListener {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(Uri.parse(uri), "image/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(this@XResearchResultsActivity,
                                    "画像を開けません: " + e.javaClass.simpleName,
                                    android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    })
                }
            }
            body.addView(text("いいね数付き候補（別保存）", 18f))
            body.addView(text(XResearchMemory(this).summarySaved()))
        } catch (e: Exception) {
            body.addView(text("保存データの読み込みに失敗しました: " + e.message.orEmpty().take(150)))
        }
    }
}
