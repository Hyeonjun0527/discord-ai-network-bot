package com.discordassistant.central.web

import com.discordassistant.central.global.adapter.inbound.web.GlobalExceptionHandler
import com.discordassistant.central.global.error.ApiErrorCodes
import com.discordassistant.central.global.error.ApiErrorDetailKeys
import com.discordassistant.central.global.error.ApiErrorDetailsSchemas
import com.discordassistant.central.global.security.AiNetworkApiSecurityFilter
import com.discordassistant.central.global.security.RequestIdFilter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

class ApiErrorEnvelopeContractTest {
    private val objectMapper = jacksonObjectMapper()

    @AfterEach
    fun clearMdc() = MDC.remove(RequestIdFilter.MDC_KEY)

    @RestController
    class ContractThrowingController {
        @GetMapping("/contract/bad-request")
        fun badRequest() {
            require(false) { "contract bad request" }
        }
    }

    private val adviceMvc =
        MockMvcBuilders
            .standaloneSetup(ContractThrowingController())
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

    @Test
    fun `filter and advice errors share the same JSON envelope shape`() {
        MDC.put(RequestIdFilter.MDC_KEY, "filter-contract-1")
        val filterResponse = MockHttpServletResponse()
        val filter =
            AiNetworkApiSecurityFilter(
                adminToken = "",
                adminUserIdsRaw = "",
                objectMapper = objectMapper,
            )
        filter.doFilter(
            MockHttpServletRequest("GET", "/api/dashboard/guilds"),
            filterResponse,
            FilterChain { _, _ -> error("admin request should be blocked by filter") },
        )

        assertEnvelope(
            body = filterResponse.contentAsString,
            expectedStatus = 403,
            expectedCode = ApiErrorCodes.DASHBOARD_ADMIN_REQUIRED,
            expectedRequestId = "filter-contract-1",
        )
        assertThat(objectMapper.readTree(filterResponse.contentAsString).path("error").has("currentState")).isFalse()

        MDC.put(RequestIdFilter.MDC_KEY, "advice-contract-1")
        val adviceBody =
            adviceMvc
                .perform(get("/contract/bad-request"))
                .andExpect(status().isBadRequest)
                .andReturn()
                .response
                .contentAsString

        assertEnvelope(
            body = adviceBody,
            expectedStatus = 400,
            expectedCode = ApiErrorCodes.INVALID_REQUEST,
            expectedRequestId = "advice-contract-1",
        )
        assertThat(objectMapper.readTree(adviceBody).path("error").has("details")).isFalse()
    }

    @Test
    fun `client visible error codes and details schemas are registered`() {
        assertThat(ApiErrorCodes.registeredCodes)
            .contains(
                ApiErrorCodes.INVALID_REQUEST,
                ApiErrorCodes.NOT_FOUND,
                ApiErrorCodes.DASHBOARD_ADMIN_REQUIRED,
                ApiErrorCodes.INVALID_SERVER_STATE,
                ApiErrorCodes.INTERNAL_SERVER_ERROR,
                ApiErrorCodes.SERVICE_UNAVAILABLE,
            )

        val preconditionSchema = ApiErrorDetailsSchemas.schemaFor(ApiErrorCodes.PRECONDITION_FAILED)
        assertThat(preconditionSchema).isNotNull
        assertThat(preconditionSchema!!.optionalKeys)
            .containsExactlyInAnyOrder(ApiErrorDetailKeys.ALLOWED_VALUES, ApiErrorDetailKeys.ACTUAL_VALUE)
        assertThat(
            preconditionSchema.unexpectedKeys(
                mapOf(
                    ApiErrorDetailKeys.ALLOWED_VALUES to listOf("image/jpeg", "image/png"),
                    ApiErrorDetailKeys.ACTUAL_VALUE to "image/heic",
                ),
            ),
        ).isEmpty()
        assertThat(preconditionSchema.missingRequiredKeys(emptyMap())).isEmpty()
    }

    private fun assertEnvelope(
        body: String,
        expectedStatus: Int,
        expectedCode: String,
        expectedRequestId: String,
    ) {
        val root: JsonNode = objectMapper.readTree(body)
        assertThat(root.path("success").asBoolean()).isFalse()
        assertThat(root.path("status").asInt()).isEqualTo(expectedStatus)
        assertThat(root.path("requestId").asText()).isEqualTo(expectedRequestId)
        assertThat(root.path("error").path("code").asText()).isEqualTo(expectedCode)
        assertThat(root.path("error").path("message").isTextual).isTrue()
        assertThat(ApiErrorCodes.isRegistered(root.path("error").path("code").asText())).isTrue()
    }
}
