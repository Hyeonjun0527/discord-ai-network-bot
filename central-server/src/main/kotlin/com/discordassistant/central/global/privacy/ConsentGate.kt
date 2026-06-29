package com.discordassistant.central.global.privacy

import java.util.concurrent.ConcurrentHashMap

/**
 * 동의 철회 즉시 차단 게이트(NEXA-P17-T010 지원, security).
 *
 * NEXA 처리의 각 위험 지점(메시지 수집·발화·외부 GLM 전송·학습 export)에서 "이 가명 사용자가 아직 동의 상태인가"
 * 를 **즉시 동기 확인**하는 단일 진실원이다. 동의가 철회되면([revoke]) 그 시점 이후 모든 [checkAllowed] 가
 * 차단되어, 진행 중 ingestion·pending action·GLM 요청 직전 어느 지점에서 호출돼도 신규 처리가 0 이 된다
 * (acceptance T010). scheduler tick 비대기 — 철회와 동시에 효력.
 *
 * 기본값은 **차단(deny-by-default)**: 명시 동의([grant]) 전에는 처리 불가(consent-model: 동의 전 어떤 관찰도 금지).
 * 스레드 안전(동시 철회/검사).
 */
open class ConsentGate {
    private val granted = ConcurrentHashMap.newKeySet<String>()

    /** [subjectPseudonym] 의 동의를 부여한다(처리 허용). */
    fun grant(subjectPseudonym: String) {
        granted.add(subjectPseudonym)
    }

    /** [subjectPseudonym] 의 동의를 즉시 철회한다 — 이후 모든 [checkAllowed] 가 차단된다. */
    fun revoke(subjectPseudonym: String) {
        granted.remove(subjectPseudonym)
    }

    /** [subjectPseudonym] 이 동의 상태인지(허용 여부). 철회됐거나 미동의면 false. */
    fun isAllowed(subjectPseudonym: String): Boolean = subjectPseudonym in granted

    /**
     * [stage] 위험 지점에서 [subjectPseudonym] 처리가 허용되는지 확인한다. 미동의/철회면 [ConsentRevokedException]
     * 으로 fail-closed 차단한다(호출 성공 = 그 시점 동의 보장). 모든 위험 지점이 이 한 메서드로 게이트된다.
     */
    open fun checkAllowed(
        subjectPseudonym: String,
        stage: ProcessingStage,
    ) {
        if (!isAllowed(subjectPseudonym)) {
            throw ConsentRevokedException(subjectPseudonym = subjectPseudonym, stage = stage)
        }
    }
}

/** 동의 게이트가 적용되는 처리 위험 지점. */
enum class ProcessingStage {
    /** 메시지 수집·관찰(진행 중 ingestion). */
    INGESTION,

    /** judge 입력용 원문 context window 조회. */
    RAW_CONTEXT_READ,

    /** 단일 participation judge 호출. */
    JUDGE_CALL,

    /** 발화 후보 생성. */
    SPEECH_GENERATION,

    /** 예약된 action 실행 직전. */
    PENDING_ACTION,

    /** 외부 GLM 요청 직전. */
    EXTERNAL_GLM_REQUEST,

    /** 학습 dataset export. */
    TRAINING_EXPORT,
}

/** 동의 철회/미동의 상태에서 처리 시도 — fail-closed 차단. 원문 비포함(가명·stage 만). */
class ConsentRevokedException(
    val subjectPseudonym: String,
    val stage: ProcessingStage,
) : RuntimeException("동의 철회/미동의 차단: stage=$stage subject=$subjectPseudonym")
