package com.discordassistant.central.global.observability

/**
 * NEXA end-to-end 상관 컨텍스트(NEXA-P18-T002, 순수 값 객체).
 *
 * 한 관찰 흐름 **event→burst→scene→decision→action→GLM→Discord message** 를 **단일 [correlationId]** 로 엮어
 * 원문 없이 역추적할 수 있게 한다. requestlog 의 [com.discordassistant.central.requestlog.application.NexaCorrelation]
 * (decision→GLM→Discord 발화 1건)을 **상류 단계(event/burst/scene)까지 확장**한 운영 추적용 컨텍스트다.
 *
 * **acceptance(T002) — 각 단계가 없어도 chain 이 깨지지 않고 로그에 원문이 없다**:
 *  - 모든 stage stamp 는 nullable 이며, [stages] 는 **현재까지 도달한 단계만** 순서대로 돌려준다 — IGNORE 처럼
 *    decision 에서 끝나(action/glm/discord 없음) 흐름도 chain 이 끊기지 않는다. 단계를 건너뛰어도(예: scene 없이
 *    burst→decision) 남은 단계가 같은 [correlationId] 로 계속 이어진다.
 *  - 어떤 필드도 **원문/식별 가능한 ID 를 담지 않는다** — correlation/단계는 안정 stamp(가명·코드)뿐이다
 *    (logging-boundary.md). MDC/로그에는 [correlationId] 만 싣고 본문은 싣지 않는다.
 *
 * 불변(immutable): `withXxx` 는 새 인스턴스를 만든다 — 단계 진행을 값으로 누적해 동시성 안전.
 *
 * 순수성: 표준 타입만. Spring/JPA/JDA 미참조(MDC 주입·영속화는 어댑터/필터가 한다).
 */
data class NexaCorrelationContext(
    /** 흐름 전체를 잇는 단일 상관 키(원문·snowflake 비포함 — 안정 토큰). */
    val correlationId: String,
    /** event 단계 stamp(관찰 유입). */
    val eventStamp: String? = null,
    /** burst 단계 stamp(버스트 분할). */
    val burstStamp: String? = null,
    /** scene 단계 stamp(장면). */
    val sceneStamp: String? = null,
    /** decision 단계 stamp(participation 결정 — IGNORE 포함). */
    val decisionStamp: String? = null,
    /** action 단계 stamp(actionruntime 예약). null 이면 미예약(IGNORE/WAIT). */
    val actionStamp: String? = null,
    /** GLM generation 단계 stamp(speech 발화 생성). null 이면 미생성(비SPEAK·shadow). */
    val glmStamp: String? = null,
    /** Discord 전송 단계 stamp(실제 발화). null 이면 미전송(shadow/취소). */
    val discordStamp: String? = null,
) {
    init {
        require(correlationId.isNotBlank()) { "correlationId 는 비어 있을 수 없다" }
    }

    fun withEvent(stamp: String): NexaCorrelationContext = copy(eventStamp = stamp)

    fun withBurst(stamp: String): NexaCorrelationContext = copy(burstStamp = stamp)

    fun withScene(stamp: String): NexaCorrelationContext = copy(sceneStamp = stamp)

    fun withDecision(stamp: String): NexaCorrelationContext = copy(decisionStamp = stamp)

    fun withAction(stamp: String): NexaCorrelationContext = copy(actionStamp = stamp)

    fun withGlm(stamp: String): NexaCorrelationContext = copy(glmStamp = stamp)

    fun withDiscord(stamp: String): NexaCorrelationContext = copy(discordStamp = stamp)

    /**
     * 현재까지 도달한 단계만 **순서대로** 돌려준다(없는 단계는 건너뛴다 — chain 이 깨지지 않는다, acceptance T002).
     * 빈 단계가 있어도 다음 단계가 같은 [correlationId] 로 이어진다는 사실은 [correlationId] 동일성으로 보장된다.
     */
    fun stages(): List<NexaStage> =
        buildList {
            if (eventStamp != null) add(NexaStage.EVENT)
            if (burstStamp != null) add(NexaStage.BURST)
            if (sceneStamp != null) add(NexaStage.SCENE)
            if (decisionStamp != null) add(NexaStage.DECISION)
            if (actionStamp != null) add(NexaStage.ACTION)
            if (glmStamp != null) add(NexaStage.GLM)
            if (discordStamp != null) add(NexaStage.DISCORD)
        }

    companion object {
        /** MDC/로그 키 — 본문 없이 이 키 1개로 흐름을 잇는다. */
        const val MDC_KEY: String = "nexaCorrelationId"

        /** 새 흐름을 [correlationId] 로 시작한다(아직 어떤 단계도 도달 전). */
        fun start(correlationId: String): NexaCorrelationContext = NexaCorrelationContext(correlationId)
    }
}

/**
 * NEXA 상관 흐름의 단계(순수 enum, 저카디널리티). 단계 누락 시 [NexaCorrelationContext.stages] 에서 자동으로 빠진다.
 */
enum class NexaStage(
    val label: String,
) {
    EVENT("event"),
    BURST("burst"),
    SCENE("scene"),
    DECISION("decision"),
    ACTION("action"),
    GLM("glm"),
    DISCORD("discord"),
}
