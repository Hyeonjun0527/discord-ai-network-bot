package com.discordassistant.central.licensing

import com.discordassistant.central.global.error.PreconditionFailedException
import com.discordassistant.central.licensing.adapter.inbound.web.LicenseController
import com.discordassistant.central.licensing.adapter.inbound.web.LicenseMeRequest
import com.discordassistant.central.licensing.adapter.inbound.web.PaddleWebhookController
import com.discordassistant.central.licensing.application.BillingService
import com.discordassistant.central.licensing.application.EventClaimService
import com.discordassistant.central.licensing.application.LicenseService
import com.discordassistant.central.licensing.application.PaddleSignatureVerifier
import com.discordassistant.central.licensing.application.WebhookOutcome
import com.discordassistant.central.licensing.application.port.CheckoutPort
import com.discordassistant.central.provider.application.TokenService
import com.discordassistant.central.relay.OwnerBinding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class LicenseControllerContractTest {
    private val tokens = mock(TokenService::class.java)
    private val licenses = mock(LicenseService::class.java)
    private val events = mock(EventClaimService::class.java)

    @Test
    fun `license lookup missing durable token exposes common error metadata`() {
        val controller = controller(checkout = CheckoutPort { "https://checkout.example" })

        val ex =
            assertThrows(PreconditionFailedException::class.java) {
                controller.me(LicenseMeRequest("one-shot-token"))
            }

        assertEquals(401, ex.httpStatus)
        assertEquals("UNAUTHORIZED", ex.errorCode)
        assertEquals("durable_token_present", ex.failedCondition)
        assertEquals("VIEW_LICENSE_ENTITLEMENT", ex.blockedAction)
        verifyNoInteractions(licenses, events)
    }

    @Test
    fun `license event claim invalid durable token exposes blocked action`() {
        val controller = controller(checkout = CheckoutPort { "https://checkout.example" })

        val ex =
            assertThrows(PreconditionFailedException::class.java) {
                controller.eventClaim(LicenseMeRequest("dv1.invalid.token"))
            }

        assertEquals(401, ex.httpStatus)
        assertEquals("UNAUTHORIZED", ex.errorCode)
        assertEquals("durable_token_valid", ex.failedCondition)
        assertEquals("CLAIM_LAUNCH_EVENT", ex.blockedAction)
        verifyNoInteractions(licenses, events)
    }

    @Test
    fun `disabled checkout exposes service unavailable precondition metadata`() {
        `when`(tokens.verify("dv1.valid.token")).thenReturn(OwnerBinding(providerId = 42L, guildId = 100L))
        val controller = controller(checkout = CheckoutPort { null })

        val ex =
            assertThrows(PreconditionFailedException::class.java) {
                controller.checkout(LicenseMeRequest("dv1.valid.token"))
            }

        assertEquals(503, ex.httpStatus)
        assertEquals("SERVICE_UNAVAILABLE", ex.errorCode)
        assertEquals("license_checkout_enabled", ex.failedCondition)
        assertEquals("CREATE_LICENSE_CHECKOUT", ex.blockedAction)
        verifyNoInteractions(licenses, events)
    }

    private fun controller(checkout: CheckoutPort): LicenseController = LicenseController(tokens, licenses, checkout, events)
}

class PaddleWebhookControllerContractTest {
    @Test
    fun `invalid Paddle signature exposes common error metadata`() {
        val billing = mock(BillingService::class.java)
        val controller = PaddleWebhookController(PaddleSignatureVerifier("", 300), billing)

        val ex =
            assertThrows(PreconditionFailedException::class.java) {
                controller.paddle("""{"event_id":"evt_1"}""", signature = null)
            }

        assertEquals(401, ex.httpStatus)
        assertEquals("UNAUTHORIZED", ex.errorCode)
        assertEquals("paddle_signature_valid", ex.failedCondition)
        assertEquals("PROCESS_PADDLE_WEBHOOK", ex.blockedAction)
        verifyNoInteractions(billing)
    }

    @Test
    fun `valid Paddle webhook returns typed response DTO`() {
        val verifier = mock(PaddleSignatureVerifier::class.java)
        val billing = mock(BillingService::class.java)
        val raw = """{"event_id":"evt_1"}"""
        `when`(verifier.verify(raw, "sig")).thenReturn(true)
        `when`(billing.handle(raw)).thenReturn(WebhookOutcome.OK)
        val controller = PaddleWebhookController(verifier, billing)

        val response = controller.paddle(raw, "sig")

        assertEquals("OK", response.outcome)
    }
}
