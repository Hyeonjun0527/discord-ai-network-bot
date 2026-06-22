package com.discordassistant.central.participation.application.model

import com.discordassistant.central.participation.application.port.out.ShadowModelRegistryPort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 모델 승인 서비스 테스트(NEXA-P19-T020). 승인 권한·이중 확인·audit·rollback target·자동 배포 금지를 검증한다.
 */
class ModelApprovalServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC)

    private fun fixture(): Triple<ModelApprovalService, InMemoryRegistryStore, RecordingAudit> {
        val store = InMemoryRegistryStore()
        val registry = ShadowModelRegistry(store, clock)
        val audit = RecordingAudit()
        // SHADOW 상태 후보 준비(승인 직전 단계).
        registry.register("m-1", "sha-1", "v1", 1, "cal-1")
        registry.transition("m-1", ModelStatus.SHADOW)
        return Triple(ModelApprovalService(registry, audit, clock), store, audit)
    }

    private fun proposal(token: String = "confirm-xyz") =
        ModelApprovalProposal(
            modelId = "m-1",
            artifactSha256 = "sha-1",
            metrics = mapOf("balanced_accuracy" to 0.8),
            limits = mapOf("max_speak_share" to 0.3),
            confirmationToken = token,
        )

    @Test
    fun `acceptance — 이중 확인 토큰이 일치하면 승인되고 LIVE 자격을 얻는다`() {
        val (service, _, _) = fixture()
        val result =
            service.approve(
                proposal = proposal(),
                approverId = "admin-1",
                confirmationToken = "confirm-xyz",
                currentLiveModelId = "m-live",
            )
        assertThat(result.newStatus).isEqualTo(ModelStatus.APPROVED)
        assertThat(result.decidedBy).isEqualTo("admin-1")
    }

    @Test
    fun `acceptance — 이중 확인 토큰 불일치면 승인이 거부된다(오승인 방지)`() {
        val (service, store, audit) = fixture()
        assertThatThrownBy {
            service.approve(
                proposal = proposal(),
                approverId = "admin-1",
                confirmationToken = "wrong-token",
                currentLiveModelId = null,
            )
        }.isInstanceOf(ModelApprovalException::class.java)
            .hasMessageContaining("이중 확인")
        // 상태가 바뀌지 않았다(여전히 SHADOW).
        assertThat(store.find("m-1")!!.status).isEqualTo(ModelStatus.SHADOW)
        // 실패도 audit 에 남는다.
        assertThat(audit.entries.last().action).isEqualTo(ApprovalAction.APPROVE_REJECTED_DOUBLE_CONFIRM)
    }

    @Test
    fun `acceptance — 승인은 자동 배포가 아니다(autoDeployed=false)`() {
        val (service, _, _) = fixture()
        val result =
            service.approve(proposal(), "admin-1", "confirm-xyz", currentLiveModelId = "m-live")
        assertThat(result.autoDeployed).isFalse()
    }

    @Test
    fun `acceptance — 승인 결과에 rollback target(현재 LIVE 모델)이 있다`() {
        val (service, _, _) = fixture()
        val result =
            service.approve(proposal(), "admin-1", "confirm-xyz", currentLiveModelId = "m-live")
        assertThat(result.rollbackTargetModelId).isEqualTo("m-live")
    }

    @Test
    fun `acceptance — 모든 승인이 audit 에 기록된다(누가·무엇을)`() {
        val (service, _, audit) = fixture()
        service.approve(proposal(), "admin-1", "confirm-xyz", currentLiveModelId = "m-live")
        val entry = audit.entries.last()
        assertThat(entry.action).isEqualTo(ApprovalAction.APPROVED)
        assertThat(entry.approverId).isEqualTo("admin-1")
        assertThat(entry.modelId).isEqualTo("m-1")
        assertThat(entry.rollbackTargetModelId).isEqualTo("m-live")
    }

    @Test
    fun `거절은 사유와 함께 audit 에 기록되고 REJECTED 가 된다`() {
        val (service, store, audit) = fixture()
        val result = service.reject("m-1", "admin-1", reason = "calibration 미달")
        assertThat(result.newStatus).isEqualTo(ModelStatus.REJECTED)
        assertThat(store.find("m-1")!!.status).isEqualTo(ModelStatus.REJECTED)
        assertThat(audit.entries.last().action).isEqualTo(ApprovalAction.REJECTED)
        assertThat(audit.entries.last().reason).isEqualTo("calibration 미달")
    }

    @Test
    fun `빈 approverId 는 거부된다(승인 권한 주체 필요)`() {
        val (service, _, _) = fixture()
        assertThatThrownBy {
            service.approve(proposal(), "", "confirm-xyz", currentLiveModelId = null)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private class RecordingAudit : ModelApprovalAuditPort {
        val entries = mutableListOf<ApprovalAuditEntry>()

        override fun record(entry: ApprovalAuditEntry) {
            entries.add(entry)
        }
    }

    private class InMemoryRegistryStore : ShadowModelRegistryPort {
        private val data = linkedMapOf<String, ShadowModelCandidate>()

        override fun save(candidate: ShadowModelCandidate) {
            data[candidate.modelId] = candidate
        }

        override fun find(modelId: String): ShadowModelCandidate? = data[modelId]

        override fun listAll(): List<ShadowModelCandidate> = data.values.sortedByDescending { it.registeredAt }
    }
}
