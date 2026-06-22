package com.discordassistant.central.socialmemory.domain.model.extraction

import com.discordassistant.central.socialmemory.domain.model.Confidence
import com.discordassistant.central.socialmemory.domain.model.MemoryEvidence
import com.discordassistant.central.socialmemory.domain.model.VisibilityScope
import com.discordassistant.central.socialmemory.domain.model.source.MemorySource

/**
 * 추출기(GLM)가 finalized scene 에서 만들어낸 **기억 후보**(NEXA-P07-T014, 순수 도메인 값 객체·불변).
 *
 * **후보는 확정 사실이 아니다**: GLM 한 번의 추출은 약한 근거이므로([MemoryEvidence.GLM_EXTRACTION]) 후보의
 * 신뢰는 [Confidence.CERTAIN_THRESHOLD] 미만으로 시작한다(acceptance T010). 검증·승격(T016)을 통과해야 비로소
 * 저장 가능한 기억 aggregate(Episodic/TemporalFact/Relationship/PendingIntent)가 된다.
 *
 * 후보는 추출기가 채울 수 있는 **구조화 필드만** 가진다 — 원문/자연어 chain-of-thought 를 담지 않는다
 * (data-categories.md, observable-state-policy: 관찰 사실만, 민감 추론 금지). subject/predicate/object 와 가시성,
 * provenance([source]), 그리고 농담/부정/인용 같은 비단정 맥락 표식([modality])만 운반한다.
 *
 * 순수성: Spring/JPA/JDA·ainetwork 미참조.
 */
data class MemoryCandidate(
    /** 어느 기억 유형 후보인가(저장 시 어느 aggregate 로 승격될지). */
    val kind: CandidateKind,
    /** 이 후보가 묶일 guild 가명 + 가시성 스코프(cross-guild 노출 금지, T011). */
    val visibility: VisibilityScope,
    /** 사실의 주어(보통 사람 가명 토큰). */
    val subject: String,
    /** 닫힌/안정 술어 키(예: uses_language·plays_game). 원문 문장이 아님. */
    val predicate: String,
    /** 짧은 구조화 목적어 값(원문 복사 아님). */
    val obj: String,
    /** 이 후보의 출처(원천 이벤트 ID·추출 버전·동의 스냅샷). 출처 없는 후보는 만들 수 없다. */
    val source: MemorySource,
    /** 추출기가 본 발화의 **양상**(단정/농담/부정/인용/가정). 사실 승격 게이트의 입력(T016/T022). */
    val modality: StatementModality = StatementModality.ASSERTED,
    /** 이 후보가 민감 카테고리(정치·종교·성적지향·건강 등)를 건드리는가 — 추론 사실 승격 차단 표식(T022). */
    val sensitive: Boolean = false,
) {
    init {
        require(subject.isNotBlank()) { "subject 는 비어 있을 수 없다" }
        require(predicate.isNotBlank()) { "predicate 는 비어 있을 수 없다" }
        require(obj.isNotBlank()) { "obj 는 비어 있을 수 없다" }
    }

    /** GLM 추출 기본 신뢰(약한 근거). 단독으로 확정이 되지 않는다(acceptance T010). */
    val confidence: Confidence
        get() = Confidence.forEvidence(MemoryEvidence.GLM_EXTRACTION)
}

/** 후보가 승격될 수 있는 기억 유형(NEXA-P07-T014). socialmemory 가 소유하는 4개 유형. */
enum class CandidateKind {
    EPISODIC,
    TEMPORAL_FACT,
    RELATIONSHIP,
    PENDING_INTENT,
}

/**
 * 발화의 **양상**(NEXA-P07-T014/T022). 같은 문장이라도 단정인지 농담/부정/인용/가정인지에 따라 사실 승격 여부가
 * 다르다 — "나 일베 아님"(부정), "걔가 일베래"(인용), "내가 일베면 어쩔 건데"(가정)는 단일 사실로 승격하지 않는다.
 */
enum class StatementModality {
    /** 본인이 직접 단정한 발화(예: "나 파이썬 써"). 사실 승격 후보. */
    ASSERTED,

    /** 농담/비꼼 맥락(예: "ㅋㅋ 나 일베지"). 사실로 영구 저장하지 않는다. */
    JOKE,

    /** 부정(예: "나 일베 아님"). 긍정 사실로 승격하지 않는다. */
    NEGATED,

    /** 타인 인용/전언(예: "걔가 ~래"). 주어 본인의 사실이 아니다. */
    QUOTED,

    /** 가정·조건(예: "내가 ~라면"). 사실이 아니다. */
    HYPOTHETICAL,
    ;

    /** 이 양상이 **단정 사실 승격** 대상인가. ASSERTED 만 true — 나머지는 사실로 굳히지 않는다(T022). */
    val isAssertion: Boolean
        get() = this == ASSERTED
}
