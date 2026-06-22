package com.discordassistant.central.participation.application.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** NEXA-P17-T020: 모델 artifact 서명·hash 검증 — 변조 artifact 가 ACTIVE 가 되지 않는다(ml 측과 parity). */
class ArtifactIntegrityVerifierTest {
    private val verifier = ArtifactIntegrityVerifier()

    private val components =
        listOf(
            ComponentDigest("model", "a".repeat(64)),
            ComponentDigest("config", "b".repeat(64)),
            ComponentDigest("calibration", "c".repeat(64)),
        )
    private val manifest = ArtifactManifest(modelVersion = "policy-v1", components = components)
    private val actualDigests =
        mapOf("model" to "a".repeat(64), "config" to "b".repeat(64), "calibration" to "c".repeat(64))
    private val key = "shared-key-123".toByteArray(Charsets.UTF_8)

    @Test
    fun `canonical bytes match ml signing python SSOT`() {
        // ml nexa_policy.export.signing 가 만든 정규형과 바이트 동일해야 한다(언어 경계 parity).
        val expected =
            """{"components":[{"name":"calibration","sha256":"${"c".repeat(64)}"},""" +
                """{"name":"config","sha256":"${"b".repeat(64)}"},""" +
                """{"name":"model","sha256":"${"a".repeat(64)}"}],""" +
                """"formatVersion":1,"modelVersion":"policy-v1"}"""
        assertThat(String(manifest.canonicalBytes(), Charsets.UTF_8)).isEqualTo(expected)
    }

    @Test
    fun `accepts signature produced by ml signing python`() {
        // ml signing.sign_manifest(manifest, b"shared-key-123") 가 생성한 실제 서명(cross-language parity).
        val mlSignature = "32cab2ae20d834afa8c41926b9d3a7fca99df6326415beff47074fbc3d7737a2"
        val signed = SignedArtifactManifest(manifest = manifest, signature = mlSignature)
        // 변조 없으면 예외 없이 통과.
        verifier.verify(signed, actualDigests, key)
    }

    @Test
    fun `rejects forged signature`() {
        val signed = SignedArtifactManifest(manifest = manifest, signature = "deadbeef".repeat(8))
        assertThatThrownBy { verifier.verify(signed, actualDigests, key) }
            .isInstanceOf(ArtifactIntegrityException::class.java)
    }

    @Test
    fun `rejects wrong key`() {
        val mlSignature = "32cab2ae20d834afa8c41926b9d3a7fca99df6326415beff47074fbc3d7737a2"
        val signed = SignedArtifactManifest(manifest = manifest, signature = mlSignature)
        assertThatThrownBy { verifier.verify(signed, actualDigests, "wrong-key".toByteArray()) }
            .isInstanceOf(ArtifactIntegrityException::class.java)
    }

    @Test
    fun `rejects tampered component hash`() {
        val mlSignature = "32cab2ae20d834afa8c41926b9d3a7fca99df6326415beff47074fbc3d7737a2"
        val signed = SignedArtifactManifest(manifest = manifest, signature = mlSignature)
        // 서명은 유효하지만 실제 파일 hash 가 manifest 와 다르다(파일 swap).
        val tampered = actualDigests.toMutableMap().apply { this["model"] = "f".repeat(64) }
        assertThatThrownBy { verifier.verify(signed, tampered, key) }
            .isInstanceOf(ArtifactIntegrityException::class.java)
    }

    @Test
    fun `rejects missing component`() {
        val mlSignature = "32cab2ae20d834afa8c41926b9d3a7fca99df6326415beff47074fbc3d7737a2"
        val signed = SignedArtifactManifest(manifest = manifest, signature = mlSignature)
        val partial = mapOf("model" to "a".repeat(64), "config" to "b".repeat(64))
        assertThatThrownBy { verifier.verify(signed, partial, key) }
            .isInstanceOf(ArtifactIntegrityException::class.java)
    }

    @Test
    fun `rejects empty key`() {
        val signed = SignedArtifactManifest(manifest = manifest, signature = "x".repeat(64))
        assertThatThrownBy { verifier.verify(signed, actualDigests, ByteArray(0)) }
            .isInstanceOf(ArtifactIntegrityException::class.java)
    }
}
