package com.discordassistant.central.platform.discord.nexa

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 채널별 최신 사람 메시지 버전을 보관한다.
 *
 * Discord snowflake 메시지 ID는 채널 안에서 단조 증가하므로 별도 카운터 대신 그대로 generation으로 사용한다. 수신
 * 스레드가 FIFO 작업을 넣기 전에 [observe]해야, 오래 실행 중인 judge도 그사이 도착한 새 장면을 즉시 감지할 수 있다.
 */
@Component
class NiaTurnGenerationTracker {
    private val latestByChannel = ConcurrentHashMap<Long, GenerationState>()

    fun observe(
        channelId: Long,
        generation: Long,
    ): Long {
        require(channelId > 0) { "channelId는 양수여야 한다: $channelId" }
        require(generation > 0) { "turn generation은 양수여야 한다: $generation" }
        val state =
            latestByChannel.compute(channelId) { _, current ->
                when {
                    current == null || generation > current.generation ->
                        GenerationState(generation = generation, valid = true)
                    else -> current
                }
            }!!
        return state.generation
    }

    /**
     * Any edit, deletion, or channel removal changes the raw scene that the current turn was judged against. Invalidate
     * that turn even when the mutated message is older than the trigger; a newer Discord snowflake restores validity.
     */
    fun invalidateCurrent(channelId: Long): Boolean {
        require(channelId > 0) { "channelId는 양수여야 한다: $channelId" }
        var invalidated = false
        latestByChannel.computeIfPresent(channelId) { _, current ->
            if (current.valid) {
                invalidated = true
                current.copy(valid = false)
            } else {
                current
            }
        }
        return invalidated
    }

    /** 아직 관찰되지 않은 채널은 기존 직접 호출·테스트 경로를 보존하기 위해 현재 장면으로 취급한다. */
    fun isLatest(
        channelId: Long,
        generation: Long,
    ): Boolean = latestByChannel[channelId]?.let { it.generation == generation && it.valid } ?: true

    /**
     * Action-runtime context keeps the Discord snowflake while valid. Invalidated state uses a negative revision token
     * so already scheduled work becomes stale without changing the externally stored target generation.
     */
    fun current(channelId: Long): Long? =
        latestByChannel[channelId]?.let { state ->
            if (state.valid) state.generation else state.generation xor Long.MIN_VALUE
        }

    private data class GenerationState(
        val generation: Long,
        val valid: Boolean,
    )
}
