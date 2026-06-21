package com.discordassistant.central.participation.application.model

import com.discordassistant.central.participation.application.port.out.ShadowModelRegistryPort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * shadow 모델 레지스트리 서비스 테스트(NEXA-P11-T020). 등록·단계 전이·LIVE 선택 불변식을 검증한다.
 *
 * **핵심 acceptance — 승인되지 않은 artifact 는 LIVE 로 선택할 수 없다**: REGISTERED/SHADOW/REJECTED 후보의 LIVE
 * 선택은 모두 거부되고, APPROVED 만 선택된다. 또한 REGISTERED→APPROVED 직승격(자동 LIVE)이 차단된다.
 */
class ShadowModelRegistryTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC)

    private fun registry(): Pair<ShadowModelRegistry, InMemoryRegistryStore> {
        val store = InMemoryRegistryStore()
        return ShadowModelRegistry(store, clock) to store
    }

    private fun ShadowModelRegistry.registerOne(id: String = "m-1") =
        register(
            modelId = id,
            artifactSha256 = "abc123",
            modelVersion = "v1",
            featureSchemaVersion = 1,
            calibrationVersion = "cal-1",
        )

    @Test
    fun `등록은 REGISTERED 상태로 시작한다(자동 LIVE 아님)`() {
        val (reg, _) = registry()
        val c = reg.registerOne()
        assertThat(c.status).isEqualTo(ModelStatus.REGISTERED)
        assertThat(c.isSelectableForLive).isFalse()
    }

    @Test
    fun `중복 modelId 등록은 거부된다`() {
        val (reg, _) = registry()
        reg.registerOne("dup")
        assertThatThrownBy { reg.registerOne("dup") }
            .isInstanceOf(ShadowModelRegistryException::class.java)
            .hasMessageContaining("이미 등록된")
    }

    @Test
    fun `acceptance — REGISTERED 에서 곧장 APPROVED 로 직승격할 수 없다(자동 LIVE 금지)`() {
        val (reg, _) = registry()
        reg.registerOne()
        assertThatThrownBy { reg.transition("m-1", ModelStatus.APPROVED) }
            .isInstanceOf(ShadowModelRegistryException::class.java)
            .hasMessageContaining("허용되지 않는 상태 전이")
    }

    @Test
    fun `acceptance — 미승인 후보는 LIVE 로 선택할 수 없다`() {
        val (reg, _) = registry()
        reg.registerOne()
        // REGISTERED.
        assertThatThrownBy { reg.selectForLive("m-1") }
            .isInstanceOf(ShadowModelRegistryException::class.java)
            .hasMessageContaining("승인되지 않은")
        // SHADOW 도 불가.
        reg.transition("m-1", ModelStatus.SHADOW)
        assertThatThrownBy { reg.selectForLive("m-1") }
            .isInstanceOf(ShadowModelRegistryException::class.java)
    }

    @Test
    fun `acceptance — APPROVED 후보만 LIVE 로 선택된다`() {
        val (reg, _) = registry()
        reg.registerOne()
        reg.transition("m-1", ModelStatus.SHADOW)
        reg.transition("m-1", ModelStatus.APPROVED)
        val live = reg.selectForLive("m-1")
        assertThat(live.status).isEqualTo(ModelStatus.APPROVED)
    }

    @Test
    fun `REJECTED 는 종착 상태라 더 전이할 수 없다`() {
        val (reg, _) = registry()
        reg.registerOne()
        reg.transition("m-1", ModelStatus.REJECTED)
        assertThatThrownBy { reg.transition("m-1", ModelStatus.SHADOW) }
            .isInstanceOf(ShadowModelRegistryException::class.java)
    }

    @Test
    fun `APPROVED 후보도 거부로 회수되면 LIVE 자격을 잃는다(안전 방향)`() {
        val (reg, _) = registry()
        reg.registerOne()
        reg.transition("m-1", ModelStatus.SHADOW)
        reg.transition("m-1", ModelStatus.APPROVED)
        reg.transition("m-1", ModelStatus.REJECTED)
        assertThatThrownBy { reg.selectForLive("m-1") }
            .isInstanceOf(ShadowModelRegistryException::class.java)
    }

    @Test
    fun `shadowCandidates 는 SHADOW 와 APPROVED 만 포함한다`() {
        val (reg, _) = registry()
        reg.registerOne("a")
        reg.registerOne("b")
        reg.registerOne("c")
        reg.transition("b", ModelStatus.SHADOW)
        reg.transition("c", ModelStatus.SHADOW)
        reg.transition("c", ModelStatus.APPROVED)
        assertThat(reg.shadowCandidates().map { it.modelId }).containsExactlyInAnyOrder("b", "c")
    }

    /** 결정론 in-memory 레지스트리 저장(테스트용). */
    private class InMemoryRegistryStore : ShadowModelRegistryPort {
        private val data = linkedMapOf<String, ShadowModelCandidate>()

        override fun save(candidate: ShadowModelCandidate) {
            data[candidate.modelId] = candidate
        }

        override fun find(modelId: String): ShadowModelCandidate? = data[modelId]

        override fun listAll(): List<ShadowModelCandidate> = data.values.sortedByDescending { it.registeredAt }
    }
}
