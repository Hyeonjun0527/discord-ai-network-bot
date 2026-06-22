package com.discordassistant.central.participation.application.model

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 모델 artifact 서명·hash 검증(NEXA-P17-T020, central 측·application). ml 측 서명기
 * (`nexa_policy.export.signing`)가 봉인한 bundle(ONNX/config/calibration)의 무결성을 로드 시 검증한다 —
 * (1) manifest HMAC-SHA256 서명이 유효하고 (2) 각 구성요소의 현재 sha256 이 manifest 와 일치해야 한다.
 *
 * **acceptance(T020) — 변조 artifact 가 registry 에서 ACTIVE 가 되지 않는다**: [verify] 가 서명 불일치·hash 불일치·
 * 구성요소 누락 중 하나라도 발견하면 [ArtifactIntegrityException]. [ShadowModelRegistry.selectForLive] 앞단에서
 * 이 검증을 강제하면, 변조된 artifact 는 APPROVED 라도 LIVE 로 올라가지 못한다(서명/hash 게이트).
 *
 * manifest 정규 직렬화는 ml 측 [signing.py] 와 **동일 형식**(SSOT): JSON, key 정렬, 컴포넌트 name 정렬,
 * `separators=(",",":")` 와 동치인 공백 없는 출력. 같은 키·같은 바이트면 양측 HMAC 이 일치한다(언어 경계 parity).
 *
 * 순수성 경계: application 레이어 — JDK 표준 crypto(javax.crypto)만. Spring/JPA/JDA 미참조.
 */
class ArtifactIntegrityVerifier {
    /**
     * [signed] manifest 서명과 각 구성요소 hash 를 검증한다. 어긋나면 [ArtifactIntegrityException](fail-closed).
     *
     * @param signed ml 측이 만든 서명 manifest(서명 + 컴포넌트 digest).
     * @param actualDigests 검증 시점에 다시 계산한 컴포넌트별 현재 sha256(name → sha256 hex).
     * @param signingKey 대칭 서명키(env 로만 주입 — 이 클래스는 키를 보관하지 않는다).
     */
    fun verify(
        signed: SignedArtifactManifest,
        actualDigests: Map<String, String>,
        signingKey: ByteArray,
    ) {
        if (signingKey.isEmpty()) {
            throw ArtifactIntegrityException("서명키가 비어 있다(fail-closed)")
        }
        // 1) manifest 서명 검증(manifest 변조 차단).
        val expected = hmacSha256Hex(signingKey, signed.manifest.canonicalBytes())
        if (!constantTimeEquals(expected, signed.signature)) {
            throw ArtifactIntegrityException("manifest 서명 불일치(변조 또는 잘못된 키) — ACTIVE 자격 없음")
        }
        // 2) 구성요소 집합 일치(누락/추가 차단).
        val manifestNames =
            signed.manifest.components
                .map { it.name }
                .toSet()
        if (manifestNames != actualDigests.keys) {
            throw ArtifactIntegrityException(
                "구성요소 집합 불일치: manifest=${manifestNames.sorted()} provided=${actualDigests.keys.sorted()}",
            )
        }
        // 3) 각 파일 hash 일치(파일 swap·변조 차단).
        signed.manifest.components.forEach { component ->
            val actual = actualDigests[component.name]
            if (actual != component.sha256) {
                throw ArtifactIntegrityException("구성요소 hash 불일치(변조): ${component.name} — ACTIVE 자격 없음")
            }
        }
    }

    private fun hmacSha256Hex(
        key: ByteArray,
        message: ByteArray,
    ): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(message).joinToString("") { "%02x".format(it) }
    }

    /** 길이·내용 비교에서 조기 반환하지 않는 상수시간 비교(타이밍 누출 방지). */
    private fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean {
        val ab = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        if (ab.size != bb.size) return false
        var result = 0
        for (i in ab.indices) {
            result = result or (ab[i].toInt() xor bb[i].toInt())
        }
        return result == 0
    }

    companion object {
        /** ml 측 SIGNATURE_ALGORITHM(HmacSHA256)과 일치. */
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}

/**
 * 서명 대상 manifest(application 값 객체·불변). ml 측 [ArtifactManifest] 와 동일 형식의 JVM 표현.
 * [canonicalBytes] 는 양측이 공유하는 정규 직렬화(JSON·key 정렬·공백 없음)다 — 서명 parity 의 SSOT.
 */
data class ArtifactManifest(
    val modelVersion: String,
    val components: List<ComponentDigest>,
    val formatVersion: Int = MANIFEST_FORMAT_VERSION,
) {
    init {
        require(modelVersion.isNotBlank()) { "modelVersion 은 비어 있을 수 없다" }
        require(components.isNotEmpty()) { "components 는 비어 있을 수 없다" }
    }

    /**
     * 정규 바이트(ml 측 `json.dumps(..., sort_keys=True, separators=(",",":"))` 와 동치). 컴포넌트는 name 으로
     * 정렬하고, 최상위 키는 알파벳 순(components < formatVersion < modelVersion)으로 직렬화한다(sort_keys 동치).
     */
    fun canonicalBytes(): ByteArray {
        val ordered = components.sortedBy { it.name }
        val componentsJson =
            ordered.joinToString(prefix = "[", postfix = "]", separator = ",") { c ->
                // 객체 내부 키도 알파벳 순(name < sha256).
                """{"name":${jsonString(c.name)},"sha256":${jsonString(c.sha256)}}"""
            }
        val json =
            """{"components":$componentsJson,"formatVersion":$formatVersion,"modelVersion":${jsonString(modelVersion)}}"""
        return json.toByteArray(Charsets.UTF_8)
    }

    private fun jsonString(value: String): String {
        val escaped =
            buildString {
                value.forEach { ch ->
                    when (ch) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(ch)
                    }
                }
            }
        return "\"$escaped\""
    }

    companion object {
        /** ml 측 MANIFEST_FORMAT_VERSION 과 일치(형식 SSOT). */
        const val MANIFEST_FORMAT_VERSION = 1
    }
}

/** bundle 한 구성요소의 무결성 항목 — 논리 이름과 sha256 hex(ml 측 ComponentDigest 와 동치). */
data class ComponentDigest(
    val name: String,
    val sha256: String,
) {
    init {
        require(name.isNotBlank()) { "name 은 비어 있을 수 없다" }
        require(sha256.isNotBlank()) { "sha256 은 비어 있을 수 없다" }
    }
}

/** 서명된 manifest(application 값 객체) — central 이 검증하는 단위. 서명은 hex(HMAC-SHA256). */
data class SignedArtifactManifest(
    val manifest: ArtifactManifest,
    val signature: String,
) {
    init {
        require(signature.isNotBlank()) { "signature 는 비어 있을 수 없다" }
    }
}

/** artifact 무결성 위반(서명·hash 불일치·구성 누락). fail-closed 로 던진다(acceptance T020). */
class ArtifactIntegrityException(
    message: String,
) : RuntimeException(message)
