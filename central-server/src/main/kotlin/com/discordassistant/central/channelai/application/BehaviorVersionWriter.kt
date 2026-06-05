package com.discordassistant.central.channelai.application

import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionEntity
import com.discordassistant.central.channelai.adapter.outbound.persistence.AiBehaviorVersionRepository
import com.discordassistant.central.channelai.adapter.outbound.persistence.ChannelAiRepository
import org.springframework.stereotype.Component

/**
 * 채널 AI behavior version 채번(`MAX(version)+1`)+insert 를 동시성 안전하게 수행하는 협력자.
 *
 * **@Transactional 미부여(의도적)**: 별 @Component 빈으로 빼도 새 TX 가 열리지 않는다.
 * `channelAis.findByIdForUpdate`(PESSIMISTIC_WRITE)·`versions.saveAndFlush` 는 호출자(파사드의
 * @Transactional write 라이프사이클)의 활성 트랜잭션에 그대로 합류하므로, 추출 전 같은 빈 내부 호출과
 * 락·재시도·원자성이 1바이트도 다르지 않다. 여기에 @Transactional/REQUIRES_NEW 를 붙이면 새 TX 가 열려
 * 락 점유 시점과 재시도 의미가 깨진다 — 절대 부여 금지.
 */
@Component
class BehaviorVersionWriter(
    private val channelAis: ChannelAiRepository,
    private val versions: AiBehaviorVersionRepository,
) {
    /**
     * behavior version 채번(`MAX(version)+1`)과 insert 를 동시성 안전하게 수행한다.
     *
     * 1) 채널 AI 행을 PESSIMISTIC_WRITE 로 잠가(`findByIdForUpdate`) 같은 채널의 채번을 직렬화한다 —
     *    동시 두 요청이 같은 version 으로 insert 하다 `uk_ai_behavior_version` 유니크를 깨고
     *    트랜잭션 전체가 롤백되는 race(#2)를 막는다.
     * 2) 락이 보장되지 않는 환경(테스트용 인메모리 DB 등)을 위해, 유니크 위반
     *    ([DataIntegrityViolationException])이 나면 version 을 재조회해 최대 [MAX_VERSION_RETRIES] 회
     *    재시도하는 낙관적 보호막을 덧댄다. 채번+behavior insert 만 재시도 범위에 두므로
     *    호출처의 다른 부작용(channelAi.save 등)은 중복되지 않는다.
     *
     * @param channelAiId 채번 대상 채널 AI id (이미 저장/flush 된 행이어야 한다).
     * @param build 확정된 version 으로 새 [AiBehaviorVersionEntity] 를 만드는 빌더.
     */
    fun saveNextBehaviorVersion(
        channelAiId: Long,
        build: (Int) -> AiBehaviorVersionEntity,
    ): AiBehaviorVersionEntity {
        var attempt = 0
        while (true) {
            // 채널 AI 행 락으로 같은 채널의 채번을 직렬화한다(트랜잭션 안에서만 유효).
            channelAis.findByIdForUpdate(channelAiId)
            val nextVersion = (versions.findTopByChannelAiIdOrderByVersionDesc(channelAiId)?.version ?: 0) + 1
            try {
                return versions.saveAndFlush(build(nextVersion))
            } catch (ex: org.springframework.dao.DataIntegrityViolationException) {
                attempt += 1
                if (attempt >= MAX_VERSION_RETRIES) {
                    throw IllegalStateException(
                        "채널 AI 행동 버전 채번이 동시 변경과 계속 충돌했어요. 잠시 후 다시 시도해 주세요.",
                        ex,
                    )
                }
            }
        }
    }

    private companion object {
        const val MAX_VERSION_RETRIES = 5
    }
}
