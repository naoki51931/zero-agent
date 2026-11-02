package com.zeroagent.app

/** Ranking only: never adds search terms not supplied by the user's objective. */
object XResearchPriority {
    private val pairing = listOf("同一CP", "同じCP", "同一カップリング", "カップリング", "CP")
    private val identity = listOf("同じ顔", "同一人物", "2人", "二人", "双子", "分身", "クローン")
    private val creation = listOf("キャラ", "キャラクター", "生成", "イラスト", "画像", "プロンプト", "SeaArt", "LoRA", "顔", "髪型", "衣装", "設定")

    fun score(value: String): Int {
        val q = value.lowercase()
        fun has(terms: List<String>) = terms.any { q.contains(it.lowercase()) }
        val pair = has(pairing)
        val same = has(identity)
        val creative = has(creation)
        return (if (pair) 100 else 0) + (if (same) 55 else 0) +
            (if (creative) 35 else 0) + (if (pair && same) 40 else 0) +
            (if (pair && creative) 25 else 0)
    }
}
