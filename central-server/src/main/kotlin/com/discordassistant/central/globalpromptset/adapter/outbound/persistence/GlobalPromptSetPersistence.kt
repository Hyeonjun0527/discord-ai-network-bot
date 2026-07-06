package com.discordassistant.central.globalpromptset.adapter.outbound.persistence

import com.discordassistant.central.global.crypto.EncryptedStringConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
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

    /**
     * 기본 지정된 셋 중 id 가 가장 이른 하나(결정적 단일 선택). 단일-기본 불변식이 깨져 is_default=true 행이
     * 둘 남더라도 IncorrectResultSizeDataAccessException 대신 결정적으로 하나를 고른다 — /ask 핫패스 방어
     * (defense-in-depth). 쓰기측 직렬화([findByGuildIdForUpdate])와 함께 이중으로 불변식을 지킨다.
     */
    fun findFirstByGuildIdAndIsDefaultTrueOrderByIdAsc(guildId: Long): GlobalPromptSetEntity?

    fun findByGuildIdAndId(
        guildId: Long,
        id: Long,
    ): GlobalPromptSetEntity?

    fun existsByGuildIdAndName(
        guildId: Long,
        name: String,
    ): Boolean

    /**
     * 길드의 모든 셋을 PESSIMISTIC_WRITE 로 잠근 채 조회한다(트랜잭션 필요). setDefault 의
     * clear-all-then-set 을 직렬화해, 동시 setDefault(또는 setDefault×add) race 가 is_default=true 행을
     * 둘 남기는 것을 막는다 — 길드당 기본 1개 불변식을 DB partial 인덱스 없이 이 락으로 보장한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from GlobalPromptSetEntity s where s.guildId = :guildId")
    fun findByGuildIdForUpdate(
        @Param("guildId") guildId: Long,
    ): List<GlobalPromptSetEntity>
}
