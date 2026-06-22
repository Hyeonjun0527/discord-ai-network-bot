package com.discordassistant.central.requestlog.application

import java.security.MessageDigest

/**
 * 외부 GLM 요청 감사 hash(NEXA-P17-T012, security).
 *
 * 외부(GLM) 로 나간 요청을 **원문 payload 없이** 사후 감사할 수 있게, payload 의 canonical hash·전송된 필드
 * 목록·모델·purpose·consent 스냅샷을 기록한다. 원문은 저장하지 않으므로 누출 표면이 없고, 같은 입력은 같은
 * hash 를 내므로 "어떤 범위의 데이터가 외부로 나갔는지" 를 사후에 검증할 수 있다(acceptance T012).
 *
 * 순수 application: 표준 타입만. Spring/JPA/JDA 미참조. 영속화는 어댑터가 [GlmRequestAuditRecord] 를 받아 채운다.
 */
class GlmRequestAuditFactory {
    /**
     * 전송 payload 와 메타데이터로 감사 레코드를 만든다. payload 원문은 보관하지 않고 canonical hash 만 담는다.
     * [fieldNames] 는 payload 에 실제 포함된 필드(allowlist serializer 가 내보낸 키) 목록 — 범위 감사용.
     */
    fun create(
        payload: String,
        fieldNames: List<String>,
        model: String,
        purpose: String,
        consentSnapshotId: String,
    ): GlmRequestAuditRecord {
        require(model.isNotBlank()) { "model 은 비어 있을 수 없다" }
        require(purpose.isNotBlank()) { "purpose 는 비어 있을 수 없다" }
        return GlmRequestAuditRecord(
            payloadHash = canonicalHash(payload),
            fieldNames = fieldNames.sorted(),
            model = model,
            purpose = purpose,
            consentSnapshotId = consentSnapshotId,
        )
    }

    /** payload 의 canonical SHA-256 hex hash(개행 정규화 후). 원문은 반환·저장하지 않는다. */
    fun canonicalHash(payload: String): String {
        val canonical = payload.replace("\r\n", "\n").trim()
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * 외부 GLM 요청 1건의 감사 레코드(불변). 원문 payload 를 담지 않는다 — hash·필드 목록·메타만(acceptance T012).
 */
data class GlmRequestAuditRecord(
    /** 전송 payload 의 canonical SHA-256 hash(원문 비저장·사후 동일성 검증용). */
    val payloadHash: String,
    /** payload 에 포함된 필드 키 목록(정렬됨 — 범위 감사). */
    val fieldNames: List<String>,
    /** 응답 모델 라벨(예: "glm-5.1"). */
    val model: String,
    /** 요청 목적(예: NexaCorrelation.PURPOSE). */
    val purpose: String,
    /** 전송 시점 consent 스냅샷 식별자(동의 상태 사후 추적). */
    val consentSnapshotId: String,
)
