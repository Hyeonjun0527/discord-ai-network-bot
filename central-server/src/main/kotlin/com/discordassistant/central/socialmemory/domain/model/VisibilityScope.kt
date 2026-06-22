package com.discordassistant.central.socialmemory.domain.model

/**
 * 한 기억이 **어디까지 보일 수 있는가**를 타입으로 못 박는 가시성 스코프(NEXA-P07-T011, 순수 도메인 sealed 타입).
 *
 * 모든 스코프는 guild 가명([guildPseudonym]) 에 묶인다 — cross-guild 식별·연결 금지(ADR 0010, data-categories.md).
 * 더 좁은 스코프(channel/thread/private)는 추가 가명 토큰으로 한정한다. 원본 snowflake 가 아니라 가명만 운반한다.
 *
 * **acceptance(T011) — 한 서버 기억이 다른 서버 prompt 에 노출되지 않는다**: [isVisibleTo] 는 다른 guild 가명에는
 * 무조건 false 다. 같은 guild 안에서도 요청 스코프가 기억 스코프를 **포함**할 때만 노출된다(channel 기억은 그 channel
 * 요청에만, private 기억은 그 private 대상에만).
 *
 * 순수성: Spring/JPA/JDA 미참조.
 */
sealed interface VisibilityScope {
    /** 이 스코프가 묶인 guild 가명 토큰(모든 스코프 공통). 다른 guild 에는 절대 노출되지 않는다. */
    val guildPseudonym: String

    /**
     * [requester] 스코프의 prompt 가 이 기억을 볼 수 있는가. guild 가 다르면 무조건 false(acceptance T011).
     * 같은 guild 안에서는 요청 스코프가 이 기억 스코프를 포함할 때만 true.
     */
    fun isVisibleTo(requester: VisibilityScope): Boolean

    /** guild 전체에 보이는 기억(그 guild 의 어떤 채널/스레드 요청에도 노출). */
    data class Guild(
        override val guildPseudonym: String,
    ) : VisibilityScope {
        init {
            require(guildPseudonym.isNotBlank()) { "guildPseudonym 은 비어 있을 수 없다" }
        }

        // guild 기억은 같은 guild 안의 모든 스코프에 노출된다.
        override fun isVisibleTo(requester: VisibilityScope): Boolean = requester.guildPseudonym == guildPseudonym
    }

    /** 특정 채널 스코프 기억(그 채널·그 채널의 스레드 요청에만 노출). */
    data class Channel(
        override val guildPseudonym: String,
        val channelPseudonym: String,
    ) : VisibilityScope {
        init {
            require(guildPseudonym.isNotBlank()) { "guildPseudonym 은 비어 있을 수 없다" }
            require(channelPseudonym.isNotBlank()) { "channelPseudonym 은 비어 있을 수 없다" }
        }

        override fun isVisibleTo(requester: VisibilityScope): Boolean =
            when (requester) {
                is Guild -> false
                is Channel -> requester.guildPseudonym == guildPseudonym && requester.channelPseudonym == channelPseudonym
                is Thread -> requester.guildPseudonym == guildPseudonym && requester.channelPseudonym == channelPseudonym
                is Private -> false
            }
    }

    /** 특정 스레드 스코프 기억(그 스레드 요청에만 노출 — 가장 좁은 채널 하위). */
    data class Thread(
        override val guildPseudonym: String,
        val channelPseudonym: String,
        val threadPseudonym: String,
    ) : VisibilityScope {
        init {
            require(guildPseudonym.isNotBlank()) { "guildPseudonym 은 비어 있을 수 없다" }
            require(channelPseudonym.isNotBlank()) { "channelPseudonym 은 비어 있을 수 없다" }
            require(threadPseudonym.isNotBlank()) { "threadPseudonym 은 비어 있을 수 없다" }
        }

        override fun isVisibleTo(requester: VisibilityScope): Boolean =
            requester is Thread &&
                requester.guildPseudonym == guildPseudonym &&
                requester.channelPseudonym == channelPseudonym &&
                requester.threadPseudonym == threadPseudonym
    }

    /** 특정 대상(1:1) private 스코프 기억 — 그 대상 본인 요청에만 노출. */
    data class Private(
        override val guildPseudonym: String,
        val subjectPseudonym: String,
    ) : VisibilityScope {
        init {
            require(guildPseudonym.isNotBlank()) { "guildPseudonym 은 비어 있을 수 없다" }
            require(subjectPseudonym.isNotBlank()) { "subjectPseudonym 은 비어 있을 수 없다" }
        }

        override fun isVisibleTo(requester: VisibilityScope): Boolean =
            requester is Private &&
                requester.guildPseudonym == guildPseudonym &&
                requester.subjectPseudonym == subjectPseudonym
    }
}
