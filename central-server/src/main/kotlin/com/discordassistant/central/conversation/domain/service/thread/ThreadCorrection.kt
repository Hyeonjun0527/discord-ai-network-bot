package com.discordassistant.central.conversation.domain.service.thread

import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.thread.ConversationThreadId

/**
 * 스레드 교정 provenance 한 건(NEXA-P05-T011/T012, 순수 도메인 값 객체·불변). merge/split 결정이 **왜** 일어났는지
 * 식별자·근거 코드로만 남긴다 — 원문 텍스트를 담지 않는다(logging-boundary.md). 과거 decision 보존의 단위다.
 */
data class ThreadProvenance(
    val kind: ThreadProvenanceKind,
    /** 이 교정을 유발한 reply 증거 burst(있으면). merge=잇는 burst, split=잘못 합쳐진 burst. */
    val burstId: BurstId? = null,
    /** 교정을 만든 규칙 버전(재현·디버깅). */
    val ruleVersion: String,
) {
    init {
        require(ruleVersion.isNotBlank()) { "ruleVersion 은 비어 있을 수 없다" }
    }
}

/** 스레드 교정 근거 종류(순수 도메인 enum). 원문 없이 어떤 신호로 merge/split 이 결정됐는지 코드로만 남긴다. */
enum class ThreadProvenanceKind {
    /** 명시적 reply 로 두 스레드가 연결됨(merge 근거, T011). */
    MERGE_REPLY,

    /** 다른 스레드를 흡수한 스레드의 과거 기록 표식(merge provenance 보존, T011). */
    ABSORBED_HISTORY,

    /** 잘못 합쳐진 burst 가 새 스레드로 분리됨(split 교정 근거, T012). */
    SPLIT_OFF,
}

/**
 * 스레드 merge 결정(NEXA-P05-T011, 순수 도메인 값 객체·불변). 어느 스레드가 canonical 로 살아남고 어느 스레드를
 * 흡수했는지, 그리고 보존된 과거 provenance 를 담는다. projection 을 임의로 다시 쓰지 않고 이 결정만 적용한다.
 *
 * **acceptance(T011)**: [canonicalThreadId] 는 기존 스레드 id 그대로(새 id 미생성), [preservedProvenance] 에 양쪽
 * 과거 기록이 모두 남는다(merge 가 과거 decision 을 삭제하지 않는다).
 */
data class ThreadMergeDecision(
    val canonicalThreadId: ConversationThreadId,
    val absorbedThreadId: ConversationThreadId,
    val preservedProvenance: List<ThreadProvenance>,
) {
    init {
        require(canonicalThreadId != absorbedThreadId) { "canonical 과 absorbed 는 같을 수 없다" }
    }
}

/**
 * 스레드 split 교정 결정(NEXA-P05-T012, 순수 도메인 값 객체·불변). 잘못 합쳐진 burst 들을 **새 projection version**
 * 에서 원래 스레드와 분리한다. event store 를 재생하거나 과거 projection 을 임의 mutation 하지 않고, 이 correction
 * 결정만 남긴다 — 새 version 에서 적용된다.
 *
 * **acceptance(T012) — event store 재생 없이 임의 mutation 금지, correction event 잔존**:
 * - [keptThreadId] 는 원래 스레드(기존 id 유지), [splitThreadId] 는 분리되어 나간 새 스레드 id 다.
 * - [movedBursts] 는 새 스레드로 옮겨가는 burst 집합(비어 있을 수 없다 — 빈 split 은 무의미).
 * - [correction] 에 split 근거(SPLIT_OFF)가 provenance 로 남아 "어떻게 갈라졌는지" 가 추적된다.
 */
data class ThreadSplitDecision(
    val keptThreadId: ConversationThreadId,
    val splitThreadId: ConversationThreadId,
    val movedBursts: Set<BurstId>,
    val correction: ThreadProvenance,
) {
    init {
        require(keptThreadId != splitThreadId) { "kept 와 split 스레드는 같을 수 없다" }
        require(movedBursts.isNotEmpty()) { "split 은 최소 1개 burst 를 새 스레드로 옮긴다(빈 split 금지)" }
        require(correction.kind == ThreadProvenanceKind.SPLIT_OFF) {
            "split correction 의 근거는 SPLIT_OFF provenance 여야 한다: ${correction.kind}"
        }
    }
}
