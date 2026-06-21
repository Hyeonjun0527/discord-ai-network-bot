package com.discordassistant.central.participation.contract

import com.discordassistant.central.participation.application.port.out.PolicyDecisionResponse
import com.discordassistant.central.participation.domain.model.action.SocialAct
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.ActionTargetDistribution
import com.discordassistant.central.participation.domain.model.decision.BurstProfile
import com.discordassistant.central.participation.domain.model.decision.DelayBucket
import com.discordassistant.central.participation.domain.model.decision.DelayDistribution
import com.discordassistant.central.participation.domain.model.decision.TargetCandidate
import com.discordassistant.central.participation.domain.model.decision.TargetKind
import com.discordassistant.central.participation.domain.model.decision.TargetRef
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration

/**
 * 정책 계약 golden test(NEXA-P08-T024). Kotlin 역직렬화와 Python loader 가 **같은 JSON fixture**
 * (`contracts/policy/fixtures/policy-decision-response.golden.json`)를 읽도록 보장한다 — 와이어 형태(camelCase·
 * 안정 코드)를 한곳에 고정해 양측 drift 를 잡는다(Python 측은 scripts/validate-nexa-policy-fixtures.py).
 *
 * **acceptance(T024) — schema version 변경 시 golden diff 가 명시적으로 실패한다**:
 * fixture 의 [SCHEMA_VERSION] 이 코드가 기대하는 버전과 다르면 [loadGolden] 이 즉시 실패한다(조용한 호환 금지).
 * 또한 golden fixture 를 [PolicyDecisionResponse] 로 파싱한 값이 기대 분포와 정확히 일치하는지 검증한다.
 */
class PolicyContractGoldenTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `golden fixture 를 PolicyDecisionResponse 로 파싱하면 기대 분포와 일치한다`() {
        val response = loadGolden()

        assertThat(response.actionWeights)
            .containsEntry(SocialActionKind.IGNORE, 0.2)
            .containsEntry(SocialActionKind.SPEAK, 0.6)
        assertThat(response.mostLikelyAction).isEqualTo(SocialActionKind.SPEAK)
        assertThat(response.targetDistribution.noneProbability).isEqualTo(0.3)
        assertThat(response.targetDistribution.mostLikely?.target)
            .isEqualTo(TargetRef(TargetKind.MESSAGE, "m-1"))
        assertThat(response.delayDistribution.mostLikelyBucket).isEqualTo(DelayBucket.IMMEDIATE)
        assertThat(response.socialActWeights).containsEntry(SocialAct.ASK, 0.6)
        assertThat(response.burstProfile.mostLikelyFragmentCount).isEqualTo(1)
        assertThat(response.uncertainty).isEqualTo(0.3)
        assertThat(response.modelVersion).isEqualTo("rules-1")
    }

    @Test
    fun `schema version 이 기대와 다르면 golden 로드가 명시적으로 실패한다`() {
        val raw = mapper.readTree(goldenFile().readText()) as com.fasterxml.jackson.databind.node.ObjectNode
        raw.put("schemaVersion", SCHEMA_VERSION + 1)
        assertThatThrownBy { parseResponse(raw) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("schema version")
    }

    private fun loadGolden(): PolicyDecisionResponse {
        val raw = mapper.readTree(goldenFile().readText()) as com.fasterxml.jackson.databind.node.ObjectNode
        return parseResponse(raw)
    }

    /** golden JSON 노드를 계약 타입으로 매핑한다. schema version 불일치는 즉시 실패(acceptance). */
    private fun parseResponse(node: com.fasterxml.jackson.databind.node.ObjectNode): PolicyDecisionResponse {
        val schemaVersion = node.get("schemaVersion").asInt()
        check(schemaVersion == SCHEMA_VERSION) {
            "정책 응답 fixture schema version 불일치: 기대 $SCHEMA_VERSION, 실제 $schemaVersion (golden diff)"
        }

        val actionWeights =
            node.get("actionWeights").fields().asSequence().associate { (k, v) ->
                SocialActionKind.entries.first { it.wireName == k } to v.asDouble()
            }

        val targetNode = node.get("targetDistribution")
        val candidates =
            targetNode.get("candidates").map { c ->
                val t = c.get("target")
                TargetCandidate(
                    target = TargetRef(TargetKind.entries.first { it.wireName == t.get("kind").asText() }, t.get("id").asText()),
                    probability = c.get("probability").asDouble(),
                )
            }
        val targetDistribution =
            ActionTargetDistribution(
                candidates = candidates,
                noneProbability = targetNode.get("noneProbability").asDouble(),
                resolverVersion = targetNode.get("resolverVersion").asText(),
            )

        val delayWeights =
            node.get("delayDistribution").get("weights").fields().asSequence().associate { (k, v) ->
                DelayBucket.valueOf(k) to v.asDouble()
            }

        val socialActWeights =
            node.get("socialActWeights").fields().asSequence().associate { (k, v) ->
                SocialAct.fromWireName(k) to v.asDouble()
            }

        val burstNode = node.get("burstProfile")
        val burstProfile =
            BurstProfile(
                fragmentCountWeights =
                    burstNode
                        .get("fragmentCountWeights")
                        .fields()
                        .asSequence()
                        .associate { (k, v) -> k.toInt() to v.asDouble() },
                maxFragmentLength = burstNode.get("maxFragmentLength").asInt(),
                gapLowerBound = Duration.ofMillis((burstNode.get("gapLowerBoundSeconds").asDouble() * 1000).toLong()),
                gapUpperBound = Duration.ofMillis((burstNode.get("gapUpperBoundSeconds").asDouble() * 1000).toLong()),
                reactionOnlyProbability = burstNode.get("reactionOnlyProbability").asDouble(),
            )

        return PolicyDecisionResponse(
            actionWeights = actionWeights,
            targetDistribution = targetDistribution,
            delayDistribution = DelayDistribution(delayWeights),
            socialActWeights = socialActWeights,
            burstProfile = burstProfile,
            uncertainty = node.get("uncertainty").asDouble(),
            modelVersion = node.get("modelVersion").asText(),
        )
    }

    private fun goldenFile(): File = File("../contracts/policy/fixtures/policy-decision-response.golden.json")

    companion object {
        /** 코드가 기대하는 정책 응답 계약 schema version(PolicyDecisionRequest.schemaVersion 과 동일 계열). */
        const val SCHEMA_VERSION = 1
    }
}
