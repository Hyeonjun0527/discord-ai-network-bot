package com.discordassistant.central.onboarding.adapter.outbound.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** onboarding 도메인 JPA(adapter/out): 서버 AI 자동 온보딩 동의/실행/유저 opt-out. */

@Entity
@Table(name = "guild_onboarding_consent")
class GuildOnboardingConsentEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var actorUserId: Long? = null,
    @Column(name = "channel_whitelist") var channelWhitelist: String? = null,
    @Column(name = "message_backfill_opted_in") var messageBackfillOptedIn: Boolean = false,
    var createdAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "guild_onboarding_run")
class GuildOnboardingRunEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var channelId: Long = 0,
    var consentId: Long? = null,
    var proposalId: Long? = null,
    var channelAiId: Long? = null,
    var knowledgeSpaceId: Long? = null,
    var analysisSource: String = "heuristic",
    var status: String = "draft",
    var backfilledMessageCount: Int = 0,
    var scrubbedCount: Int = 0,
    var createdAt: Instant = Instant.EPOCH,
    var updatedAt: Instant = Instant.EPOCH,
)

/**
 * 자동 온보딩 백필 색인에서 본인 메시지를 제외하기로 한 사용자(유저 단위 opt-out).
 * `/ai-onboard-optout` 으로 누구나 본인에 한해 등록/해제할 수 있다(관리자 권한 불필요). 길드 단위로 격리된다.
 */
@Entity
@Table(name = "guild_onboarding_opt_out")
class GuildOnboardingOptOutEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var userId: Long = 0,
    var createdAt: Instant = Instant.EPOCH,
)
