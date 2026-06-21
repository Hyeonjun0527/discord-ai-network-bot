package com.discordassistant.central.conversation.domain.model.thread

import com.discordassistant.central.conversation.domain.model.burst.BurstLocationKey

/**
 * 논리적 대화 스레드 식별자(NEXA-P05-T001, 순수 도메인 value type).
 *
 * Discord 의 채널/스레드 ID 와 **논리적 대화 스레드** 를 구분한다. 한 부모 채널([BurstLocationKey]) 안에서
 * 여러 대화가 동시에 진행될 수 있다 — 같은 Discord channel ID 를 공유하면서도 reply/mention/adjacency 로
 * 묶이는 흐름은 서로 다른 논리 스레드다. Discord 의 네이티브 thread([ChannelId])는 위치(location)일 뿐,
 * 그 안에서도 논리 대화는 여럿일 수 있으므로 별도 식별자가 필요하다.
 *
 * 순수성: conversation.domain 규칙(NexaArchitectureTest.nexaDomainsArePure)을 위해 Spring/JPA/JDA/adapter
 * 타입을 일절 참조하지 않는다. 식별자는 순수 도메인 value type 으로만 운반한다.
 *
 * **acceptance(T001)**: 같은 [BurstLocationKey](= 같은 Discord channel ID, thread 유무 포함)를 공유하는
 * 서로 다른 [ordinal] 은 서로 다른 [ConversationThreadId] 다 — 즉 한 채널 안에서 여러 논리 대화가 공존한다.
 * 식별자는 위치 키 + 순번으로 결정론적으로 만들어져 replay 시 같은 입력이면 같은 id 가 나온다.
 */
@JvmInline
value class ConversationThreadId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "ConversationThreadId 는 비어 있을 수 없다" }
    }

    companion object {
        /**
         * 위치([location]) 안의 [ordinal] 번째 논리 대화에 대한 결정론적 식별자를 만든다.
         * 같은 위치라도 [ordinal] 이 다르면 다른 논리 스레드 — 한 Discord 채널에 여러 대화가 공존한다(acceptance).
         */
        fun of(
            location: BurstLocationKey,
            ordinal: Int,
        ): ConversationThreadId {
            require(ordinal >= 0) { "ordinal 은 음수일 수 없다" }
            val threadPart = location.threadId?.value?.toString() ?: "-"
            return ConversationThreadId("thread:${location.channelId.value}:$threadPart:#$ordinal")
        }
    }
}
