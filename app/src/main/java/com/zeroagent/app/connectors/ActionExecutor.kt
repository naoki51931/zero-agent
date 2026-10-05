package com.zeroagent.app.connectors

class ActionExecutor(
    private val registry: ConnectorRegistry
) {
    suspend fun execute(action: ConnectorAction): ActionResult {
        val connector = registry.resolve(action.capability)
            ?: return ActionResult(
                success = false,
                message = "${action.capability} を実行できる接続済みサービスがありません。",
                humanActionRequired = true
            )

        return when (connector.operationMode) {
            OperationMode.API -> connector.execute(action)
            OperationMode.HYBRID -> connector.execute(action)
            OperationMode.HUMAN -> ActionResult(
                success = false,
                message = "${connector.displayName} は人間による操作が必要です。",
                humanActionRequired = true
            )
            OperationMode.UNSUPPORTED -> ActionResult(
                success = false,
                message = "${connector.displayName} ではこの操作を実行できません。"
            )
        }
    }
}
