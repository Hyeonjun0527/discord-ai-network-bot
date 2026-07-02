package com.discordassistant.central.global.error

/**
 * REST error code registry. Client-visible codes must be added here before server code uses them.
 */
object ApiErrorCodes {
    const val NOT_FOUND = "NOT_FOUND"
    const val INVALID_REQUEST = "INVALID_REQUEST"
    const val CONFLICT = "CONFLICT"
    const val FORBIDDEN = "FORBIDDEN"
    const val INVALID_STATE_TRANSITION = "INVALID_STATE_TRANSITION"
    const val PRECONDITION_FAILED = "PRECONDITION_FAILED"
    const val DASHBOARD_ADMIN_REQUIRED = "DASHBOARD_ADMIN_REQUIRED"
    const val INVALID_SERVER_STATE = "INVALID_SERVER_STATE"
    const val INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR"
    const val ERROR = "ERROR"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE"

    val registeredCodes: Set<String> =
        setOf(
            NOT_FOUND,
            INVALID_REQUEST,
            CONFLICT,
            FORBIDDEN,
            INVALID_STATE_TRANSITION,
            PRECONDITION_FAILED,
            DASHBOARD_ADMIN_REQUIRED,
            INVALID_SERVER_STATE,
            INTERNAL_SERVER_ERROR,
            ERROR,
            UNAUTHORIZED,
            SERVICE_UNAVAILABLE,
        )

    fun isRegistered(code: String): Boolean = code in registeredCodes
}

object ApiErrorDetailKeys {
    const val ALLOWED_VALUES = "allowedValues"
    const val ACTUAL_VALUE = "actualValue"
}

data class ApiErrorDetailsSchema(
    val optionalKeys: Set<String> = emptySet(),
    val requiredKeys: Set<String> = emptySet(),
) {
    val allowedKeys: Set<String> = requiredKeys + optionalKeys

    fun unexpectedKeys(details: Map<String, Any?>): Set<String> = details.keys - allowedKeys

    fun missingRequiredKeys(details: Map<String, Any?>): Set<String> = requiredKeys - details.keys
}

object ApiErrorDetailsSchemas {
    val schemasByCode: Map<String, ApiErrorDetailsSchema> =
        mapOf(
            ApiErrorCodes.PRECONDITION_FAILED to
                ApiErrorDetailsSchema(
                    optionalKeys = setOf(ApiErrorDetailKeys.ALLOWED_VALUES, ApiErrorDetailKeys.ACTUAL_VALUE),
                ),
        )

    fun schemaFor(code: String): ApiErrorDetailsSchema? = schemasByCode[code]
}
