package com.discordassistant.central.quota.adapter.outbound.persistence

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * quota 도메인 JPA(adapter/out): 사용자 차단 목록(blocklist). 스키마는 Flyway 소유(ddl-auto=none).
 * BlocklistService 가 isBlocked 핫패스를 인메모리 캐시로, 영속은 이 테이블로 유지한다(재시작 보존).
 */

@Entity
@Table(name = "blocklist")
class BlocklistEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    var guildId: Long = 0,
    var userId: Long = 0,
    var blockedBy: Long? = null,
    var createdAt: Instant = Instant.EPOCH,
)

interface BlocklistRepository : JpaRepository<BlocklistEntity, Long> {
    fun findByGuildIdAndUserId(
        guildId: Long,
        userId: Long,
    ): BlocklistEntity?

    fun findByGuildId(guildId: Long): List<BlocklistEntity>

    fun deleteByGuildIdAndUserId(
        guildId: Long,
        userId: Long,
    )

    fun deleteByGuildId(guildId: Long)
}
