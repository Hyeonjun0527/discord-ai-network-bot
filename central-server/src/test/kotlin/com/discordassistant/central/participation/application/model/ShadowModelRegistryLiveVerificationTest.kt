package com.discordassistant.central.participation.application.model

import com.discordassistant.central.participation.application.port.out.ShadowModelRegistryPort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * NEXA-P17-T020 enforcement(보안 후속): 실제 LIVE 승격 경로([ShadowModelRegistry.selectForLiveVerified])가
 * [ArtifactIntegrityVerifier] 로 서명·hash 무결성을 강제하는지 **실제 서비스 호출로** 검증한다.
 *
 * 기존 [ShadowModelRegistryTest] 는 status(APPROVED)만 확인했고 서명 검증이 우회됐다(security-reviewer H2 갭).
 * 이 테스트는 등록→shadow→approved 까지 실제 전이를 거친 APPROVED 후보에 대해, **변조/swap/미서명 artifact 가
 * selectForLiveVerified 에서 거부되고, 유효 서명만 통과**함을 ml 측이 생성한 실제 서명으로 증명한다.
 */
class ShadowModelRegistryLiveVerificationTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-22T00:00:00Z"), ZoneOffset.UTC)

    // ml nexa_policy.export.signing 가 봉인한 manifest(ArtifactIntegrityVerifierTest 와 동일 fixture·parity).
    private val modelDigest = "a".repeat(64)
    private val components =
        listOf(
            ComponentDigest("model", modelDigest),
            ComponentDigest("config", "b".repeat(64)),
            ComponentDigest("calibration", "c".repeat(64)),
        )
    private val manifest = ArtifactManifest(modelVersion = "policy-v1", components = components)
    private val actualDigests =
        mapOf("model" to modelDigest, "config" to "b".repeat(64), "calibration" to "c".repeat(64))
    private val key = "shared-key-123".toByteArray(Charsets.UTF_8)

    // ml signing.sign_manifest(manifest, b"shared-key-123") 가 실제 생성한 서명(cross-language parity).
    private val mlSignature = "32cab2ae20d834afa8c41926b9d3a7fca99df6326415beff47074fbc3d7737a2"

    /** 등록→shadow→approved 까지 실제 전이를 거친 APPROVED 후보를 만든다(등록된 artifactSha256 = manifest model digest). */
    private fun approvedRegistry(): ShadowModelRegistry {
        val reg = ShadowModelRegistry(InMemoryRegistryStore(), clock)
        reg.register(
            modelId = "m-1",
            artifactSha256 = modelDigest,
            modelVersion = "policy-v1",
            featureSchemaVersion = 1,
            calibrationVersion = "cal-1",
        )
        reg.transition("m-1", ModelStatus.SHADOW)
        reg.transition("m-1", ModelStatus.APPROVED)
        return reg
    }

    @Test
    fun `acceptance — 유효 서명 APPROVED 후보만 LIVE 로 승격된다`() {
        val reg = approvedRegistry()
        val signed = SignedArtifactManifest(manifest = manifest, signature = mlSignature)
        val live = reg.selectForLiveVerified("m-1", signed, actualDigests, key)
        assertThat(live.status).isEqualTo(ModelStatus.APPROVED)
        assertThat(live.modelId).isEqualTo("m-1")
    }

    @Test
    fun `acceptance — 변조된 artifact hash 는 APPROVED 라도 LIVE 거부`() {
        val reg = approvedRegistry()
        val signed = SignedArtifactManifest(manifest = manifest, signature = mlSignature)
        // 서명은 유효하나 실제 파일 hash 가 manifest 와 다르다(파일 swap/변조).
        val tampered = actualDigests.toMutableMap().apply { this["model"] = "f".repeat(64) }
        assertThatThrownBy { reg.selectForLiveVerified("m-1", signed, tampered, key) }
            .isInstanceOf(ArtifactIntegrityException::class.java)
    }

    @Test
    fun `acceptance — 위조 서명(미서명) artifact 는 LIVE 거부`() {
        val reg = approvedRegistry()
        val forged = SignedArtifactManifest(manifest = manifest, signature = "deadbeef".repeat(8))
        assertThatThrownBy { reg.selectForLiveVerified("m-1", forged, actualDigests, key) }
            .isInstanceOf(ArtifactIntegrityException::class.java)
    }

    @Test
    fun `acceptance — 등록 artifactSha256 과 다른 manifest 를 끼워넣는 swap 거부`() {
        // APPROVED 후보지만 등록된 artifact hash 가 manifest model digest 와 다르다(다른 artifact swap).
        val reg = ShadowModelRegistry(InMemoryRegistryStore(), clock)
        reg.register(
            modelId = "m-2",
            artifactSha256 = "9".repeat(64), // 등록은 다른 hash.
            modelVersion = "policy-v1",
            featureSchemaVersion = 1,
            calibrationVersion = "cal-1",
        )
        reg.transition("m-2", ModelStatus.SHADOW)
        reg.transition("m-2", ModelStatus.APPROVED)
        val signed = SignedArtifactManifest(manifest = manifest, signature = mlSignature)
        assertThatThrownBy { reg.selectForLiveVerified("m-2", signed, actualDigests, key) }
            .isInstanceOf(ArtifactIntegrityException::class.java)
    }

    @Test
    fun `미승인 후보는 서명이 유효해도 LIVE 거부(상태 게이트 우선)`() {
        val reg = ShadowModelRegistry(InMemoryRegistryStore(), clock)
        reg.register(
            modelId = "m-3",
            artifactSha256 = modelDigest,
            modelVersion = "policy-v1",
            featureSchemaVersion = 1,
            calibrationVersion = "cal-1",
        )
        reg.transition("m-3", ModelStatus.SHADOW) // APPROVED 아님.
        val signed = SignedArtifactManifest(manifest = manifest, signature = mlSignature)
        assertThatThrownBy { reg.selectForLiveVerified("m-3", signed, actualDigests, key) }
            .isInstanceOf(ShadowModelRegistryException::class.java)
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
