package com.discordassistant.central.actionruntime.application

import com.discordassistant.central.actionruntime.application.port.inbound.RevocationScope
import com.discordassistant.central.actionruntime.application.port.out.ChannelMuteStorePort
import com.discordassistant.central.actionruntime.application.port.out.PendingActionPurgePort
import com.discordassistant.central.actionruntime.domain.ChannelMute
import com.discordassistant.central.actionruntime.domain.ChannelMuteLevel
import java.time.Clock
import java.time.Instant

/**
 * 채널별 mute 유스케이스(NEXA-P18-T014, application 레이어).
 *
 * 길드 단위 [GuildKillSwitchService] 보다 **세밀한** 채널 단위 정지다. 운영자/관리자가 특정 채널에서 NEXA 를 끄되,
 * 두 수준을 **분리**한다(deliverable T014):
 *  - [ChannelMuteLevel.SPEECH_ONLY]: 발화·예약·전송만 멈추고 관찰·event store append 는 계속한다(맥락 보존).
 *  - [ChannelMuteLevel.OBSERVE_AND_SPEECH]: 발화에 더해 **신규 event store append 부터 차단**한다(완전히 손 뗌).
 *
 * **acceptance(T014) — 관찰 중단은 새 event store append 부터 차단한다**: [allowsObservationAppend] 는
 * OBSERVE_AND_SPEECH 채널에서만 false 다 — 그 경계가 신규 정규화 이벤트를 적재하지 않는다. 이미 적재된 과거
 * 이벤트는 보존한다(단순 mute 로 과거를 지우지 않는다 — 삭제는 동의 철회/redaction 경로).
 *
 * mute 발동 시(OBSERVE_AND_SPEECH 든 SPEECH_ONLY 든 둘 다 발화를 막으므로) 그 채널의 **이미 생성된 pending
 * 예약·content 를 즉시 취소**한다([PendingActionPurgePort] — kill switch·동의 철회와 같은 즉시 invalidation 경로).
 * [activeMutes] 가 결정 코어의 SSOT 라, 발동 직후 다음 관찰/발화 경계가 즉시 차단을 본다(tick 대기 없음).
 *
 * 순수성 경계: application — 포트·도메인·[Clock] 만. Spring/JPA/JDA 미참조(어댑터가 와이어).
 */
class ChannelMuteService(
    private val store: ChannelMuteStorePort,
    private val purge: PendingActionPurgePort,
    private val clock: Clock,
) {
    /**
     * [channelPseudonym] 을 [level] 로 mute 한다 — 활성 집합에 즉시 반영하고, 발화가 막히므로 그 채널의 pending
     * 예약·content 를 즉시 취소한다. 취소된 pending 수를 돌려준다. [level] 이 [ChannelMuteLevel.NONE] 이면
     * [unmute] 로 위임한다(NONE 으로 mute = 해제).
     */
    fun mute(
        channelPseudonym: String,
        level: ChannelMuteLevel,
        actor: String,
        reason: String,
    ): Int {
        if (level == ChannelMuteLevel.NONE) {
            unmute(channelPseudonym, actor)
            return 0
        }
        val now = Instant.now(clock)
        // 먼저 신규 행동을 막은 다음 pending 을 청소한다(막은 뒤 청소해야 청소 중 새 pending 이 끼지 않는다).
        // 두 mute 수준 모두 발화를 막으므로 이미 생성된 pending 은 채널 범위로 취소한다(content 까지 제거).
        val scope = RevocationScope(guildPseudonym = CHANNEL_MUTE_SCOPE, channelId = channelPseudonym)
        val pending = purge.findPendingIn(scope)
        pending.forEach { purge.purge(it) }
        store.mute(
            channelPseudonym = channelPseudonym,
            level = level,
            actor = actor,
            reason = reason,
            cancelledPending = pending.size,
            at = now,
        )
        return pending.size
    }

    /** [channelPseudonym] 의 mute 를 해제한다 — 다음 [allowsSpeech]/[allowsObservationAppend] 부터 정상. 해제 audit 를 남긴다. */
    fun unmute(
        channelPseudonym: String,
        actor: String,
    ) {
        store.unmute(channelPseudonym = channelPseudonym, actor = actor, at = Instant.now(clock))
    }

    /**
     * [channelPseudonym] 에서 정책 평가·발화·예약·전송이 허용되는가. 결정/예약/전송 경계가 신규 행동 전에 호출해
     * false 면 멈춘다. SSOT(활성 집합) 조회로만 판정하므로 [mute] 직후 즉시 반영된다(acceptance — 즉시 발효).
     */
    fun allowsSpeech(channelPseudonym: String): Boolean = ChannelMute.allowsSpeech(channelPseudonym, store.activeMutes())

    /**
     * [channelPseudonym] 에서 **신규 event store append**(관찰·저장)가 허용되는가(acceptance T014). conversation
     * 수집 경계가 신규 이벤트 적재 전에 호출해 false 면 적재하지 않는다([ChannelMuteLevel.OBSERVE_AND_SPEECH] 만 false).
     */
    fun allowsObservationAppend(channelPseudonym: String): Boolean =
        ChannelMute.allowsObservationAppend(channelPseudonym, store.activeMutes())

    /** [channelPseudonym] 의 현재 mute 수준(없으면 [ChannelMuteLevel.NONE]). */
    fun levelOf(channelPseudonym: String): ChannelMuteLevel = ChannelMute.levelOf(channelPseudonym, store.activeMutes())

    private companion object {
        // 채널 mute 는 채널 식별자만으로 pending 을 좁힌다 — RevocationScope 는 guildPseudonym 을 필수로 받으므로
        // "채널 범위 취소" 를 표현하는 합성 상위 가명을 쓴다(JPA 구현이 channelId 로 좁혀 취소한다).
        const val CHANNEL_MUTE_SCOPE = "channel-mute"
    }
}
