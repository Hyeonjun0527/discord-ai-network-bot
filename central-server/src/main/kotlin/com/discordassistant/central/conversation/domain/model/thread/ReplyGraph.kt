package com.discordassistant.central.conversation.domain.model.thread

import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.event.MessageId

/**
 * Burst 간 reply 그래프(NEXA-P05-T002, 순수 도메인 불변 그래프).
 *
 * Discord 메시지의 reply 관계를 **burst 단위 directed edge** 로 승격한다. 한 burst 의 조각이 다른 메시지를
 * 답글 대상으로 가리키면, 그 대상 메시지가 속한 burst 로 향하는 edge 를 만든다 — 메시지 사슬보다 거친
 * 입자(burst)로 그래프를 봐야 논리 스레드 배치(T010)가 단순해진다.
 *
 * **acceptance(T002) — tombstone**: reply 대상 메시지가 삭제되어 어느 burst 에도 속하지 않으면, 그 대상을
 * [ReplyEdge.target] 의 [ReplyTarget.Tombstone] node 로 유지한다. edge 를 버리지 않으므로 그래프 연결이
 * 끊기지 않고("이 burst 는 무언가에 답했다" 는 사실 보존), 나중에 대상이 복원돼도 위치를 알 수 있다.
 *
 * 순수성: Spring/JPA/JDA/adapter 타입을 일절 참조하지 않는다. value type 으로만 운반한다.
 */
data class ReplyGraph(
    /** burst → reply 대상(해결된 burst 또는 tombstone)으로 향하는 directed edge 들(삽입 순서 보존). */
    val edges: List<ReplyEdge>,
) {
    /** [source] burst 에서 나가는 reply edge 들(없으면 빈 리스트). */
    fun outgoing(source: BurstId): List<ReplyEdge> = edges.filter { it.source == source }

    /** [target] burst 로 들어오는 reply edge 들(tombstone 대상은 제외 — 해결된 burst target 만). */
    fun incoming(target: BurstId): List<ReplyEdge> = edges.filter { (it.target as? ReplyTarget.Resolved)?.burstId == target }

    companion object {
        /** 빈 그래프(edge 없음). */
        val EMPTY: ReplyGraph = ReplyGraph(emptyList())
    }
}

/**
 * Burst 단위 reply edge(순수 도메인). [source] burst 가 [target](해결된 burst 또는 삭제된 tombstone)에 답했다.
 * [viaMessage] 는 답글을 건 원천 메시지(증거)이고, [toMessage] 는 답글이 가리킨 대상 메시지 키다.
 */
data class ReplyEdge(
    val source: BurstId,
    val target: ReplyTarget,
    /** 답글을 건 [source] burst 안의 메시지(증거; 원문 텍스트는 운반하지 않는다). */
    val viaMessage: MessageId,
    /** 답글이 가리킨 대상 메시지 키(target 이 tombstone 이어도 어떤 메시지였는지 보존). */
    val toMessage: MessageId,
)

/**
 * Reply edge 의 대상 node(sealed). 대상 메시지가 어느 burst 에 속하면 [Resolved], 삭제되어 사라졌으면
 * [Tombstone] — tombstone 도 node 로 유지해 그래프 연결을 끊지 않는다(acceptance T002).
 */
sealed interface ReplyTarget {
    /** 대상 메시지가 해결된 burst 에 속함. */
    data class Resolved(
        val burstId: BurstId,
    ) : ReplyTarget

    /** 대상 메시지가 삭제되어 어느 burst 에도 없음 — 그래프 연결 유지를 위한 묘비 node. */
    data object Tombstone : ReplyTarget
}
