package edu.jhu.cobra.commons.graph.storage

import io.github.oshai.kotlinlogging.KLogger

internal fun logCompletion(logger: KLogger, operation: String, storage: IStorage, started: Long) {
    logger.atInfo {
        message = "Storage operation completed"
        payload = mapOf(
            "operation" to operation,
            "implementation" to storage.javaClass.name,
            "storageId" to System.identityHashCode(storage),
            "outcome" to "success",
            "durationNs" to System.nanoTime() - started,
        )
    }
}
