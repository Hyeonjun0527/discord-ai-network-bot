package com.discordassistant.central.req

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * 요구사항 추적성 검사기(차수 18). Jira/Xray 없이 레포 안에서 자체 추적성을 강제한다.
 * requirements.yaml(원장) ↔ feature 의 @REQ-* 태그 ↔ 테스트 경로의 정합을 검증한다.
 */
class RequirementsTraceabilityTest {
    private data class Req(
        val id: String,
        val priority: String,
        val paths: List<String>,
    )

    private fun loadReqs(): List<Req> {
        val text =
            javaClass.getResourceAsStream("/requirements.yaml")?.bufferedReader()?.readText()
                ?: error("requirements.yaml 을 클래스패스에서 찾을 수 없습니다.")

        @Suppress("UNCHECKED_CAST")
        val root = Yaml().load<Map<String, Any?>>(text)

        @Suppress("UNCHECKED_CAST")
        val list = (root["requirements"] as? List<Map<String, Any?>>).orEmpty()
        return list.map { m ->
            @Suppress("UNCHECKED_CAST")
            val tests = (m["tests"] as? List<Map<String, Any?>>).orEmpty()
            Req(
                id = m["id"].toString(),
                priority = m["priority"]?.toString() ?: "P2",
                paths = tests.mapNotNull { it["path"]?.toString() },
            )
        }
    }

    private val featureDir = File("src/test/resources/features")
    private val reqTagRegex = Regex("@(REQ-[A-Z0-9-]+)")

    private fun featureTags(): Set<String> {
        if (!featureDir.exists()) return emptySet()
        return featureDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "feature" }
            .flatMap { f -> reqTagRegex.findAll(f.readText()).map { it.groupValues[1] } }
            .toSet()
    }

    @Test
    fun `feature 의 모든 @REQ 태그는 requirements yaml 에 선언돼 있다`() {
        val declared = loadReqs().map { it.id }.toSet()
        val orphans = featureTags() - declared
        assertTrue(orphans.isEmpty(), "원장에 없는 @REQ 태그(삭제됐거나 오타?): $orphans")
    }

    @Test
    fun `P0 요구사항은 최소 한 개의 feature 시나리오로 추적된다`() {
        val used = featureTags()
        val untestedP0 = loadReqs().filter { it.priority == "P0" }.map { it.id }.filter { it !in used }
        assertTrue(untestedP0.isEmpty(), "추적 시나리오가 없는 P0 요구사항: $untestedP0")
    }

    @Test
    fun `requirements yaml 의 모든 테스트 경로는 실제로 존재한다`() {
        val missing = loadReqs().flatMap { it.paths }.distinct().filter { !File(it).exists() }
        assertTrue(missing.isEmpty(), "존재하지 않는 테스트 경로: $missing")
    }
}
