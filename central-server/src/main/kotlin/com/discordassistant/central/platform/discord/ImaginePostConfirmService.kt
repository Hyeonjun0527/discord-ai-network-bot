package com.discordassistant.central.platform.discord

import jakarta.annotation.PostConstruct
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * /그림(imagine) 결과를 공개 채널에 올리기 전 **본인 확인 게이트** 설정. **DB 영속 + 인메모리 캐시**.
 *
 * 기본값은 ON(완성 후 ephemeral 미리보기 + 게시 확인 버튼). "다음부터 안 묻기"를 누른 유저만 OFF 로 행을 저장한다
 * (대다수가 기본 ON 이라 테이블/캐시가 작게 유지됨). isEnabled 는 /그림 핫패스라 캐시로 O(1).
 * 행 존재 = OFF, 행 부재 = ON. BlocklistService 와 동일한 DB+캐시 적재 패턴.
 */
@Entity
@Table(name = "imagine_post_confirm_off")
class ImaginePostConfirmOffEntity(
    @Id var userId: Long = 0,
    var updatedAt: Instant = Instant.EPOCH,
)

interface ImaginePostConfirmOffRepository : JpaRepository<ImaginePostConfirmOffEntity, Long>

@Service
class ImaginePostConfirmService(
    private val repo: ImaginePostConfirmOffRepository,
) {
    // 게이트를 끈(OFF) 유저 집합. 행이 곧 OFF 이므로 이 집합에 없으면 기본 ON.
    private val off = ConcurrentHashMap.newKeySet<Long>()
    private val log = LoggerFactory.getLogger(ImaginePostConfirmService::class.java)

    @PostConstruct
    fun load() {
        try {
            repo.findAll().forEach { off.add(it.userId) }
        } catch (e: DataAccessException) {
            // 적재 실패 = 모든 유저가 게이트 ON 으로 시작한다(안전한 쪽 — 실수 공개를 막는 게 이 기능의 목적).
            // 조용히 넘기지 않고 크게 남긴다(예외 원칙 3).
            log.error("imagine 게시 확인 설정 적재 실패 — 모두 기본 ON 으로 동작합니다(운영 확인 필요)", e)
        }
    }

    /** 이 유저에게 게시 확인 게이트가 켜져 있는지(=ephemeral 미리보기 후 버튼으로 확인). 캐시에 OFF 로 없으면 true. */
    fun isEnabled(userId: Long): Boolean = userId !in off

    @Transactional
    fun setEnabled(
        userId: Long,
        enabled: Boolean,
    ) {
        if (enabled) {
            if (repo.existsById(userId)) repo.deleteById(userId) // ON = 기본값 → 행 제거(없으면 그대로)
            off.remove(userId)
        } else {
            repo.save(ImaginePostConfirmOffEntity(userId = userId, updatedAt = Instant.now())) // OFF = 행 저장(upsert)
            off.add(userId)
        }
    }
}
