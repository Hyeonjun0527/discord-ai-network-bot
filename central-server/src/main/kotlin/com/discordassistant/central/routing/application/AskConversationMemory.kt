package com.discordassistant.central.routing.application

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * /질문 전용 단기 멀티턴 대화 기억(인메모리). 채널+유저 스코프로 최근 [maxTurns] turn 을 링버퍼로 들고,
 * 마지막 활동 후 [ttlMillis] 가 지나면 만료한다. 단일 컨테이너 운영(인메모리 OK)이라 DB/로그에 남기지 않는다.
 *
 * 프라이버시: 채널+유저 단위로 격리(다른 유저·채널의 맥락이 섞이지 않음)·짧은 윈도우·TTL·인메모리(영속·로깅 없음).
 * 채널AI/NEXA 의 이력과 **분리된 /질문 전용** 스토어다 — 그쪽 동작에 영향을 주지 않는다.
 *
 * OpenAI Responses input 앞에 붙일 [CloudTurn] 리스트(role user/assistant, 시간순)를 반환한다.
 */
@Component
class AskConversationMemory(
    private val maxTurns: Int = DEFAULT_MAX_TURNS,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Spring 은 이 no-arg 생성자로 @Component 빈을 만든다(기본값 사용). 테스트는 위 주 생성자로 clock/사이즈를 주입한다.
    @org.springframework.beans.factory.annotation.Autowired
    constructor() : this(DEFAULT_MAX_TURNS, DEFAULT_TTL_MILLIS, System::currentTimeMillis)

    private data class Conversation(
        val turns: ArrayDeque<CloudTurn> = ArrayDeque(),
        var lastActiveAt: Long = 0L,
    )

    // key = "channelId:userId" — 채널+유저 격리.
    private val conversations = ConcurrentHashMap<String, Conversation>()

    /**
     * 이 채널+유저의 최근 대화 히스토리(시간순). 만료됐으면 비우고 빈 리스트를 돌려준다.
     * OpenAI 호출 시 이번 질문보다 **앞에** 붙여 멀티턴 맥락("방금 뭐라고 했지?")을 제공한다.
     */
    fun history(
        channelId: Long,
        userId: Long,
    ): List<CloudTurn> {
        val key = key(channelId, userId)
        val convo = conversations[key] ?: return emptyList()
        synchronized(convo) {
            if (isExpired(convo)) {
                conversations.remove(key)
                return emptyList()
            }
            return convo.turns.toList()
        }
    }

    /**
     * 이번 한 turn(사용자 질문 [userMessage] + AI 답변 [assistantMessage])을 기억에 append 한다.
     * 링버퍼라 [maxTurns] 를 넘는 가장 오래된 turn 부터 버린다. 답변/질문이 비면 저장하지 않는다.
     */
    fun append(
        channelId: Long,
        userId: Long,
        userMessage: String,
        assistantMessage: String,
    ) {
        if (userMessage.isBlank() || assistantMessage.isBlank()) return
        val key = key(channelId, userId)
        val now = clock()
        val convo =
            conversations.compute(key) { _, existing ->
                val c = existing?.takeUnless { isExpired(it) } ?: Conversation()
                c.lastActiveAt = now
                c
            }!!
        synchronized(convo) {
            convo.lastActiveAt = now
            convo.turns.addLast(CloudTurn("user", userMessage))
            convo.turns.addLast(CloudTurn("assistant", assistantMessage))
            // 링버퍼: 최근 maxTurns turn(=maxTurns*2 메시지)만 유지.
            while (convo.turns.size > maxTurns * 2) convo.turns.removeFirst()
        }
    }

    private fun isExpired(convo: Conversation): Boolean = clock() - convo.lastActiveAt > ttlMillis

    private fun key(
        channelId: Long,
        userId: Long,
    ): String = "$channelId:$userId"

    companion object {
        // 최근 10 turn(질문+답 각 1) — 단기 맥락만, 토큰 폭주 방지.
        const val DEFAULT_MAX_TURNS = 10

        // 30분 — 짧은 대화 세션만 기억(프라이버시).
        const val DEFAULT_TTL_MILLIS = 30L * 60L * 1000L
    }
}
