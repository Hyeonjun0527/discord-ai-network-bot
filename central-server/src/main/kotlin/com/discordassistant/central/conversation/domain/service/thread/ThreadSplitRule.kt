package com.discordassistant.central.conversation.domain.service.thread

import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.thread.ConversationThreadId

/**
 * 스레드 split 교정 규칙(NEXA-P05-T012, 순수 함수). 한 스레드에 잘못 합쳐진 burst 들을 **새 projection version**
 * 에서 별도 스레드로 분리하는 [ThreadSplitDecision] 을 만든다.
 *
 * 잘못된 merge 가 관찰되면(예: adjacency 약신호로 두 대화가 한 스레드로 묶였는데 실제로는 무관) event store 를
 * 재생하거나 과거 projection 을 임의로 mutation 하지 않고, 옮길 burst 와 근거만 담은 correction 결정을 남긴다 —
 * 새 version 에서 적용된다(acceptance T012).
 *
 * **split 대상 id(KISS·결정론)**: 분리되어 나간 burst 묶음은 **원래 스레드의 위치 + 다음 ordinal** 로 새
 * [ConversationThreadId] 를 받는다. 같은 입력 replay 면 같은 새 id 가 나오도록 [splitOrdinal] 을 호출자가 결정론적으로 부여한다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 상태 없음(모든 입력을 인자로 받는다).
 */
object ThreadSplitRule {
    /**
     * [original] 스레드에서 [misassignedBursts] 를 분리한 split 교정 결정을 만든다.
     *
     * @param original 잘못된 merge 가 일어난 원래 스레드 id(분리 후에도 유지 — 기존 id 보존).
     * @param splitThreadId 분리되어 나가는 burst 묶음의 새 스레드 id(결정론적으로 호출자가 부여).
     * @param misassignedBursts 새 스레드로 옮길 burst 집합(비어 있으면 거부 — 무의미한 split).
     * @param ruleVersion 이 교정을 만든 규칙 버전(provenance).
     */
    fun split(
        original: ConversationThreadId,
        splitThreadId: ConversationThreadId,
        misassignedBursts: Set<BurstId>,
        ruleVersion: String,
    ): ThreadSplitDecision {
        require(misassignedBursts.isNotEmpty()) { "분리할 burst 가 없으면 split 하지 않는다" }
        return ThreadSplitDecision(
            keptThreadId = original,
            splitThreadId = splitThreadId,
            movedBursts = misassignedBursts,
            correction =
                ThreadProvenance(
                    kind = ThreadProvenanceKind.SPLIT_OFF,
                    burstId = misassignedBursts.minByOrNull { it.value },
                    ruleVersion = ruleVersion,
                ),
        )
    }
}
