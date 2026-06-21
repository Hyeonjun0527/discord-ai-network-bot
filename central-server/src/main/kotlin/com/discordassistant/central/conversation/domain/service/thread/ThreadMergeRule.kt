package com.discordassistant.central.conversation.domain.service.thread

import com.discordassistant.central.conversation.domain.model.thread.ConversationThreadId

/**
 * 스레드 merge 규칙(NEXA-P05-T011, 순수 함수). 명시적 reply 로 그동안 별개였던 두 논리 스레드가 연결되면, 둘을
 * 하나로 합치되 **기존 ID 와 과거 decision provenance 를 보존** 하는 merge 결정을 만든다.
 *
 * [LogicalThreadAssembler] 가 전체 burst 를 한 번에 union-find 로 묶는다면, 이 규칙은 **이미 만들어진 두 스레드가
 * 나중에 reply 로 이어졌을 때** 의 증분 교정을 다룬다 — projection 을 임의로 다시 쓰지 않고, 어느 스레드가 어느
 * 스레드로 흡수되는지와 그 근거(연결 reply)를 [ThreadMergeDecision] 으로 남긴다(event store 재생 불요).
 *
 * **merge 방향(KISS·결정론)**: 두 스레드 중 **더 오래된(canonical) 스레드** 가 살아남고 다른 쪽을 흡수한다.
 * canonical 은 입력으로 받은 [ThreadLineage.createdSequence] 가 작은 쪽 — 같은 입력 replay 면 항상 같은 방향이다.
 *
 * **acceptance(T011) — 기존 ID·과거 provenance 보존**:
 * - 살아남는 [ThreadMergeDecision.canonicalThreadId] 는 두 스레드 중 하나의 **기존** id 다(새 id 를 만들지 않는다).
 * - 흡수되는 스레드의 과거 [ThreadLineage.provenance] 가 [ThreadMergeDecision.preservedProvenance] 에 합쳐져
 *   보존된다 — merge 가 과거 decision 기록을 삭제하지 않는다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 상태 없음(모든 입력을 인자로 받는다).
 */
object ThreadMergeRule {
    /**
     * 명시적 reply 로 연결된 두 스레드를 합치는 결정을 만든다. 같은 스레드면 null(merge 불요).
     *
     * @param a 한쪽 스레드 계보.
     * @param b 다른 쪽 스레드 계보.
     * @param linkingReply 두 스레드를 잇는 reply 증거(merge 근거 provenance).
     */
    fun merge(
        a: ThreadLineage,
        b: ThreadLineage,
        linkingReply: ThreadProvenance,
    ): ThreadMergeDecision? {
        require(linkingReply.kind == ThreadProvenanceKind.MERGE_REPLY) {
            "merge 의 연결 근거는 MERGE_REPLY provenance 여야 한다: ${linkingReply.kind}"
        }
        if (a.threadId == b.threadId) return null

        // canonical = 더 오래된 스레드(생성 순번이 작은 쪽). 동률이면 id 문자열로 결정론적 타이브레이크.
        val (canonical, absorbed) =
            if (a.createdSequence != b.createdSequence) {
                if (a.createdSequence < b.createdSequence) a to b else b to a
            } else {
                if (a.threadId.value <= b.threadId.value) a to b else b to a
            }

        // 기존 ID 보존: 새 id 를 만들지 않고 canonical 의 기존 id 를 유지한다.
        // 과거 provenance 보존: 양쪽 과거 기록 + 흡수 표식 + 연결 reply 를 순서 보존으로 합친다.
        val preserved =
            buildList {
                addAll(canonical.provenance)
                addAll(absorbed.provenance)
                add(linkingReply)
            }

        return ThreadMergeDecision(
            canonicalThreadId = canonical.threadId,
            absorbedThreadId = absorbed.threadId,
            preservedProvenance = preserved,
        )
    }
}

/**
 * 한 논리 스레드의 계보(merge/split 교정 입력, 순수 도메인 값 객체·불변). 스레드의 기존 id, 생성 순번(merge 방향
 * 결정론), 그동안 쌓인 과거 provenance 를 담는다 — 교정 규칙이 이 계보를 보고 ID·provenance 를 보존하며 합치거나 나눈다.
 */
data class ThreadLineage(
    val threadId: ConversationThreadId,
    /** 스레드 생성 순번(작을수록 오래됨). merge 시 canonical 방향을 결정론적으로 고른다. */
    val createdSequence: Long,
    /** 그동안 이 스레드에 쌓인 과거 decision provenance(원문 비포함, 식별자·근거 코드만). 순서 보존. */
    val provenance: List<ThreadProvenance>,
) {
    init {
        require(createdSequence >= 0) { "createdSequence 는 음수일 수 없다" }
    }
}
