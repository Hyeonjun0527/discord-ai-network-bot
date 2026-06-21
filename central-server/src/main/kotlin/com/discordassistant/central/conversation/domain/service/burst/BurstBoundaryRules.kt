package com.discordassistant.central.conversation.domain.service.burst

import com.discordassistant.central.conversation.domain.model.burst.BurstGapPolicy
import com.discordassistant.central.conversation.domain.model.burst.MessageFragment
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import java.time.Duration
import java.time.Instant

/**
 * 버스트 경계 규칙 모음(NEXA-P04-T007~T010, 순수 함수). 각 규칙은 "[next] 조각이 작성자의 [openBurst] 에 이어붙는가,
 * 아니면 새 버스트를 시작하는 경계인가" 를 독립 예측 함수로 답한다. 최종 결정은 [FixedGapBurstSegmenter] 가 이들을
 * 합쳐 내린다 — 여기서는 각 신호별 boolean 만 제공해 규칙마다 단위 테스트가 가능하다(SRP·결정론).
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 [Instant]/[Duration] 만 쓰고 상태가 없다(모든 입력을 인자로 받는다).
 */
object BurstBoundaryRules {
    /**
     * T009 — thread 경계. [next] 가 [openBurst] 와 **다른 위치**(채널↔스레드, 또는 다른 스레드)면 경계다(true).
     * Discord 에서 스레드 메시지와 부모 채널 메시지는 별개 흐름이라 같은 버스트로 합치면 안 된다.
     */
    fun crossesLocationBoundary(
        openBurst: UtteranceBurst,
        next: MessageFragment,
    ): Boolean = next.locationKey != openBurst.location

    /**
     * T008 — reply target 변경 경계. 같은 작성자라도 [next] 의 reply 대상이 [openBurst] 마지막 조각과 다르면 경계다.
     * 단, 둘 다 reply 가 아니면(null==null) 연속 자유 발화라 경계가 아니다 — 같은 target 의 짧은 연속은 유지된다.
     */
    fun changesReplyTarget(
        openBurst: UtteranceBurst,
        next: MessageFragment,
    ): Boolean = next.replyTo != openBurst.lastFragment.replyTo

    /**
     * T007 — 다른 작성자 개입 종료 규칙. [intruder] 가 OPEN 버스트의 작성자와 **다른 사람**이고, 그 조각 type 이
     * 실제 발화로 취급되면([FragmentType.endsOtherAuthorBurst]) 기존 작성자의 OPEN 버스트를 종료해야 한다(true).
     * 같은 작성자이거나(개입 아님) 이모지/시스템 메시지면 종료하지 않는다(false).
     */
    fun otherAuthorEndsBurst(
        openBurst: UtteranceBurst,
        intruder: MessageFragment,
    ): Boolean = intruder.authorId != openBurst.authorId && intruder.type.endsOtherAuthorBurst

    /**
     * T004/T006 — 시간 간격 경계. [openBurst] 마지막 조각과 [next] 사이 간격이 [effectiveGap] 초과면 경계다(true).
     * [effectiveGap] 은 baseline gap 에 typing 연장이 반영되고 [BurstGapPolicy.clamp] 로 안전 범위에 잘린 값이다.
     */
    fun exceedsGap(
        openBurst: UtteranceBurst,
        next: MessageFragment,
        effectiveGap: Duration,
    ): Boolean = Duration.between(openBurst.lastFragmentAt, next.occurredAt) > effectiveGap

    /**
     * T010 — typing 신호 기반 종료 연장. 작성자가 [next] 발생 시점에도 typing 중([typingExpiresAt] 미만)이면 baseline
     * gap 을 [policy].maxGap 까지 늘려 finalize 를 미룬다. typing 신호가 없거나 만료됐으면 baseline 그대로다.
     *
     * **acceptance(T010) — 무한 연기 금지·hard deadline**: 연장값은 항상 [BurstGapPolicy.clamp] 로 maxGap 에 잘린다.
     * 따라서 typing 이벤트가 유실돼 [typingExpiresAt] 이 갱신되지 않으면(만료) 연장이 풀려 baseline gap 으로 돌아가고,
     * typing 이 살아 있어도 maxGap 이라는 hard ceiling 을 못 넘는다 — 어느 경우에도 무한히 열려 있지 않다.
     */
    fun effectiveGapWithTyping(
        policy: BurstGapPolicy,
        baselineGap: Duration,
        typingExpiresAt: Instant?,
        at: Instant,
    ): Duration {
        val typingActive = BurstGapFeatures.isTypingActive(typingExpiresAt, at)
        val candidate = if (typingActive) policy.maxGap else baselineGap
        return policy.clamp(candidate)
    }
}
