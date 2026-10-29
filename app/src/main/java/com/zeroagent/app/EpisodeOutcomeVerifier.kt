package com.zeroagent.app

/**
 * Conservative, deterministic evidence classification for a navigation.
 * Screen changes are not goal completion. The AI verifier may confirm a goal
 * only after a same-host, observable page change.
 */
object EpisodeOutcomeVerifier {
    data class Evidence(
        val previousHost: String,
        val currentHost: String,
        val previousUrl: String,
        val currentUrl: String,
        val previousInventory: String,
        val currentInventory: String,
        val currentText: String,
        val goalConfirmed: Boolean
    )
    data class Result(val outcome: String, val reason: String)

    fun classify(e: Evidence): Result {
        if (e.previousHost.isBlank() || e.previousHost != e.currentHost) {
            return Result("failed", "別ホストへの移動またはホスト不明")
        }
        val changed = e.previousUrl != e.currentUrl || e.previousInventory != e.currentInventory
        if (!changed) return Result("failed", "画面とURLに変化がありません")
        if (e.goalConfirmed && e.currentText.isNotBlank()) {
            return Result("success", "再観測した画面で工程達成を確認")
        }
        return Result("unknown", "画面は変化したが工程達成は未確認")
    }
}
