package com.discordassistant.central.socialmemory.domain.service.relationship

import java.time.Duration

/**
 * response expectation(응답 기대 타이밍) 지표(NEXA-P06-T008, 순수 도메인 값 객체·무상태).
 *
 * 직접 호출·질문 후 **해당 사용자가 통상 기다리는 시간**과 재호출 패턴을 측정한다(observable-state-policy 허용:
 * "질문/멘션 후 답을 기다리는 정황" 관찰). 사용자가 보인 대기 행동의 집계일 뿐이다.
 *
 * **acceptance(T008) — NEXA 응답 의무가 아니라 timing feature 로만 사용된다**:
 * 이 객체는 [typicalWait](관찰된 중앙 대기)와 [recallRate](재호출 비율)만 노출한다. "NEXA 가 응답해야 한다" 같은
 * 의무/명령 필드가 없다([isObligation] 항상 false 가드). participation 이 timing 입력으로만 읽고 행동은 스스로 정한다
 * (socialmemory 불변식 2 — 상태는 행동을 결정하지 않는다).
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 java.time 만 쓴다.
 */
data class ResponseExpectation(
    /** 직접 호출/질문 후 사용자가 통상 기다린 대기 시간(관찰 중앙값). null 이면 표본 없음. */
    val typicalWait: Duration?,
    /** 응답이 없을 때 사용자가 같은 건을 다시 호출한 횟수. */
    val recalls: Int,
    /** 직접 호출/질문 표본 수(재호출 비율의 분모). */
    val directCalls: Int,
) {
    init {
        require(recalls >= 0) { "recalls 는 음수일 수 없다" }
        require(directCalls >= 0) { "directCalls 는 음수일 수 없다" }
        require(typicalWait == null || !typicalWait.isNegative) { "typicalWait 는 음수일 수 없다" }
        require(!isObligation) { "ResponseExpectation 은 응답 의무가 아니라 timing feature 다(acceptance T008)" }
    }

    /**
     * NEXA 응답 의무 여부 — **항상 false**. 이 지표는 timing 입력일 뿐 행동 명령이 아니라는 불변식의 가드다
     * (acceptance T008, socialmemory 불변식 2).
     */
    val isObligation: Boolean
        get() = false

    /** 재호출 비율 [0,1] (recalls / directCalls). 표본 0 이면 0. 높을수록 빠른 응답을 기대한다는 관찰 신호. */
    val recallRate: Double
        get() = if (directCalls == 0) 0.0 else (recalls.toDouble() / directCalls.toDouble()).coerceIn(0.0, 1.0)

    /** 표본이 있는가(직접 호출이 1회 이상). */
    val hasSample: Boolean
        get() = directCalls > 0

    companion object {
        /** 표본 없는 초기 상태. */
        val EMPTY: ResponseExpectation = ResponseExpectation(typicalWait = null, recalls = 0, directCalls = 0)
    }
}
