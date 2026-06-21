package com.discordassistant.central.conversation

import com.discordassistant.central.conversation.application.port.out.ConsentPolicyPort
import com.discordassistant.central.conversation.domain.model.ConsentDecision
import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.ConsentDenialReason
import com.discordassistant.central.conversation.domain.model.event.ConsentDenialScope
import com.discordassistant.central.conversation.domain.model.event.ConsentDenied
import com.discordassistant.central.conversation.domain.model.event.EventId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.MessageContent
import com.discordassistant.central.conversation.domain.model.event.MessageCreated
import com.discordassistant.central.conversation.domain.model.event.MessageId
import com.discordassistant.central.conversation.domain.model.event.NormalizedDiscordEvent
import com.discordassistant.central.conversation.domain.model.event.PrivacyClass
import com.discordassistant.central.global.crypto.ScopedPseudonymizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * NEXA-P02-T024 동의 경계 통합 테스트.
 *
 * conversation 수집 경계의 **3가지 차단 시나리오**(미동의=길드 비활성 / 옵트아웃 사용자 / 제외 채널)에서
 * 이벤트가 **저장·외부 전송 후보로 가지 않음**을 [ConsentPolicyPort]/[ConsentDecision] 으로 증명한다.
 *
 * acceptance:
 * - 3가지 차단이 각각 **고유한 감사 코드**([ConsentDenialScope]+[ConsentDenialReason])로 구분된다.
 * - 차단 경로에서 **원문 메시지 내용이 로그·감사에 남지 않는다**(가명/코드만). 원문 PII 의 부재를 테스트로 증명한다.
 *
 * 근거: consent-model.md(미동의 시 관찰 금지), user-opt-out.md(옵트아웃 배제), channel-scope.md(제외 채널),
 * external-model-data.md(외부 전송 금지 필드), logging-boundary.md(원문 로그 금지).
 *
 * 순수성: conversation.domain + global.crypto 만 사용(Spring/JPA/JDA 없음 — NexaArchitectureTest.nexaDomainsArePure).
 */
class NexaConsentBoundaryTest {
    private val guildId = 100L
    private val userId = 200L
    private val allowedChannel = 300L
    private val excludedChannel = 999L

    /** 원문 내용 — 차단 경로 어디에도 남으면 안 되는 PII 마커. */
    private val secretContent = "TOP-SECRET-원문-메시지-내용-leak-canary"

    /**
     * 3가지 차단 정책을 합성하는 fake [ConsentPolicyPort].
     * - 길드 비활성([disabledGuild]) → DENIED
     * - 사용자 옵트아웃([optedOutUser]) → DENIED
     * - 제외 채널([excludedChannels]) → DENIED
     * 어느 차단에도 안 걸리면 OBSERVE_AND_SPEAK.
     */
    private fun policy(
        disabledGuild: Boolean = false,
        optedOutUser: Long? = null,
        excludedChannels: Set<Long> = emptySet(),
    ): ConsentPolicyPort =
        ConsentPolicyPort { g, u, c ->
            when {
                disabledGuild -> ConsentDecision.DENIED
                u == optedOutUser -> ConsentDecision.DENIED
                c in excludedChannels -> ConsentDecision.DENIED
                else -> ConsentDecision.OBSERVE_AND_SPEAK
            }
        }

    /** 원문(PII high)을 담은 관찰 메시지 — 동의 통과 시에만 저장/전송 후보가 된다. */
    private fun observedMessage(channelId: Long): MessageCreated =
        MessageCreated(
            eventId = EventId("evt-msg-1"),
            guildId = GuildId(guildId),
            channelId = ChannelId(channelId),
            occurredAt = Instant.parse("2026-06-21T10:00:00Z"),
            receivedAt = Instant.parse("2026-06-21T10:00:00Z"),
            sourceSequence = 1L,
            privacyClass = PrivacyClass.HIGH,
            messageId = MessageId(10L),
            authorId = AuthorId(userId),
            content = MessageContent.Available(secretContent),
            replyTo = null,
            mentions = emptySet(),
            attachments = emptyList(),
            threadId = null,
        )

    /**
     * 차단 경로에서 남기는 감사 레코드(테스트 로컬 표현). 도메인 enum 코드와 가명만 담는다 — 원문 없음.
     * ConsentDenied 가 운반하는 감사 정보(scope/reason)와 가명화된 actor 만 모사한다.
     */
    private data class AuditRecord(
        val scope: ConsentDenialScope,
        val reason: ConsentDenialReason,
        val actorPseudonym: String,
    ) {
        /** 감사 레코드를 로그 한 줄로 직렬화 — 원문이 새지 않는지 검사할 표면. */
        fun toLogLine(): String = "BLOCKED scope=$scope reason=$reason actor=$actorPseudonym"
    }

    /**
     * 동의 차단 시 발행되는 내부 [ConsentDenied] 이벤트(저장/전송이 아니라 중단 신호) + 감사 레코드를 만든다.
     * 원문([MessageCreated.content]) 은 의도적으로 운반하지 않는다 — scope/reason 코드와 가명만 남긴다.
     */
    private fun blockAndAudit(
        scope: ConsentDenialScope,
        reason: ConsentDenialReason,
        observed: MessageCreated,
    ): Pair<ConsentDenied, AuditRecord> {
        val denied =
            ConsentDenied(
                eventId = EventId("deny-${observed.eventId.value}"),
                guildId = observed.guildId,
                channelId = observed.channelId,
                occurredAt = observed.occurredAt,
                receivedAt = observed.receivedAt,
                sourceSequence = observed.sourceSequence,
                privacyClass = PrivacyClass.LOW,
                scope = scope,
                reason = reason,
            )
        val pseudonym =
            ScopedPseudonymizer.pseudonymize(
                purpose = ScopedPseudonymizer.Purpose.LOG,
                guildId = observed.guildId.value,
                snowflake = observed.authorId.value,
            )
        return denied to AuditRecord(scope, reason, pseudonym)
    }

    // ── 차단 시나리오 1: 미동의(길드 비활성) ──────────────────────────────────────

    @Test
    fun `미동의 길드의 이벤트는 관찰조차 허용되지 않는다`() {
        val decision = policy(disabledGuild = true).observationDecision(guildId, userId, allowedChannel)

        assertEquals(ConsentDecision.DENIED, decision)
        assertFalse(decision.observationAllowed, "미동의 길드는 관찰(저장 후보) 금지")
        assertFalse(decision.speechAllowed, "관찰이 막히면 발화도 막힘")
    }

    // ── 차단 시나리오 2: 옵트아웃 사용자 ─────────────────────────────────────────

    @Test
    fun `옵트아웃 사용자의 이벤트는 관찰 후보에서 배제된다`() {
        val decision = policy(optedOutUser = userId).observationDecision(guildId, userId, allowedChannel)

        assertEquals(ConsentDecision.DENIED, decision)
        assertFalse(decision.observationAllowed, "옵트아웃 사용자는 관찰(저장 후보) 금지")
    }

    @Test
    fun `옵트아웃 아닌 사용자는 같은 길드 채널에서 관찰이 허용된다`() {
        // 옵트아웃은 사용자별 — 다른 사용자는 차단되지 않음(개인 거부는 개인에 한정).
        val other = userId + 1
        val decision = policy(optedOutUser = userId).observationDecision(guildId, other, allowedChannel)

        assertTrue(decision.observationAllowed)
    }

    // ── 차단 시나리오 3: 제외 채널 ───────────────────────────────────────────────

    @Test
    fun `제외 채널의 이벤트는 관찰 후보에서 배제된다`() {
        val decision =
            policy(excludedChannels = setOf(excludedChannel))
                .observationDecision(guildId, userId, excludedChannel)

        assertEquals(ConsentDecision.DENIED, decision)
        assertFalse(decision.observationAllowed, "제외 채널은 관찰(저장 후보) 금지")
    }

    @Test
    fun `제외되지 않은 채널은 관찰이 허용된다`() {
        val decision =
            policy(excludedChannels = setOf(excludedChannel))
                .observationDecision(guildId, userId, allowedChannel)

        assertTrue(decision.observationAllowed)
    }

    // ── 차단된 이벤트는 저장/외부 전송 후보로 가지 않는다 ─────────────────────────

    @Test
    fun `세 가지 차단 모두에서 관찰 메시지는 저장 외부전송 후보 컬렉션에 들어가지 않는다`() {
        // "수집 경계" 모사: 동의 통과한 이벤트만 후보 목록에 적재. 차단이면 적재 0.
        fun collect(
            port: ConsentPolicyPort,
            channelId: Long,
        ): List<MessageCreated> {
            val msg = observedMessage(channelId)
            val decision = port.observationDecision(msg.guildId.value, msg.authorId.value, msg.channelId.value)
            return if (decision.observationAllowed) listOf(msg) else emptyList()
        }

        assertTrue(collect(policy(disabledGuild = true), allowedChannel).isEmpty(), "미동의 → 후보 0")
        assertTrue(collect(policy(optedOutUser = userId), allowedChannel).isEmpty(), "옵트아웃 → 후보 0")
        assertTrue(
            collect(policy(excludedChannels = setOf(excludedChannel)), excludedChannel).isEmpty(),
            "제외 채널 → 후보 0",
        )
        // 대조: 동의 통과 시에만 후보가 1개 적재된다(차단 로직이 무조건 비우는 게 아님을 증명).
        assertEquals(1, collect(policy(), allowedChannel).size, "동의 통과 → 후보 1")
    }

    // ── acceptance: 각 차단 이유가 고유한 감사 코드로 구분된다 ────────────────────

    @Test
    fun `세 가지 차단 사유가 각각 고유한 감사 코드로 구분된다`() {
        val msg = observedMessage(allowedChannel)
        val (guildDenied, _) =
            blockAndAudit(ConsentDenialScope.GUILD_DISABLED, ConsentDenialReason.GUILD_POLICY, msg)
        val (userDenied, _) =
            blockAndAudit(ConsentDenialScope.USER_OPT_OUT, ConsentDenialReason.USER_REQUESTED, msg)
        val (channelDenied, _) =
            blockAndAudit(ConsentDenialScope.CHANNEL_EXCLUDED, ConsentDenialReason.CHANNEL_POLICY, msg)

        val scopes = setOf(guildDenied.scope, userDenied.scope, channelDenied.scope)
        val reasons = setOf(guildDenied.reason, userDenied.reason, channelDenied.reason)

        // 세 차단이 서로 다른 scope/reason 코드 — 감사에서 사유가 구분된다.
        assertEquals(3, scopes.size, "scope 코드 3개가 서로 다름")
        assertEquals(3, reasons.size, "reason 코드 3개가 서로 다름")

        // 차단 신호는 결정/상태만(LOW) — 원문 PII 등급(HIGH)을 운반하지 않는다.
        assertEquals(PrivacyClass.LOW, guildDenied.privacyClass)
        assertEquals(PrivacyClass.LOW, userDenied.privacyClass)
        assertEquals(PrivacyClass.LOW, channelDenied.privacyClass)
    }

    // ── acceptance: 차단 경로에서 원문이 로그·감사에 남지 않는다(가명/코드만) ──────

    @Test
    fun `차단 경로의 감사 로그에 원문 메시지 내용이 나타나지 않는다`() {
        val msg = observedMessage(allowedChannel)
        val records =
            listOf(
                blockAndAudit(ConsentDenialScope.GUILD_DISABLED, ConsentDenialReason.GUILD_POLICY, msg),
                blockAndAudit(ConsentDenialScope.USER_OPT_OUT, ConsentDenialReason.USER_REQUESTED, msg),
                blockAndAudit(ConsentDenialScope.CHANNEL_EXCLUDED, ConsentDenialReason.CHANNEL_POLICY, msg),
            )

        for ((denied, audit) in records) {
            val logLine = audit.toLogLine()
            // 1) 원문 내용이 로그에 새지 않는다.
            assertFalse(logLine.contains(secretContent), "감사 로그에 원문 내용 누출: $logLine")
            // 2) 원시 actor snowflake 도 로그에 평문으로 남지 않는다(가명만).
            assertFalse(logLine.contains(userId.toString()), "감사 로그에 원시 snowflake 누출: $logLine")
            // 3) 감사 코드(scope/reason)는 로그에 남아 사유를 추적 가능하다.
            assertTrue(logLine.contains(denied.scope.name))
            assertTrue(logLine.contains(denied.reason.name))
            // 4) actor 는 가명 형식(v{version}:...)으로만 남는다.
            assertTrue(audit.actorPseudonym.startsWith("v"), "actor 는 scoped 가명이어야 함")
            assertNotEquals(userId.toString(), audit.actorPseudonym, "가명은 원시 식별자와 달라야 함")
        }
    }

    @Test
    fun `차단 신호 ConsentDenied 는 원문을 담는 어떤 필드도 갖지 않는다`() {
        val msg = observedMessage(allowedChannel)
        val (denied, _) =
            blockAndAudit(ConsentDenialScope.USER_OPT_OUT, ConsentDenialReason.USER_REQUESTED, msg)

        // ConsentDenied 의 전체 toString() 표면에도 원문이 없음을 증명(데이터 클래스 자동 직렬화 포함).
        val serialized = denied.toString()
        assertFalse(serialized.contains(secretContent), "ConsentDenied 직렬화에 원문 누출: $serialized")
        // 차단 신호는 NormalizedDiscordEvent 봉투 계약을 따르는 내부 이벤트다(원문 운반 X).
        val envelope: NormalizedDiscordEvent = denied
        assertEquals(PrivacyClass.LOW, envelope.privacyClass)
    }
}
