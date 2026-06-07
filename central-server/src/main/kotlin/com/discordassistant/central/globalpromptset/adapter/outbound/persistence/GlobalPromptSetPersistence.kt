package com.discordassistant.central.globalpromptset.adapter.outbound.persistence

import com.discordassistant.central.global.crypto.EncryptedStringConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * 길드별 전역 프롬프트셋(서버 전체 기본 AI 성격) JPA 엔티티.
 *
 * 관리자가 작성한 셋만 저장한다. NEXA 기본 정체성(니아)은 코드 상수([com.discordassistant.central.shared.NexaIdentity])
 * 라 이 테이블에 행이 없다 — 기본(is_default=true) 셋이 하나도 없으면 니아가 적용된다.
 * content 는 at-rest 암호화([EncryptedStringConverter]) 된다.
 */
@Entity
@Table(name = "global_prompt_set")
class GlobalPromptSetEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "guild_id") var guildId: Long = 0,
    @Column(name = "name") var name: String = "",
    @Convert(converter = EncryptedStringConverter::class) @Column(name = "content") var content: String = "",
    @Column(name = "is_default") var isDefault: Boolean = false,
    @Column(name = "created_by") var createdBy: Long? = null,
    @Column(name = "created_at") var createdAt: Instant = Instant.EPOCH,
)

interface GlobalPromptSetRepository : JpaRepository<GlobalPromptSetEntity, Long> {
    fun findByGuildIdOrderByIdAsc(guildId: Long): List<GlobalPromptSetEntity>

    fun findByGuildIdAndIsDefaultTrue(guildId: Long): GlobalPromptSetEntity?

    fun findByGuildIdAndId(
        guildId: Long,
        id: Long,
    ): GlobalPromptSetEntity?

    fun existsByGuildIdAndName(
        guildId: Long,
        name: String,
    ): Boolean
}
