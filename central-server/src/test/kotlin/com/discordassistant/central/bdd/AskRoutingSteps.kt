package com.discordassistant.central.bdd

import com.discordassistant.central.discord.CommandContext
import com.discordassistant.central.discord.CommandService
import com.discordassistant.central.discord.Reply
import com.discordassistant.central.relay.AgentConnection
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.relay.ProviderSession
import com.discordassistant.central.relay.protocol.Frame
import com.discordassistant.central.relay.protocol.InferRequest
import com.discordassistant.central.relay.protocol.InferResult
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.annotation.Autowired

/** BDD 시나리오의 질문자 고정 ID(차단 시나리오에서 차단 대상과 질문자를 일치시킨다). */
private const val ASKER_USER_ID = 4242L

/** echo 프로바이더(테스트용): InferRequest 를 받으면 "echo:{prompt}" 로 즉시 응답한다(실제 라우팅 경로 검증). */
private class EchoConn : AgentConnection {
    lateinit var session: ProviderSession
    override val remoteId = "bdd-echo"

    override fun sendFrame(frame: Frame) {
        if (frame is InferRequest) session.handleFrame(InferResult(frame.requestId, "echo:${frame.prompt}"))
    }

    override fun close(reason: String) {}
}

/** /ask 핵심 흐름 step 정의(차수 18). CommandService → RequestOrchestrator 실경로를 실제 컨텍스트에서 실행. */
class AskRoutingSteps {
    @Autowired
    lateinit var commands: CommandService

    @Autowired
    lateinit var registry: ConnectionRegistry

    private val registered = mutableListOf<ProviderSession>()
    private var lastReply: Reply? = null

    private fun connectEcho(
        guildId: Long,
        providerId: Long,
    ) {
        val conn = EchoConn()
        val session = ProviderSession(conn, providerId = providerId, guildId = guildId)
        conn.session = session
        registry.register(session)
        registered.add(session)
    }

    @Given("echo 프로바이더가 길드 {long} 에 연결되어 있다")
    fun echoConnected(guildId: Long) = connectEcho(guildId, guildId + 1)

    @Given("길드 {long} 에 연결된 프로바이더가 없다")
    fun noProvider(
        @Suppress("UNUSED_PARAMETER") guildId: Long,
    ) {
        // 고유 길드 ID 를 써서 풀이 비어 있음을 보장한다(의도적으로 아무 것도 등록하지 않음).
    }

    @Given("관리자가 길드 {long} 의 채널 {long} 만 LLM 을 허용한다")
    fun adminAllowsChannel(
        guildId: Long,
        channelId: Long,
    ) {
        val admin = CommandContext(guildId = guildId, channelId = channelId, userId = 1L, roleIds = setOf(1L), isAdmin = true)
        commands.allowChannel(admin, channelId)
    }

    @Given("관리자가 길드 {long} 에서 질문자를 차단한다")
    fun adminBlocksAsker(guildId: Long) {
        val admin = CommandContext(guildId = guildId, channelId = 1L, userId = 1L, roleIds = setOf(1L), isAdmin = true)
        commands.blockUser(admin, ASKER_USER_ID)
    }

    @When("사용자가 길드 {long} 채널 {long} 에서 {string} 라고 질문한다")
    fun userAsks(
        guildId: Long,
        channelId: Long,
        prompt: String,
    ) {
        val user = CommandContext(guildId = guildId, channelId = channelId, userId = ASKER_USER_ID, roleIds = setOf(1L), isAdmin = false)
        lastReply = commands.ask(user, prompt)
    }

    @Then("응답에 {string} 가 포함된다")
    fun replyContains(expected: String) {
        val content = lastReply?.content.orEmpty()
        assertTrue(content.contains(expected), "기대 '$expected' 미포함. 실제: $content")
    }

    @Then("응답이 거부 안내를 포함한다")
    fun replyRejected() {
        val content = lastReply?.content.orEmpty()
        assertTrue(content.contains("⛔") || content.contains("사용할 수 없"), "거부 안내가 아님. 실제: $content")
    }

    @After
    fun cleanup() {
        registered.forEach { registry.unregister(it) }
        registered.clear()
        lastReply = null
    }
}
