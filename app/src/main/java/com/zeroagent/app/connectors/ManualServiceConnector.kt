package com.zeroagent.app.connectors

/**
 * Generic connector for services that currently require a real person to operate them.
 * This does not attempt to imitate human behavior or bypass service safeguards.
 */
class ManualServiceConnector(
    override val id: String,
    override val displayName: String,
    private val supportedCapabilities: Set<Capability>
) : ServiceConnector {
    override val operationMode: OperationMode = OperationMode.HUMAN

    override fun capabilities(): Set<Capability> = supportedCapabilities

    override fun isConfigured(): Boolean = true

    override suspend fun healthCheck(): Boolean = true

    override suspend fun execute(action: ConnectorAction): ActionResult = ActionResult(
        success = false,
        message = "$displayName を開いて人間が ${action.capability} を実行してください。完了後にAIへ戻せます。",
        humanActionRequired = true
    )
}
