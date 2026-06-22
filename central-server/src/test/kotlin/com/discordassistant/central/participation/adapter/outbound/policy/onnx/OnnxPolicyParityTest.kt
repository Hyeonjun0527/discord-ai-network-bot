package com.discordassistant.central.participation.adapter.outbound.policy.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * Python-JVM golden parity 테스트(NEXA-P11-T019). ml/social-policy 가 onnxruntime(Python)으로 봉인한 golden head
 * 출력과, central JVM onnxruntime 추론이 **같은 ONNX 모델·같은 입력** 에서 허용오차 내 일치함을 증명한다 — 즉 Python
 * 추론과 JVM 추론이 같은 정책 결정을 낸다(언어 경계 parity).
 *
 * **acceptance(T019) — 수치 허용오차와 category ordering 이 명시된다**:
 * - golden 의 `tolerance`(절대오차)를 그대로 적용한다(Python 측 PARITY_TOLERANCE 와 동일 SSOT).
 * - golden 의 `categoryOrder` 가 decoder([OnnxPolicyResponseDecoder])의 head 인덱스 순서와 일치하는지 확인한다 —
 *   순서가 어긋나면 분포가 뒤섞이므로 명시적으로 검증한다.
 *
 * fixture 는 contracts/policy/fixtures/parity/(작은 ONNX ~8KB + golden JSON). 운영 모델 artifact 아님.
 */
class OnnxPolicyParityTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `JVM onnxruntime 이 Python golden head 출력과 허용오차 내 일치한다`() {
        val golden = loadGolden()
        val tolerance = golden.get("tolerance").asDouble()
        val inputs = parseMatrix(golden.get("inputs"))
        val expected = golden.get("heads")

        val env = OrtEnvironment.getEnvironment()
        env.createSession(onnxFile().absolutePath, OrtSession.SessionOptions()).use { session ->
            val flat = FloatArray(inputs.size * inputs[0].size)
            inputs.forEachIndexed { r, row -> row.forEachIndexed { c, v -> flat[r * row.size + c] = v } }
            val shape = longArrayOf(inputs.size.toLong(), inputs[0].size.toLong())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape).use { input ->
                session.run(mapOf("features" to input)).use { results ->
                    for (head in listOf("action", "target", "delay", "burst", "act")) {
                        @Suppress("UNCHECKED_CAST")
                        val got = results.get(head).get().value as Array<FloatArray>
                        val exp = parseMatrix(expected.get(head))
                        var maxAbs = 0.0
                        for (i in got.indices) {
                            for (j in got[i].indices) {
                                maxAbs = maxOf(maxAbs, abs(got[i][j] - exp[i][j]).toDouble())
                            }
                        }
                        assertThat(maxAbs)
                            .withFailMessage("head %s parity 오차 %s > tol %s", head, maxAbs, tolerance)
                            .isLessThanOrEqualTo(tolerance)
                    }
                }
            }
        }
    }

    @Test
    fun `golden category 순서가 decoder head 인덱스 순서와 일치한다`() {
        val order = loadGolden().get("categoryOrder")
        assertThat(order.get("action").map { it.asText() })
            .containsExactly("ignore", "wait", "react", "speak", "cancel")
        assertThat(order.get("delay").map { it.asText() })
            .containsExactly("0-2s", "2-10s", "10-60s", "60s+", "never")
        assertThat(order.get("act").map { it.asText() })
            .containsExactly("acknowledge", "agree", "ask", "tease", "self_disclose", "unknown")
        assertThat(order.get("burst").map { it.asText() })
            .containsExactly("none", "single", "multi")
    }

    private fun loadGolden(): JsonNode = mapper.readTree(goldenFile().readText())

    private fun parseMatrix(node: JsonNode): Array<FloatArray> =
        Array(node.size()) { i ->
            val row = node.get(i)
            FloatArray(row.size()) { j -> row.get(j).asDouble().toFloat() }
        }

    private fun goldenFile(): File = File("../contracts/policy/fixtures/parity/policy-v1-parity.golden.json")

    private fun onnxFile(): File = File("../contracts/policy/fixtures/parity/policy-v1-fixture.onnx")
}
