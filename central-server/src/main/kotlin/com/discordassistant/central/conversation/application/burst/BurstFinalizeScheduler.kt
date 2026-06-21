package com.discordassistant.central.conversation.application.burst

import com.discordassistant.central.conversation.domain.event.BurstFinalized
import com.discordassistant.central.conversation.domain.event.BurstTerminationReason
import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import java.time.Clock
import java.time.Instant

/**
 * 버스트 finalize 스케줄러(NEXA-P04-T019, application). OPEN 버스트마다 **deadline**(마지막 조각 + gap)을 deadline
 * queue 에 등록하고, 주입된 [Clock] 기준으로 deadline 이 지난 버스트를 finalize 해 [BurstFinalized] 를 만든다.
 *
 * application 레이어 소속 — 순수 도메인 타입과 표준 [Clock]/[Instant] 만 본다(Spring/JPA/JDA 미참조). [Clock] 을 주입
 * 받아 **실제 sleep 없이** 시간 이동 테스트가 가능하다.
 *
 * **acceptance(T019) — sleep 없는 시간 이동·재시작 deadline 복구**:
 * - [sweep] 은 wall-clock 을 직접 읽지 않고 [Clock.instant] 만 본다 — 테스트는 [Clock.fixed] 를 갈아끼워 시간을
 *   "이동" 시킨다(Thread.sleep 불필요).
 * - 스케줄러 상태는 (burstId → deadline, burst) 인메모리 큐다. 재시작 후에는 영속 OPEN 버스트 read model 에서
 *   [restore] 로 deadline 을 **재계산해 복구**한다 — deadline 은 버스트의 lastFragmentAt 에서 결정론적으로 파생되므로
 *   재시작 전후 동일하다(저장된 타이머가 아니라 데이터에서 복구).
 */
class BurstFinalizeScheduler(
    private val clock: Clock,
    private val segmentationVersion: Int,
) {
    private data class Entry(
        val deadline: Instant,
        val burst: UtteranceBurst,
    )

    private val pending = mutableMapOf<BurstId, Entry>()

    /**
     * OPEN [burst] 의 deadline([deadline] = 보통 lastFragmentAt + gap)을 큐에 등록/갱신한다. 같은 burstId 재등록은
     * 최신 [burst]·deadline 으로 덮어쓴다(append 로 deadline 이 뒤로 밀리는 정상 흐름).
     */
    fun schedule(
        burst: UtteranceBurst,
        deadline: Instant,
    ) {
        pending[burst.burstId] = Entry(deadline = deadline, burst = burst)
    }

    /**
     * 재시작 복구(acceptance T019): 영속 OPEN 버스트 read model 에서 읽은 [openBursts] 와 그 deadline 계산기
     * [deadlineOf] 로 큐를 재구성한다. deadline 은 데이터에서 재계산되므로 재시작 전후 동일하다(저장된 타이머 불필요).
     */
    fun restore(
        openBursts: List<UtteranceBurst>,
        deadlineOf: (UtteranceBurst) -> Instant,
    ) {
        for (burst in openBursts) {
            pending[burst.burstId] = Entry(deadline = deadlineOf(burst), burst = burst)
        }
    }

    /**
     * 현재 [Clock] 시각 기준으로 deadline 이 지난(<= now) 버스트를 모두 finalize 해 [BurstFinalized](종료 이유
     * [BurstTerminationReason.STREAM_END])를 돌려주고 큐에서 제거한다. deadline 이 아직 안 지난 버스트는 그대로 둔다.
     *
     * deadline 순(같으면 burstId 순)으로 결정론적 정렬해 돌려준다 — 같은 시각·같은 큐 상태면 항상 같은 순서·집합이다.
     */
    fun sweep(): List<BurstFinalized> {
        val now = clock.instant()
        val due =
            pending.values
                .filter { !it.deadline.isAfter(now) }
                .sortedWith(compareBy({ it.deadline }, { it.burst.burstId.value }))
        for (entry in due) {
            pending.remove(entry.burst.burstId)
        }
        return due.map {
            BurstFinalized.fromBurst(
                burst = it.burst.finalize(),
                segmentationVersion = segmentationVersion,
                terminationReason = BurstTerminationReason.STREAM_END,
            )
        }
    }

    /** 아직 finalize 되지 않은(deadline 미도래) 등록 버스트 수 — 운영 가시성·테스트 검증용. */
    fun pendingCount(): Int = pending.size
}
