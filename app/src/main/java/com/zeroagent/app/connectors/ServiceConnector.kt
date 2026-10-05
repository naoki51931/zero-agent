package com.zeroagent.app.connectors

enum class Capability {
    PUBLISH_TEXT,
    PUBLISH_IMAGE,
    GENERATE_TEXT,
    GENERATE_IMAGE,
    READ_CONTENT,
    CREATE_PRODUCT,
    READ_METRICS
}

enum class OperationMode {
    API,
    HUMAN,
    HYBRID,
    UNSUPPORTED
}

data class ConnectorAction(
    val capability: Capability,
    val params: Map<String, String> = emptyMap()
)

data class ActionResult(
    val success: Boolean,
    val message: String,
    val externalId: String? = null,
    val humanActionRequired: Boolean = false
)

interface ServiceConnector {
    val id: String
    val displayName: String
    val operationMode: OperationMode

    fun capabilities(): Set<Capability>
    fun isConfigured(): Boolean
    suspend fun healthCheck(): Boolean
    suspend fun execute(action: ConnectorAction): ActionResult
}
