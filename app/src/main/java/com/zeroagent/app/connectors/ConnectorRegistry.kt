package com.zeroagent.app.connectors

class ConnectorRegistry {
    private val connectors = linkedMapOf<String, ServiceConnector>()

    fun register(connector: ServiceConnector) {
        connectors[connector.id] = connector
    }

    fun unregister(id: String) {
        connectors.remove(id)
    }

    fun all(): List<ServiceConnector> = connectors.values.toList()

    fun findByCapability(capability: Capability): List<ServiceConnector> =
        connectors.values.filter {
            capability in it.capabilities() && it.operationMode != OperationMode.UNSUPPORTED
        }

    fun resolve(capability: Capability): ServiceConnector? =
        findByCapability(capability)
            .sortedWith(
                compareBy<ServiceConnector> {
                    when (it.operationMode) {
                        OperationMode.API -> 0
                        OperationMode.HYBRID -> 1
                        OperationMode.HUMAN -> 2
                        OperationMode.UNSUPPORTED -> 3
                    }
                }.thenByDescending { it.isConfigured() }
            )
            .firstOrNull { it.isConfigured() }
}
