package com.discordassistant.central.requestlog.application

/**
 * NEXA 발화 generation 의 requestlog 상관 메타데이터(NEXA-P15-T010, 순수 값 객체).
 *
 * participation 결정 → speech GLM 요청 → actionruntime Discord 전송을 **원문 없이** 연결해 감사할 수 있게 하는
 * 식별자 묶음이다. decision log·requestlog·action audit 가 같은 [correlationId] 로 엮인다(logging-boundary.md:
 * snowflake/원문 비저장, 가명·안정 코드만).
 *
 * **acceptance(T010) — decision→GLM request→Discord message 를 감사할 수 있다**:
 *  - [correlationId]: participation 결정과 requestlog 를 잇는 단일 키(원문 비포함).
 *  - [decisionId]: 어떤 participation 결정에서 나온 generation 인지.
 *  - [actionId]: 어떤 예약 행동(actionruntime)으로 전송됐는지.
 *  - [modelVersion]: 어떤 GLM 모델 버전이 응답했는지(비용·품질 추적).
 *  이 넷이면 "이 Discord 메시지가 어느 결정·어느 모델에서 나왔는지" 를 원문 없이 역추적할 수 있다.
 *
 * 순수성: 표준 타입만. Spring/JPA/JDA 미참조(어댑터가 영속화한다).
 */
data class NexaCorrelation(
    /** decision log ↔ requestlog ↔ action audit 를 잇는 단일 상관 키(원문 비포함). */
    val correlationId: String,
    /** 이 generation 을 유발한 participation 결정 식별자. */
    val decisionId: String,
    /** 이 발화를 전송한 예약 행동 식별자(미전송이면 null — shadow/생성만). */
    val actionId: String?,
    /** 응답한 GLM 모델 버전 라벨(예: "glm-5.1"). */
    val modelVersion: String,
) {
    init {
        require(correlationId.isNotBlank()) { "correlationId 는 비어 있을 수 없다" }
        require(decisionId.isNotBlank()) { "decisionId 는 비어 있을 수 없다" }
        require(modelVersion.isNotBlank()) { "modelVersion 은 비어 있을 수 없다" }
    }

    companion object {
        /** requestlog purpose 라벨 — 기존 일반 요청과 NEXA 발화를 비용·관측에서 구분한다(T009/T010). */
        const val PURPOSE: String = "NEXA_SPEECH"
    }
}

/**
 * NEXA 발화 generation 의 requestlog 상관 기록 아웃바운드 포트(NEXA-P15-T010, application 레이어).
 *
 * speech/routing 이 generation 1건의 상관 메타데이터([NexaCorrelation])를 **원문 없이** requestlog 에 남기는 좁은
 * 포트다. 구현은 requestlog adapter 가 채운다(기존 UsageLog 와 별개 또는 연동). 기본 [Noop] 은 미기록(테스트·미연동).
 */
interface NexaCorrelationRecorderPort {
    /** generation 1건의 상관 메타데이터를 기록한다(원문 비포함 — 식별자·모델 버전만). */
    fun record(correlation: NexaCorrelation)

    /** no-op 기본 구현(미연동·테스트). */
    object Noop : NexaCorrelationRecorderPort {
        override fun record(correlation: NexaCorrelation) = Unit
    }
}
