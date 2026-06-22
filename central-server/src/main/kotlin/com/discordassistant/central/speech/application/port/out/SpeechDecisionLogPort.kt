package com.discordassistant.central.speech.application.port.out

import com.discordassistant.central.speech.domain.model.SpeechSocialAct

/**
 * 발화 파이프라인 결정 로그 아웃바운드 포트(NEXA-P17-T015/T016 enforcement, application).
 *
 * [com.discordassistant.central.speech.application.NexaSpeechPipelineService] 가 **실제 전송 직전** 내린
 * 안전 결정(고위험 fallback·critic 차단·consent 차단·최종 발화 여부)을 **원문 없이** 기록한다 — security-reviewer
 * M3 갭(안전 override·고위험 fallback decision-log sink 미소비) 해소. 원문/프롬프트/응답 본문은 담지 않는다
 * (logging-boundary.md): 결정 종류·사유 코드·후보 수 같은 provenance 만.
 *
 * 순수성 경계: application 레이어 — 도메인/application 값 객체·표준 타입만. Spring/JPA/JDA 미참조(어댑터가 채운다).
 */
interface SpeechDecisionLogPort {
    /** 발화 파이프라인 결정 한 건을 기록한다(원문 비포함). */
    fun record(decision: SpeechDecisionLog)

    /** 비-영속 기본값(테스트·미바인딩 환경) — 아무 것도 하지 않는다. */
    object Noop : SpeechDecisionLogPort {
        override fun record(decision: SpeechDecisionLog) = Unit
    }
}

/**
 * 발화 파이프라인 결정 로그 레코드(application 값 객체·불변·원문 비포함). 안전 enforcement 가 무엇을 왜 했는지의
 * provenance — 최종 결과, 적용된 고위험 directive 형태, critic 차단 사유 코드, 소비 후보 수 등.
 */
data class SpeechDecisionLog(
    /** 발화가 일어난 focus thread 가명 키(원문 snowflake 아님). */
    val focusThreadKey: String,
    /** 최종 발화 종류. */
    val socialAct: SpeechSocialAct,
    /** 최종 결과 종류(SPEAK/REACTION_ONLY/CANCEL/BLOCKED). */
    val outcome: SpeechDecisionOutcome,
    /** 고위험 분류로 안전 하강(고위험 directive 적용)됐는가. */
    val highRiskDowngraded: Boolean,
    /** consent 철회/미동의로 차단됐는가. */
    val consentBlocked: Boolean,
    /** 생성된 후보 수(critic 평가 전). */
    val generatedCandidateCount: Int,
    /** critic 이 차단한 사유 코드들(중복 제거). 비었으면 critic 차단 없음. */
    val criticBlockReasons: Set<String>,
) {
    init {
        require(focusThreadKey.isNotBlank()) { "focusThreadKey 는 비어 있을 수 없다" }
        require(generatedCandidateCount >= 0) { "generatedCandidateCount 는 음수일 수 없다" }
    }
}

/** 발화 파이프라인 최종 결과 종류(decision log 구분). */
enum class SpeechDecisionOutcome {
    /** 후보가 모든 안전 게이트를 통과해 발화한다. */
    SPEAK,

    /** 말 없이 리액션으로 하강. */
    REACTION_ONLY,

    /** 무발화(행동 취소) — 잘못된 말 대신 침묵. */
    CANCEL,

    /** consent 차단으로 어떤 외부 전송도 일어나지 않음. */
    BLOCKED,
}
