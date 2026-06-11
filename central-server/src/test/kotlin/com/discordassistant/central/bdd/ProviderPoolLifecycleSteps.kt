package com.discordassistant.central.bdd

import com.discordassistant.central.guild.application.GuildRemovalCleanupService
import com.discordassistant.central.platform.discord.CommandContext
import com.discordassistant.central.platform.discord.CommandService
import com.discordassistant.central.platform.discord.Reply
import com.discordassistant.central.provider.application.ProviderRegistrationService
import com.discordassistant.central.provider.application.TokenService
import com.discordassistant.central.relay.AgentConnection
import com.discordassistant.central.relay.ConnectionRegistry
import com.discordassistant.central.relay.ProviderSession
import com.discordassistant.central.relay.protocol.Frame
import com.discordassistant.central.requestlog.application.UsageService
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.annotation.Autowired

private class LifecycleConn : AgentConnection {
    override val remoteId = "bdd-lifecycle"
    var closedReason: String? = null

    override fun sendFrame(frame: Frame) {}

    override fun close(reason: String) {
        closedReason = reason
    }
}

/** Provider Pool 생명주기/에지 정책 BDD step 정의. */
class ProviderPoolLifecycleSteps {
    @Autowired
    lateinit var registry: ConnectionRegistry

    @Autowired
    lateinit var registration: ProviderRegistrationService

    @Autowired
    lateinit var tokens: TokenService

    @Autowired
    lateinit var cleanup: GuildRemovalCleanupService

    @Autowired
    lateinit var usage: UsageService

    @Autowired
    lateinit var commands: CommandService

    private var issuedToken: String? = null
    private var lifecycleConn: LifecycleConn? = null
    private var lastReply: Reply? = null
    private var verifiedGuildId: Long? = null

    @Given("프로바이더 {long} 이 길드 {long} 에 세션과 토큰으로 등록되어 있다")
    fun providerHasSessionAndToken(
        providerId: Long,
        guildId: Long,
    ) {
        val conn = LifecycleConn()
        lifecycleConn = conn
        registry.register(ProviderSession(conn, providerId = providerId, guildId = guildId))
        issuedToken = registration.requestJoin(providerId, guildId, autoApprove = true).token
    }

    @When("봇이 길드 {long} 에서 제거된다")
    fun botRemovedFromGuild(guildId: Long) {
        cleanup.cleanup(guildId)
    }

    @Then("길드 {long} 의 프로바이더 세션과 등록과 토큰은 정리된다")
    fun guildProviderStateIsCleaned(guildId: Long) {
        assertTrue(registry.byGuild(guildId).isEmpty())
        assertNotNull(lifecycleConn?.closedReason, "세션 close 가 호출되지 않았습니다.")
    }

    @Then("프로바이더 {long} 의 등록과 발급 토큰도 사용할 수 없다")
    fun providerRegistrationAndTokenAreGone(providerId: Long) {
        assertNull(registration.stateOf(providerId))
        assertNull(tokens.verify(issuedToken ?: error("발급 토큰이 없습니다.")))
    }

    @Given("길드 {long} 에서 프로바이더 {long} 이 {int} 회, 프로바이더 {long} 이 {int} 회 기여했다")
    fun providersContributed(
        guildId: Long,
        providerA: Long,
        countA: Int,
        providerB: Long,
        countB: Int,
    ) {
        repeat(countA) { idx ->
            usage.recordSuccess(
                guildId = guildId,
                userId = 10_000L + idx,
                providerId = providerA,
                requestId = "bdd-$guildId-$providerA-$idx",
            )
        }
        repeat(countB) { idx ->
            usage.recordSuccess(
                guildId = guildId,
                userId = 20_000L + idx,
                providerId = providerB,
                requestId = "bdd-$guildId-$providerB-$idx",
            )
        }
    }

    @When("사용자가 길드 {long} 에서 기여 현황을 확인한다")
    fun userChecksContributions(guildId: Long) {
        lastReply =
            commands.contributions(
                CommandContext(
                    guildId = guildId,
                    channelId = 200,
                    userId = 4242,
                    roleIds = setOf(1L),
                    isAdmin = false,
                ),
            )
    }

    @Then("기여 현황에는 누적 {int} 건과 기여 멤버 {int} 명이 순위 없이 표시된다")
    fun contributionSummaryContains(
        totalCount: Int,
        providerCount: Int,
    ) {
        val content = lastReply?.content.orEmpty()
        assertTrue(content.contains("누적 처리: **${totalCount}건**"), content)
        assertTrue(content.contains("기여한 멤버: **${providerCount}명**"), content)
        assertTrue(content.contains("순위 비교 없이"), content)
    }

    @Given("프로바이더 {long} 이 길드 {long} 용 토큰을 발급받았다")
    fun providerIssuedToken(
        providerId: Long,
        guildId: Long,
    ) {
        issuedToken = tokens.issue(providerId, guildId)
    }

    @When("에이전트가 그 토큰으로 인증한다")
    fun agentAuthenticatesWithToken() {
        verifiedGuildId = tokens.verify(issuedToken ?: error("발급 토큰이 없습니다."))?.guildId
    }

    @Then("토큰은 길드 {long} 에만 묶이고 재사용할 수 없다")
    fun tokenIsGuildBoundAndOneTime(guildId: Long) {
        assertEquals(guildId, verifiedGuildId)
        assertNull(tokens.verify(issuedToken ?: error("발급 토큰이 없습니다.")))
    }
}
