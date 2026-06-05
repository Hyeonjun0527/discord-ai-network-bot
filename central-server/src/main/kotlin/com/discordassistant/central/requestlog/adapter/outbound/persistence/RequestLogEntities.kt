package com.discordassistant.central.requestlog.adapter.outbound.persistence

import com.discordassistant.central.shared.RequestState
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * requestlog 도메인 JPA 엔티티(adapter/out): 요청 원장(ai_request)·사용 로그(usage_log)·기여 로그
 * (contribution_log). 스키마는 Flyway 소유(ddl-auto=none). 엔티티명 불변 → JPQL/Flyway/엔티티스캔 영향 없음.
 * 프라이버시: 프롬프트 본문/메시지 내용은 저장하지 않는다(집계·상태만).
 */

@Entity
@Table(name = "ai_request")
class AiRequestEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var requestId: String = "",
    var guildId: Long = 0,
    var channelId: Long = 0,
    var userId: Long = 0,
    var weight: String = "LIGHT",
    var requiredBurden: String = "LIGHT",
    var providerId: Long? = null,
    @Convert(converter = RequestStateConverter::class)
    var state: RequestState = RequestState.RECEIVED,
    var failReason: String? = null,
    var createdAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "usage_log")
class UsageLogEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var userId: Long = 0,
    var requestId: String = "",
    var createdAt: Instant = Instant.EPOCH,
)

@Entity
@Table(name = "contribution_log")
class ContributionLogEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var providerId: Long = 0,
    var requestId: String = "",
    var createdAt: Instant = Instant.EPOCH,
)
