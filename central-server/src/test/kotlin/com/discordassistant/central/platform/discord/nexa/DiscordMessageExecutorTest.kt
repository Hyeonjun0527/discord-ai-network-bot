package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.actionruntime.application.content.SpeechBurstContentCodec
import com.discordassistant.central.actionruntime.application.port.out.ExecutionResult
import com.discordassistant.central.actionruntime.application.port.out.SpeechContentResolver
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class DiscordMessageExecutorTest {
    @Test
    fun `bubble index selects one stored bubble for one Discord send`() {
        val jda = mock(JDA::class.java)
        val channel = mock(TextChannel::class.java)
        val action = mock(MessageCreateAction::class.java)
        val sent = mock(Message::class.java)
        val stored = SpeechBurstContentCodec.encode(listOf("첫 메시지", "둘째 메시지", "셋째 메시지"))
        val executor = DiscordMessageExecutor(jda, SpeechContentResolver { stored })
        `when`(jda.getTextChannelById("123")).thenReturn(channel)
        `when`(channel.sendMessage("둘째 메시지")).thenReturn(action)
        `when`(action.complete()).thenReturn(sent)
        `when`(sent.id).thenReturn("message-2")

        val result = executor.send("123", "speech-ref", bubbleIndex = 1, replyToMessageId = null)

        assertThat(result).isEqualTo(ExecutionResult.Sent("message-2"))
        verify(channel).sendMessage("둘째 메시지")
    }
}
