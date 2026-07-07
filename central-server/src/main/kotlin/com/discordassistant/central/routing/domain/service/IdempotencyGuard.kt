package com.discordassistant.central.routing.domain.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** 멱등/한도 검사 결과. [ALLOW] 만 처리 진행, 나머지는 사유별로 거부한다. */
enum class IdempotencyDecision {
    ALLOW,

    /** 같은 (guild,user,prompt) 가 짧은 윈도우 안에서 허용 횟수를 초과함(빠른 중복 폭주). */
    DUPLICATE,

    /** 이 채널의 하루 AI 사용 상한을 초과함. */
    CHANNEL_DAILY_LIMIT,
}

/**
 * 멱등성/한도 가드(차수 16 #243, 개선). 두 가지를 본다:
 *  1. **동일 요청 폭주**: 같은 (guild,user,prompt) 는 짧은 윈도우 안에서 **최대 [maxDuplicatesPerWindow] 번까지
 *     허용**하고 그 다음부터 막는다. 예전엔 1번이라도 같으면 무조건 막아서, 채팅에서 같은 말을 두 번 하면
 *     "중복 요청" 시스템 문구가 새어 나왔다(사람은 같은 말을 반복해도 정상). 이제 반복 채팅은 통과시키고
 *     진짜 폭주(빠른 N+1회)만 막는다.
 *  2. **채널 하루 상한**: 한 채널은 [channelWindowMillis](기본 24h) 안에서 최대 [channelDailyLimit] 회까지만
 *     AI 를 쓴다(비용·남용 방어). 처리되는 요청만 센다(중복으로 막힌 건 세지 않는다).
 *
 * 시간원천([nowNanos])은 테스트에서 주입 가능. 순수 도메인 서비스(외부 의존 없음).
 */
@Component
class IdempotencyGuard(
    @param:Value("\${central.idempotency.window-millis:10000}") private val windowMillis: Long = 10_000,
    @param:Value("\${central.idempotency.max-duplicates-per-window:5}") private val maxDuplicatesPerWindow: Int = 5,
    @param:Value("\${central.idempotency.channel-daily-limit:50}") private val channelDailyLimit: Int = 50,
    @param:Value("\${central.idempotency.channel-window-millis:86400000}") private val channelWindowMillis: Long = 86_400_000,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    /** 윈도우 안 발생 횟수 + 윈도우 시작 시각(ns). 만료되면 리셋. */
    private data class Counter(
        val count: Int,
        val windowStartNanos: Long,
    )

    private val duplicates = ConcurrentHashMap<String, Counter>()
    private val channelDaily = ConcurrentHashMap<Long, Counter>()

    private fun duplicateKey(
        guildId: Long,
        userId: Long,
        prompt: String,
    ): String = "$guildId:$userId:${prompt.hashCode()}"

    /**
     * 처리 시작 판정. 동일요청 상한(먼저)과 채널 하루 상한(다음)을 본다. 막힌 중복은 채널 하루 카운트를
     * 소모하지 않는다(처리된 요청만 하루치에 센다).
     */
    fun begin(
        guildId: Long,
        channelId: Long,
        userId: Long,
        prompt: String,
    ): IdempotencyDecision {
        val now = nowNanos()
        val windowNanos = windowMillis * 1_000_000
        val channelNanos = channelWindowMillis * 1_000_000

        // 1) 동일 요청: 윈도우 내 최대 maxDuplicatesPerWindow 회 허용.
        if (!allowWithin(duplicates, duplicateKey(guildId, userId, prompt), now, windowNanos, maxDuplicatesPerWindow)) {
            return IdempotencyDecision.DUPLICATE
        }
        // 2) 채널 하루 상한: 처리되는 요청만 카운트.
        if (!allowWithin(channelDaily, channelId, now, channelNanos, channelDailyLimit)) {
            return IdempotencyDecision.CHANNEL_DAILY_LIMIT
        }
        return IdempotencyDecision.ALLOW
    }

    /**
     * [key] 의 윈도우 내 발생 횟수가 [limit] 미만이면 1 증가시키고 true(허용), 이미 [limit] 이상이면 그대로 두고
     * false(차단). 윈도우가 만료됐으면 1 로 리셋하고 허용. compute 로 원자적(동시 요청 이중 통과 방지).
     */
    private fun <K : Any> allowWithin(
        map: ConcurrentHashMap<K, Counter>,
        key: K,
        now: Long,
        windowNanos: Long,
        limit: Int,
    ): Boolean {
        // 가벼운 청소(만료 엔트리 제거) — 맵이 커질 때만.
        if (map.size > 10_000) map.entries.removeIf { now - it.value.windowStartNanos > windowNanos }
        var allowed = false
        map.compute(key) { _, prev ->
            when {
                prev == null || now - prev.windowStartNanos >= windowNanos -> {
                    allowed = true
                    Counter(count = 1, windowStartNanos = now)
                }
                prev.count >= limit -> {
                    allowed = false
                    prev
                }
                else -> {
                    allowed = true
                    Counter(count = prev.count + 1, windowStartNanos = prev.windowStartNanos)
                }
            }
        }
        return allowed
    }
}
