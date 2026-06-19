package com.discordassistant.central.global.observability

import io.sentry.Scope
import io.sentry.SentryOptions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BugsinkScopeTest {
    @Test
    fun `request scope carries correlation tags and context`() {
        val scope = Scope(SentryOptions())

        BugsinkScope.tagRequest(scope, requestId = "req-1", method = "GET", endpoint = "/api/dashboard")

        assertEquals("api", scope.tags["app"])
        assertEquals("nexa-api", scope.tags["service"])
        assertEquals("req-1", scope.tags["requestId"])
        assertEquals("GET", scope.tags["method"])
        assertEquals("/api/dashboard", scope.tags["endpoint"])
        val context = scope.contexts["request"] as Map<*, *>
        assertEquals("req-1", context["requestId"])
        assertEquals("GET", context["method"])
        assertEquals("/api/dashboard", context["endpoint"])
    }

    @Test
    fun `api error scope carries status and optional request id`() {
        val scope = Scope(SentryOptions())

        BugsinkScope.tagApiError(
            scope,
            endpoint = "/provider/status",
            method = "POST",
            status = 503,
            requestId = "req-5xx",
        )

        assertEquals("api", scope.tags["app"])
        assertEquals("nexa-api", scope.tags["service"])
        assertEquals("/provider/status", scope.tags["endpoint"])
        assertEquals("POST", scope.tags["method"])
        assertEquals("503", scope.tags["httpStatus"])
        assertEquals("req-5xx", scope.tags["requestId"])
        val context = scope.contexts["api"] as Map<*, *>
        assertEquals("req-5xx", context["requestId"])
        assertEquals("/provider/status", context["endpoint"])
        assertEquals("POST", context["method"])
        assertEquals(503, context["httpStatus"])
    }
}
