package com.discordassistant.central.socialmemory.adapter.outbound.ainetwork

import com.discordassistant.central.ainetwork.adapter.outbound.persistence.UserAffinityRepository
import com.discordassistant.central.ainetwork.domain.model.AffinityStage
import com.discordassistant.central.socialmemory.application.port.out.NiaAffinityBridgePort
import com.discordassistant.central.socialmemory.application.port.out.NiaAffinityView
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * [NiaAffinityBridgePort] 의 **읽기 전용** JPA 구현 어댑터(NEXA-P06-T020, ADR 0010 BRIDGE 전략).
 *
 * ainetwork 의 `user_affinity` 를 [UserAffinityRepository] 로 **읽기만** 하고 명시적으로 [NiaAffinityView] 로
 * 매핑한다 — score/stage 를 복제 저장하지 않는다. 이 어댑터에는 **쓰기 경로가 없다**(read-modify-write·save 호출
 * 부재) — socialmemory 가 호감도에 중복 side effect 를 만들 경로가 타입·코드 수준에서 존재하지 않는다(acceptance
 * T021 중복 쓰기 방지).
 *
 * 경계(ADR 0010): socialmemory **domain** 은 ainetwork 엔티티를 import 하지 않는다(NexaArchitectureTest 순수성).
 * ainetwork 참조는 **이 adapter 안에서만** 일어나며, domain↔domain 직접 결합이 아니라 명시적 매핑 변환이다.
 */
@Component
class JpaNiaAffinityBridge(
    private val affinities: UserAffinityRepository,
) : NiaAffinityBridgePort {
    @Transactional(readOnly = true)
    override fun affinityView(userId: Long): NiaAffinityView? {
        val affinity = affinities.findByUserId(userId) ?: return null
        val ordinal = affinity.stageOrdinal.coerceIn(0, MAX_STAGE_ORDINAL)
        return NiaAffinityView(
            stageOrdinal = ordinal,
            // 전역 단계 서수를 [0,1] 로 정규화 — score 원본을 복제하지 않고 매핑만 노출한다.
            normalizedAffinity = if (MAX_STAGE_ORDINAL == 0) 0.0 else ordinal.toDouble() / MAX_STAGE_ORDINAL.toDouble(),
        )
    }

    private companion object {
        /** ainetwork 호감도 단계 최대 서수(STRANGER..BEST_FRIEND). 정규화 분모. */
        val MAX_STAGE_ORDINAL = AffinityStage.entries.size - 1
    }
}
