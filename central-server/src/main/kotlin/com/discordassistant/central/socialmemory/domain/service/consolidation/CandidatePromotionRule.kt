package com.discordassistant.central.socialmemory.domain.service.consolidation

import com.discordassistant.central.socialmemory.domain.model.MemoryStatus
import com.discordassistant.central.socialmemory.domain.model.extraction.MemoryCandidate
import com.discordassistant.central.socialmemory.domain.model.extraction.StatementModality
import com.discordassistant.central.socialmemory.domain.model.fact.TemporalFact

/**
 * 기억 후보의 **검증·승격 규칙**(NEXA-P07-T016, 순수 도메인 서비스). 후보를 provenance·consent·modality·confidence·
 * conflict 로 검증해 저장(STORE)·보류(HOLD)·폐기(DISCARD) 중 하나로 판정한다.
 *
 * **acceptance(T016) — 모든 승격 결과에 reason code 가 남는다**: [decide] 는 언제나 [PromotionReason] 을 포함한
 * [PromotionDecision] 을 돌려준다 — 폐기·보류·저장 모두 왜 그렇게 됐는지 코드로 설명된다(raw chain-of-thought
 * 저장 없음). 자연어 사유가 아니라 닫힌 enum 코드만 남긴다(감사·재현 가능).
 *
 * **농담/부정/인용을 단일 사실로 승격하지 않는다(T022)**: modality 가 단정(ASSERTED)이 아니면 DISCARD(비단정).
 * 민감 추론(sensitive)은 DISCARD(민감). consent 없으면 DISCARD(동의). GLM 단일 추출은 confidence 가 낮아 사실
 * 확정 권한이 없으므로, 같은 주장의 기존 명시 사실과 충돌하면 HOLD(보류) — 임의 승격하지 않는다(T009 연계).
 *
 * 순수성: Spring/JPA/JDA 미참조.
 */
object CandidatePromotionRule {
    /**
     * [candidate] 를 같은 주장의 [existingFacts](기존 fact, 보통 같은 subject/predicate) 맥락에서 검증해 판정한다.
     * 항상 reason code 를 포함한다. 순서: consent → modality → sensitive → conflict → store.
     */
    fun decide(
        candidate: MemoryCandidate,
        existingFacts: List<TemporalFact> = emptyList(),
    ): PromotionDecision {
        if (!candidate.source.consentGranted) {
            return PromotionDecision(PromotionOutcome.DISCARD, PromotionReason.NO_CONSENT)
        }
        if (candidate.modality != StatementModality.ASSERTED) {
            // 농담/부정/인용/가정은 단일 사실로 승격하지 않는다(T022).
            return PromotionDecision(PromotionOutcome.DISCARD, PromotionReason.NON_ASSERTION)
        }
        if (candidate.sensitive) {
            // 민감 카테고리 추론은 저장하지 않는다(observable-state-policy 금지 목록).
            return PromotionDecision(PromotionOutcome.DISCARD, PromotionReason.SENSITIVE_INFERENCE)
        }
        // 같은 주장의 기존 ACTIVE 사실 중 object 가 다른 것이 있으면 충돌 — GLM 약한 근거로 임의 승격 금지(HOLD).
        val conflicting =
            existingFacts.any { fact ->
                fact.status == MemoryStatus.ACTIVE &&
                    fact.subject == candidate.subject &&
                    fact.predicate == candidate.predicate &&
                    fact.obj != candidate.obj
            }
        if (conflicting) {
            return PromotionDecision(PromotionOutcome.HOLD, PromotionReason.CONFLICTS_EXISTING)
        }
        // provenance·동의·단정·비민감·무충돌 — 낮은 confidence 후보를 저장 후보로 승격(여전히 확정 아님).
        return PromotionDecision(PromotionOutcome.STORE, PromotionReason.PROVENANCE_OK)
    }
}

/** 승격 판정 결과(NEXA-P07-T016). 항상 [reason] 코드를 포함한다(acceptance — 모든 승격에 reason). */
data class PromotionDecision(
    val outcome: PromotionOutcome,
    val reason: PromotionReason,
)

/** 후보 처리 결과(저장/보류/폐기). 물리 저장은 application 어댑터가 수행한다. */
enum class PromotionOutcome {
    /** 저장 가능(검증 통과). 낮은 confidence 기억으로 적재된다. */
    STORE,

    /** 보류(충돌 등 근거 부족). 사람/추가 관찰을 기다린다 — 임의 승격하지 않는다. */
    HOLD,

    /** 폐기(동의 없음·비단정·민감). 저장하지 않는다. */
    DISCARD,
}

/** 승격/폐기/보류 **사유 코드**(NEXA-P07-T016). 자연어가 아니라 닫힌 코드 — 감사·재현 가능, raw CoT 미저장. */
enum class PromotionReason {
    /** provenance·동의·단정·비민감·무충돌 — 저장 후보. */
    PROVENANCE_OK,

    /** 동의 스냅샷이 false — 옵트아웃 사용자라 폐기. */
    NO_CONSENT,

    /** 농담/부정/인용/가정 등 비단정 발화 — 사실로 승격하지 않음(T022). */
    NON_ASSERTION,

    /** 정치·종교·성적지향·건강 등 민감 추론 — 저장 금지(observable-state-policy). */
    SENSITIVE_INFERENCE,

    /** 같은 주장의 기존 사실과 object 충돌 — GLM 약한 근거로 임의 승격 금지, 보류(T009). */
    CONFLICTS_EXISTING,
}
