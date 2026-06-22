package com.discordassistant.central.conversation.domain.service.burst

import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst

/**
 * 버스트 작성자 종류 분류기(NEXA-P04-T013, 순수 함수). NEXA 자신·다른 봇·webhook 메시지를 **인간 버스트 학습 통계**에서
 * 분리한다. 학습 통계(평균 버스트 길이·cadence 등 P04 후반의 서버 문화 학습)는 인간 발화 분포를 추정하는데, 봇/webhook
 * 의 규칙적·기계적 발화가 섞이면 분포가 오염된다(acceptance T013).
 *
 * 순수성: conversation.domain 규칙(NexaArchitectureTest.nexaDomainsArePure)을 위해 Spring/JPA/JDA/adapter 타입을
 * 일절 참조하지 않는다. 어댑터(platform/discord)가 JDA 의 `Author.isBot`/`isWebhookMessage` 신호를 [BurstActorKind]
 * 값으로 정규화해 운반하고, 이 도메인 분류기는 그 값만 본다(JDA 타입 비참조).
 *
 * **acceptance(T013) — 인간 평균 비오염**: [includeInHumanLearning] 이 [BurstActorKind.HUMAN] 만 통과시켜, 학습
 * 통계 누적 시 봇/NEXA/webhook 버스트가 입력에서 제외된다. [retainHumanBursts] 가 컬렉션 단위로 그 필터를 적용한다.
 */
object BurstActorClassifier {
    /** 이 [kind] 의 발화를 인간 버스트 학습 통계에 포함하는가(HUMAN 만 true). */
    fun includeInHumanLearning(kind: BurstActorKind): Boolean = kind == BurstActorKind.HUMAN

    /**
     * [bursts] 중 인간 발화만 학습 통계 입력으로 남긴다([kindOf] 가 각 버스트의 작성자 종류를 알려준다).
     * NEXA/다른 봇/webhook 버스트는 제외돼 인간 평균 버스트 길이를 오염시키지 않는다.
     */
    fun retainHumanBursts(
        bursts: List<UtteranceBurst>,
        kindOf: (UtteranceBurst) -> BurstActorKind,
    ): List<UtteranceBurst> = bursts.filter { includeInHumanLearning(kindOf(it)) }
}

/**
 * 버스트 작성자 종류(순수 도메인 enum). 어댑터가 Discord 신호(isBot/isWebhookMessage/NEXA 자기 식별)를 정규화해 운반한다.
 * 인간만 학습 통계에 들어간다(T013).
 */
enum class BurstActorKind {
    /** 사람 사용자 — 인간 버스트 학습 통계의 유일한 입력. */
    HUMAN,

    /** NEXA 자신의 발화 — 자기 학습 루프 방지를 위해 인간 통계에서 제외. */
    NEXA,

    /** NEXA 가 아닌 다른 봇 — 기계적 발화라 인간 통계에서 제외. */
    OTHER_BOT,

    /** webhook 메시지(연동·자동 게시) — 인간 발화가 아니라 통계에서 제외. */
    WEBHOOK,
}
