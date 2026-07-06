package com.discordassistant.central.channelai.application

import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import com.discordassistant.central.guild.application.AutoRespondChannelPolicyView
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * "AI 채팅 채널"(멘션 없이 모든 메시지에 자동 응답하는 채널) 레지스트리.
 *
 * **성능(핫패스)**: `DiscordBot.Listener.onMessageReceived` 는 모든 길드의 모든 메시지마다 호출된다 →
 * 메시지당 DB 조회를 절대 하면 안 된다. 자동응답 채널 집합을 **길드별 인메모리 캐시**(lazy 로드, 길드당 1회 조회)로
 * 보관해 [isAutoRespond] 를 O(1) 로 답한다. 플래그 변경([setAutoRespond])·채널 삭제([invalidateChannel]/
 * [invalidateGuild]) 시 해당 길드 캐시를 무효화해 다음 접근에서 다시 로드한다.
 *
 * 플래그는 `channel_ai.auto_respond` 컬럼이 SSOT. [setAutoRespond] 는 프로필이 없으면 니아 기본으로 생성한 뒤
 * 플래그를 세팅한다([ChannelAiProfileService.set] 과 같은 페르소나로 응답이 일관되도록).
 */
@Service
class AutoRespondChannelRegistry(
    private val channelAis: ChannelAiRepository,
    private val behaviorVersions: AiBehaviorVersionRepository,
    private val channelProfiles: ChannelAiProfileService,
) : AutoRespondChannelPolicyView {
    /** guildId → 자동응답 채널 id 집합. 값이 없으면 미로드(다음 접근에서 lazy 로드). */
    private val cache = ConcurrentHashMap<Long, Set<Long>>()

    /** 핫패스: 이 채널이 자동응답 대상인지(O(1), 길드 캐시 lazy 로드). */
    fun isAutoRespond(
        guildId: Long,
        channelId: Long,
    ): Boolean = channelsFor(guildId).contains(channelId)

    /** 정책 감사용 읽기 포트. 캐시된 자동응답 채널 집합을 외부 변경 가능 상태로 노출하지 않는다. */
    override fun autoRespondChannelIds(guildId: Long): Set<Long> = channelsFor(guildId)

    /**
     * 자동응답 플래그를 켜고/끈다. 프로필이 없고 [on] 이면 니아 기본 프로필을 먼저 생성한다(페르소나 일관).
     * 변경 후 길드 캐시를 무효화한다(다음 접근에서 재로드 → 즉시 반영).
     */
    @Transactional
    fun setAutoRespond(
        guildId: Long,
        channelId: Long,
        on: Boolean,
        actorId: Long? = null,
    ) {
        val existing = channelAis.findByGuildIdAndChannelId(guildId, channelId)
        var entity =
            existing ?: run {
                if (!on) return // 끄려는데 프로필이 없으면 만들 것도 없음
                // 프로필이 없으면 니아 기본으로 생성(set 이 channel_ai 행 + behavior version 을 만든다).
                ensureNiaProfile(guildId, channelId, actorId)
            }
        if (on && entity.needsActiveBehaviorRepair()) {
            // behavior version 이 없거나 dangling 일 때 재생성한다. 단 니아 기본값으로 덮어쓰면 관리자가 지정한
            // 커스텀 채널 이름/아바타가 함께 초기화되므로, 복구 경로는 이 채널의 기존 이름/아바타를 그대로 넘긴다.
            channelProfiles.set(
                guildId = guildId,
                channelId = channelId,
                displayName = entity.displayName,
                avatarUrl = entity.avatarUrl,
                actorId = actorId,
            )
            entity = channelAis.findByGuildIdAndChannelId(guildId, channelId)
                ?: error("채널 AI 프로필 복구 직후 조회 실패(guild=$guildId, channel=$channelId)")
        }
        if (entity.autoRespond != on) {
            entity.autoRespond = on
            entity.updatedAt = Instant.now()
            channelAis.save(entity)
        }
        evictAfterCommit(guildId)
    }

    /** 채널 삭제 정리 경로에서 호출 — 해당 길드 캐시 무효화(다음 접근에서 재로드). */
    fun invalidateChannel(
        guildId: Long,
        @Suppress("UNUSED_PARAMETER") channelId: Long,
    ) {
        evictAfterCommit(guildId)
    }

    /** 길드 정리 경로에서 호출 — 길드 캐시 무효화. */
    fun invalidateGuild(guildId: Long) {
        evictAfterCommit(guildId)
    }

    /**
     * 길드 캐시를 무효화하되 **커밋 이후 한 번 더** 무효화한다.
     *
     * 열린 write 트랜잭션 안에서만 evict 하면, 동시 핫패스 리더가 아직 커밋되지 않은(롤백 가능한) 상태로
     * 캐시를 재적재할 수 있고 그 pre-commit stale 은 이후 다시 evict 되지 않아 **영구 잔존**한다(원인 버그).
     * 그래서 활성 트랜잭션이 있으면 커밋 직후 [TransactionSynchronization.afterCommit] 으로 재-evict 를 예약해,
     * 커밋 전 창에서 리더가 되살린 stale 을 반드시 걷어낸다. 즉시 evict 는 같은 트랜잭션의 후속 조회·비트랜잭션
     * 정리 경로(트랜잭션 없음)를 위해 유지한다.
     */
    private fun evictAfterCommit(guildId: Long) {
        cache.remove(guildId)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        cache.remove(guildId)
                    }
                },
            )
        }
    }

    private fun channelsFor(guildId: Long): Set<Long> =
        cache.computeIfAbsent(guildId) { gid ->
            channelAis.findByGuildIdAndAutoRespondTrue(gid).map(ChannelAiEntity::channelId).toSet()
        }

    private fun ensureNiaProfile(
        guildId: Long,
        channelId: Long,
        actorId: Long?,
    ): ChannelAiEntity {
        channelProfiles.set(
            guildId = guildId,
            channelId = channelId,
            displayName = DEFAULT_NIA_DISPLAY_NAME,
            avatarUrl = null,
            actorId = actorId,
        )
        return channelAis.findByGuildIdAndChannelId(guildId, channelId)
            ?: error("채널 AI 프로필 생성 직후 조회 실패(guild=$guildId, channel=$channelId)")
    }

    private fun ChannelAiEntity.needsActiveBehaviorRepair(): Boolean {
        val activeBehaviorId = activeBehaviorVersionId ?: return true
        return behaviorVersions.findByChannelAiIdAndId(id, activeBehaviorId) == null
    }

    companion object {
        private const val DEFAULT_NIA_DISPLAY_NAME = "니아"

        /** `.` 으로 시작하는 메시지(카미봇 컨벤션)·빈 내용은 무시. trim 후 비어있지 않고 `.` 으로 시작 안 하면 응답. */
        fun shouldRespond(rawContent: String): Boolean {
            val trimmed = rawContent.trim()
            return trimmed.isNotBlank() && !trimmed.startsWith(".")
        }
    }
}
