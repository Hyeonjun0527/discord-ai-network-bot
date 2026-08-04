package com.discordassistant.central.participation.application

import com.discordassistant.central.global.audit.AuditLog
import com.discordassistant.central.global.crypto.ScopedPseudonymizer
import com.discordassistant.central.participation.application.catchup.NiaCatchUpStateLifecycle
import com.discordassistant.central.participation.application.port.out.NexaParticipationConsentPort
import com.discordassistant.central.participation.application.port.out.NexaParticipationFlagPort
import com.discordassistant.central.participation.application.port.out.ShadowModeStorePort
import com.discordassistant.central.participation.domain.model.config.NexaParticipationGate
import com.discordassistant.central.participation.domain.model.config.ParticipationLane
import com.discordassistant.central.participation.domain.model.shadow.ShadowMode
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * NEXA participation feature flag 해석 application 서비스(NEXA-P15-T002).
 *
 * raw Discord (guildId, channelId)에 대해 **유효 [ShadowMode]** 를 돌려준다. 길드 lane 은
 * [ShadowModeStorePort](P09-T007), 채널 override·제외는 [NexaParticipationFlagPort](T002)에서 읽어
 * 순수 도메인 [NexaParticipationGate] 로 합성한다. raw guildId 는 [ScopedPseudonymizer] 로 가명화해 저장 키와
 * 일치시킨다(원문 비저장).
 *
 * **acceptance(T002) — 기본 OFF(legacy)로 기존 동작 보존**: 아무 설정도 없으면 [effectiveMode] = [ShadowMode.OFF],
 * [isNexaActive] = false. 즉 P15 파이프라인(T004~T006)은 이 서비스가 활성이라고 답할 때만 동작한다 — flag 미설정
 * 길드/채널은 기존 channelai 자동응답만 돈다(회귀 0).
 *
 * **글로벌 기본 lane([globalDefaultLane])**: 길드 행이 없거나 명시 OFF 일 때 적용할 lane. `central.nexa.participation
 * .global-default-lane`(기본 OFF) 로 주입한다. prod 배포가 SHADOW 로 켜면 모든(+미래) 길드가 길드별 설정 없이도
 * SHADOW_PREDICT(평가·기록만, 전송 hard block)로 돈다. 길드가 명시적으로 OFF 아닌 값을 가지면 그게 우선.
 *
 * 순수성: application — 포트·도메인·global 가명화만. JDA/routing/GLM 미참조.
 */
@Service
class NexaParticipationFlagService(
    private val shadowModeStore: ShadowModeStorePort,
    private val flagPort: NexaParticipationFlagPort,
    private val consentPort: NexaParticipationConsentPort,
    @Value("\${central.nexa.participation.global-default-lane:OFF}") globalDefaultLaneName: String,
    private val audit: AuditLog = AuditLog(),
    private val catchUpStateLifecycle: NiaCatchUpStateLifecycle,
) {
    /** 길드 행이 없거나 명시 OFF 일 때 적용할 글로벌 기본 lane. 잘못된 값이면 안전하게 [ParticipationLane.LEGACY](OFF). */
    private val globalDefaultLane: ParticipationLane =
        runCatching { ParticipationLane.valueOf(globalDefaultLaneName.trim().uppercase()) }
            .getOrDefault(ParticipationLane.LEGACY)

    /** raw (guildId, channelId)의 유효 [ShadowMode](길드 lane + 채널 override + 제외 합성). 기본 OFF. */
    fun effectiveMode(
        guildId: Long,
        channelId: Long,
    ): ShadowMode {
        val pseudonym = guildPseudonym(guildId)
        val storedMode = shadowModeStore.currentMode(pseudonym)
        // 길드 행 없음/명시 OFF → 글로벌 기본 lane 으로 대체. 길드가 OFF 아닌 값이면 그게 우선.
        val guildLane = if (storedMode == ShadowMode.OFF) globalDefaultLane else ParticipationLane.fromShadowMode(storedMode)
        return NexaParticipationGate.resolve(
            channelId = channelId,
            guildLane = guildLane,
            channelOverride = flagPort.channelOverride(pseudonym, channelId),
            excludedChannelIds = flagPort.excludedChannelIds(pseudonym),
        )
    }

    /**
     * 이 (guildId, channelId)에서 NEXA participation 이 활성인가(정책 평가 단계 이상). false 면 기존 channelai
     * 자동응답 경로만 동작한다. P15 파이프라인 진입 가드.
     */
    fun isNexaActive(
        guildId: Long,
        channelId: Long,
    ): Boolean = effectiveMode(guildId, channelId).evaluatesPolicy

    /** 이 (guildId, channelId)에서 실제 Discord 전송이 허용되는가(CANARY/LIVE). shadow 단계는 false(전송 차단). */
    fun allowsRealSend(
        guildId: Long,
        channelId: Long,
    ): Boolean = effectiveMode(guildId, channelId).allowsRealSend

    /** 이 채널에서 NEXA participation 을 실제 발화(LIVE)로 켠다. 채널 kill-switch 가 있으면 먼저 해제한다. */
    @Transactional
    fun enableChannelLive(
        guildId: Long,
        channelId: Long,
        actorId: Long? = null,
        source: String = SOURCE_MANUAL,
    ) {
        val pseudonym = guildPseudonym(guildId)
        // 이전 disable/cleanup 이 부분 실패했더라도, 새 LIVE 범위에 오래된 CATCH_UP 작업을 넘기지 않는다.
        catchUpStateLifecycle.clearChannel(guildId = guildId, channelId = channelId)
        // 동의를 먼저 기록한다. 이후 flag 저장이 실패해도 발화는 활성화되지 않는 안전 방향이다.
        consentPort.activateMemberChannel(guildId = guildId, channelId = channelId, actorId = actorId)
        flagPort.setChannelExcluded(pseudonym, channelId, false)
        flagPort.setChannelOverride(pseudonym, channelId, ParticipationLane.LIVE)
        recordChannelAudit(
            action = "nexa_participation_channel_enabled",
            guildId = guildId,
            channelId = channelId,
            actorId = actorId,
            source = source,
            mode = ShadowMode.LIVE.name,
        )
    }

    /** 이 채널에서 NEXA participation 을 명시적으로 끈다. 길드/글로벌 LIVE 보다 kill-switch 가 우선한다. */
    @Transactional
    fun disableChannel(
        guildId: Long,
        channelId: Long,
        actorId: Long? = null,
        source: String = SOURCE_MANUAL,
    ) {
        val pseudonym = guildPseudonym(guildId)
        // 제외(kill-switch)를 먼저 심는다. resolve() 는 제외를 최우선으로 OFF 처리하므로, override 를 먼저 지워
        // 행이 삭제되는 순간 guild/global LIVE 로 fallback 해 그 채널에서 발화가 새는 창(race)을 없앤다.
        flagPort.setChannelExcluded(pseudonym, channelId, true)
        flagPort.setChannelOverride(pseudonym, channelId, null)
        catchUpStateLifecycle.clearChannel(guildId = guildId, channelId = channelId)
        consentPort.deactivateMemberChannel(guildId = guildId, channelId = channelId)
        recordChannelAudit(
            action = "nexa_participation_channel_disabled",
            guildId = guildId,
            channelId = channelId,
            actorId = actorId,
            source = source,
            mode = ShadowMode.OFF.name,
        )
    }

    /** Discord 채널 삭제/정합성 복구 경로에서 stale participation override·kill-switch 를 제거한다. */
    @Transactional
    fun cleanupChannel(
        guildId: Long,
        channelId: Long,
    ) {
        catchUpStateLifecycle.clearChannel(guildId = guildId, channelId = channelId)
        consentPort.clearChannel(guildId = guildId, channelId = channelId)
        flagPort.clearChannel(guildPseudonym(guildId), channelId)
    }

    /**
     * 봇이 서버에서 제거될 때 이 길드의 모든 채널 participation override/kill-switch 를 제거한다. 남겨두면 같은
     * guildId 로 재입장 시(가명 동일) 관리자가 다시 켜지 않았는데도 stale LIVE 채널에서 니아가 발화할 수 있다.
     */
    @Transactional
    fun cleanupGuild(guildId: Long) {
        catchUpStateLifecycle.clearGuild(guildId)
        consentPort.revokeGuild(guildId)
        flagPort.clearGuild(guildPseudonym(guildId))
    }

    /** raw guildId → 저장 키 가명(MEMORY purpose, 길드 스코프). ShadowMode store 와 같은 가명 공간. */
    private fun guildPseudonym(guildId: Long): String =
        ScopedPseudonymizer.pseudonymize(ScopedPseudonymizer.Purpose.MEMORY, guildId = guildId, snowflake = guildId)

    private fun recordChannelAudit(
        action: String,
        guildId: Long,
        channelId: Long,
        actorId: Long?,
        source: String,
        mode: String,
    ) {
        audit.record(
            action = action,
            actor = actorId?.let { "admin:$it" } ?: "system",
            target = "guild:$guildId",
            detail = "channel:$channelId source:${source.ifBlank { SOURCE_MANUAL }} mode:$mode",
        )
    }

    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_NIA_SETUP = "nia_setup"
        const val SOURCE_GUILD_ADMIN_TOGGLE = "guild_admin_toggle"
    }
}
