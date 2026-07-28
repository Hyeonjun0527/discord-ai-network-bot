package com.discordassistant.central.onboarding.application

import com.discordassistant.central.routing.application.CloudTurn
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

@Component
class NiaWebDemoConversationStore(
    @param:Value("\${central.nia-web-demo.history-ttl-seconds:1800}") private val ttlSeconds: Long = 1800,
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class Conversation(
        val turns: MutableList<CloudTurn>,
        var expiresAt: Instant,
    )

    private data class ConversationKey(
        val userId: String,
        val conversationId: String,
    )

    private val conversations = mutableMapOf<ConversationKey, Conversation>()

    init {
        require(ttlSeconds > 0) { "웹 체험 대화 보존 시간은 양수여야 합니다." }
    }

    @Synchronized
    fun history(
        userId: String,
        conversationId: String,
        maxMessages: Int,
    ): List<CloudTurn> {
        require(maxMessages > 0) { "웹 체험 문맥 메시지 상한은 양수여야 합니다." }
        val now = clock.instant()
        prune(now)
        return conversations[ConversationKey(userId, conversationId)]
            ?.turns
            ?.takeLast(maxMessages)
            .orEmpty()
    }

    @Synchronized
    fun append(
        userId: String,
        conversationId: String,
        userMessage: String,
        assistantMessage: String,
        maxMessages: Int,
    ) {
        require(maxMessages > 0) { "웹 체험 문맥 메시지 상한은 양수여야 합니다." }
        val now = clock.instant()
        prune(now)
        val key = ConversationKey(userId, conversationId)
        val conversation =
            conversations.getOrPut(key) {
                Conversation(mutableListOf(), now.plusSeconds(ttlSeconds))
            }
        conversation.turns += CloudTurn("user", userMessage)
        conversation.turns += CloudTurn("assistant", assistantMessage)
        while (conversation.turns.size > maxMessages) {
            conversation.turns.removeAt(0)
        }
        conversation.expiresAt = now.plusSeconds(ttlSeconds)
    }

    private fun prune(now: Instant) {
        conversations.entries.removeIf { !now.isBefore(it.value.expiresAt) }
    }
}
