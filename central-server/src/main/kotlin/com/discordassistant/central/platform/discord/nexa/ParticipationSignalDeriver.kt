package com.discordassistant.central.platform.discord.nexa

/**
 * NEXA participation 의 **히스토리 도출 신호**를 채널별 경량 버퍼로 계산한다(순수·테스트 가능 — JDA 미참조).
 *
 * [CoreInterventionRules] 의 dead-wired 필드(continuation 보조·중복·burst 미완·사적 핑퐁)는 "최근 대화 맥락"이 있어야
 * 발동한다. 능동 JDA 히스토리 조회(`channel.history`)는 메시지마다 비동기·레이트리밋이라 게이트웨이 스레드에서
 * 블로킹하기 부적절하다. 그래서 봇이 **이미 받은** 사람 메시지를 채널별 ring buffer 로 보관하고, 트리거가 올 때 그
 * 버퍼에서 신호를 도출한다(추가 네트워크 0). 버퍼는 채널당 최대 [maxPerChannel] 개만 유지한다.
 *
 * **도출하는 신호(트리거를 push 하기 직전 상태 기준)**:
 *  - `duplicateOfPrevHuman`(A4): 트리거 본문이 직전 사람 메시지 본문과 정확히 같은가.
 *  - `burstIncomplete`(B1): 직전 사람 메시지가 트리거와 **같은 화자**이고 짧은 간격([burstGapMs] 이하)으로 이어졌는가
 *    (이어말 중 — 묶음 미완성). gap 신호가 없으면(첫 메시지) false.
 *  - `priorHumanSpeakerLabels`(B17): 버퍼에 있는 사람 화자 라벨(트리거 화자 제외) 순서 목록.
 *  - `firstMessageText`(B17): 버퍼의 첫 메시지 본문.
 *  - `conversationMentionsNia`(B17): 버퍼의 어떤 사람 메시지든 니아를 호명/@멘션했는가.
 *
 * 니아 자신의 발화 토큰(continuation A7)은 이 버퍼로 도출하지 않는다 — 니아 메시지는 봇 author 라 메시지 핸들러가
 * early-return 해 이 버퍼로 오지 않기 때문이다. 호출자(DiscordBot)가 reply 의 referencedMessage(니아 메시지)에서
 * 별도로 도출한다.
 *
 * **graceful**: 도출 실패/버퍼 부재는 보수적 기본값(빈 목록·false = 덜 발화)으로 떨어진다. 동시성: ConcurrentHashMap +
 * 채널 deque 단위 동기화.
 *
 * @property maxPerChannel 채널당 보관할 최근 사람 메시지 수(메모리 상한). 사적 핑퐁/첫 메시지 판정에 충분한 작은 창.
 * @property burstGapMs    같은 화자 연속 메시지를 "이어말(미완성)"로 볼 최대 간격(ms). 이하면 burstIncomplete.
 */
class ParticipationSignalDeriver(
    private val maxPerChannel: Int = 12,
    private val burstGapMs: Long = 7_000,
) {
    /** 버퍼에 보관하는 사람 메시지 한 건(가명 라벨·짧은 본문·시각·니아 호명 여부 — 원문 식별자 비저장). */
    data class HumanMessage(
        val speakerLabel: String,
        val text: String,
        val tsMs: Long,
        val mentionsNia: Boolean,
    )

    /** 트리거에 대해 도출한 히스토리 신호(브리지 [ParticipationMessageSignal] 로 그대로 전달). */
    data class DerivedSignals(
        val duplicateOfPrevHuman: Boolean,
        val burstIncomplete: Boolean,
        val priorHumanSpeakerLabels: List<String>,
        val firstMessageText: String?,
        val conversationMentionsNia: Boolean,
    )

    private val buffers = java.util.concurrent.ConcurrentHashMap<Long, ArrayDeque<HumanMessage>>()

    /**
     * [channelId] 버퍼의 **현재 상태**(트리거 push 전)로 신호를 도출한 뒤, 트리거를 버퍼에 추가한다.
     * 도출은 push 전 상태 기준이라 트리거 자신이 "직전 사람 메시지"에 포함되지 않는다.
     */
    fun deriveAndRecord(
        channelId: Long,
        trigger: HumanMessage,
    ): DerivedSignals {
        val deque = buffers.computeIfAbsent(channelId) { ArrayDeque() }
        return synchronized(deque) {
            val prior = deque.toList() // push 전 스냅샷.
            val prev = prior.lastOrNull()
            val duplicate = prev != null && prev.text.isNotBlank() && prev.text.trim() == trigger.text.trim()
            val burstIncomplete =
                prev != null &&
                    prev.speakerLabel == trigger.speakerLabel &&
                    trigger.tsMs > 0 &&
                    prev.tsMs > 0 &&
                    (trigger.tsMs - prev.tsMs) in 0..burstGapMs
            val priorSpeakers = prior.map { it.speakerLabel }.filter { it != trigger.speakerLabel }
            val firstText = prior.firstOrNull()?.text
            val mentionsNia = prior.any { it.mentionsNia } || trigger.mentionsNia

            deque.addLast(trigger)
            while (deque.size > maxPerChannel) deque.removeFirst()

            DerivedSignals(
                duplicateOfPrevHuman = duplicate,
                burstIncomplete = burstIncomplete,
                priorHumanSpeakerLabels = priorSpeakers,
                firstMessageText = firstText,
                conversationMentionsNia = mentionsNia,
            )
        }
    }
}
