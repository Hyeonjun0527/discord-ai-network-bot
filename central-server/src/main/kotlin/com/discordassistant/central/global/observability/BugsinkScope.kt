package com.discordassistant.central.global.observability

import io.sentry.IScope

internal object BugsinkScope {
    const val SERVICE_NAME = "nexa-api"

    fun tagRequest(
        scope: IScope,
        requestId: String,
        method: String,
        endpoint: String,
    ) {
        scope.setTag("app", "api")
        scope.setTag("service", SERVICE_NAME)
        scope.setTag("requestId", requestId)
        scope.setTag("method", method)
        scope.setTag("endpoint", endpoint)
        scope.setContexts(
            "request",
            mapOf(
                "requestId" to requestId,
                "method" to method,
                "endpoint" to endpoint,
            ),
        )
    }

    fun tagApiError(
        scope: IScope,
        endpoint: String,
        method: String,
        status: Int,
        requestId: String?,
    ) {
        scope.setTag("app", "api")
        scope.setTag("service", SERVICE_NAME)
        scope.setTag("endpoint", endpoint)
        scope.setTag("method", method)
        scope.setTag("httpStatus", status.toString())
        if (!requestId.isNullOrBlank()) {
            scope.setTag("requestId", requestId)
        }
        scope.setContexts(
            "api",
            mapOf(
                "requestId" to requestId,
                "endpoint" to endpoint,
                "method" to method,
                "httpStatus" to status,
            ),
        )
    }
}
