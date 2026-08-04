package com.discordassistant.central.participation.application.port.out

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NiaJudgeOutputContractTest {
    private val schema = jacksonObjectMapper().readTree(NiaJudgeOutputContract.JSON_SCHEMA)

    @Test
    fun `strict schema covers every locally accepted judge field`() {
        assertObjectFields(
            schema,
            setOf(
                "schema",
                "action",
                "reason",
                "reasonCode",
                "evidenceRefs",
                "reactionCode",
                "speechIntent",
                "toneAxes",
                "confidence",
                "riskFlags",
                "reevaluateAfterMs",
                "beliefUpdates",
            ),
        )
        assertObjectFields(
            property("speechIntent"),
            setOf(
                "intentSummary",
                "sceneDirection",
                "styleMode",
                "actHint",
                "bubbleCount",
                "maxBubbleChars",
                "interactionReading",
                "informationDepth",
                "continuityRefs",
                "responseTargetRef",
                "responseObligation",
                "groundingNeed",
                "deliveryMode",
            ),
        )
        assertObjectFields(
            property("toneAxes"),
            setOf("warmth", "playfulness", "directness", "emotionalIntensity"),
        )
        assertObjectFields(
            property("beliefUpdates"),
            setOf("commonGround", "intentHypotheses", "commitments"),
        )
        assertObjectFields(
            property("beliefUpdates", "commonGround").path("items"),
            setOf("code", "confidence", "evidenceRefs", "status"),
        )
        assertObjectFields(
            property("beliefUpdates", "intentHypotheses").path("items"),
            setOf("participantRef", "code", "probability", "evidenceRefs", "status"),
        )
        assertObjectFields(
            property("beliefUpdates", "commitments").path("items"),
            setOf("commitmentRef", "topic", "socialAct", "evidenceRefs", "confidence", "status"),
        )
        assertThat(property("action").path("enum").map(JsonNode::asText))
            .containsExactly("IGNORE", "WAIT", "REACT", "SPEAK", "CANCEL")
        assertThat(property("speechIntent", "styleMode").path("enum").map(JsonNode::asText))
            .containsExactly("REACTION", "ALIGNMENT", "PLAY", "FOLLOW_UP", "SPECULATION", "CARE", "COORDINATION")
        assertThat(property("schema").path("enum").single().asText())
            .isEqualTo(NiaJudgeLlmRequest.OUTPUT_SCHEMA)
    }

    @Test
    fun `strict schema requires every object property and uses supported keywords only`() {
        assertStrictSupportedSchema(schema)
    }

    @Test
    fun `semantically optional fields are explicitly nullable`() {
        assertNullable(
            schema,
            "reasonCode",
            "evidenceRefs",
            "reactionCode",
            "speechIntent",
            "toneAxes",
            "riskFlags",
            "reevaluateAfterMs",
            "beliefUpdates",
        )
        assertNullable(
            property("speechIntent"),
            "actHint",
            "bubbleCount",
            "maxBubbleChars",
            "interactionReading",
            "informationDepth",
            "continuityRefs",
        )
        assertNullable(
            property("toneAxes"),
            "warmth",
            "playfulness",
            "directness",
            "emotionalIntensity",
        )
        assertNullable(
            property("beliefUpdates"),
            "commonGround",
            "intentHypotheses",
            "commitments",
        )
        assertNullable(property("beliefUpdates", "commonGround").path("items"), "status")
        assertNullable(property("beliefUpdates", "intentHypotheses").path("items"), "status")
    }

    private fun assertObjectFields(
        objectSchema: JsonNode,
        expected: Set<String>,
    ) {
        assertThat(typeNames(objectSchema)).contains("object")
        val properties =
            objectSchema
                .path("properties")
                .fieldNames()
                .asSequence()
                .toSet()
        val required = objectSchema.path("required").map(JsonNode::asText).toSet()
        assertThat(properties).isEqualTo(expected)
        assertThat(required).isEqualTo(expected)
        assertThat(objectSchema.path("additionalProperties").asBoolean()).isFalse()
    }

    private fun assertStrictSupportedSchema(node: JsonNode) {
        assertThat(node.fieldNames().asSequence().toSet())
            .isSubsetOf("type", "properties", "required", "additionalProperties", "items", "enum")
        if ("object" in typeNames(node)) {
            val properties = node.path("properties")
            val propertyNames = properties.fieldNames().asSequence().toSet()
            assertThat(node.path("required").map(JsonNode::asText).toSet()).isEqualTo(propertyNames)
            assertThat(node.path("additionalProperties").isBoolean).isTrue()
            assertThat(node.path("additionalProperties").asBoolean()).isFalse()
            properties.forEach(::assertStrictSupportedSchema)
        }
        node.get("items")?.let(::assertStrictSupportedSchema)
    }

    private fun assertNullable(
        objectSchema: JsonNode,
        vararg fields: String,
    ) {
        fields.forEach { field ->
            assertThat(typeNames(objectSchema.path("properties").path(field)))
                .describedAs("$field must accept explicit null")
                .contains("null")
        }
    }

    private fun property(
        first: String,
        second: String? = null,
    ): JsonNode {
        val firstProperty = schema.path("properties").path(first)
        return if (second == null) firstProperty else firstProperty.path("properties").path(second)
    }

    private fun typeNames(node: JsonNode): Set<String> {
        val type = node.path("type")
        return if (type.isArray) type.map(JsonNode::asText).toSet() else setOf(type.asText())
    }
}
