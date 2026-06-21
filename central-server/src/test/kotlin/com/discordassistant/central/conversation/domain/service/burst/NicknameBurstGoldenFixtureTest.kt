package com.discordassistant.central.conversation.domain.service.burst

import com.discordassistant.central.conversation.domain.model.burst.BurstGapPolicy
import com.discordassistant.central.conversation.domain.model.burst.BurstLocationKey
import com.discordassistant.central.conversation.domain.model.burst.BurstSession
import com.discordassistant.central.conversation.domain.model.burst.MessageFragment
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.event.MessageContent
import com.discordassistant.central.conversation.domain.model.event.MessageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * NEXA-P04-T020 golden fixture acceptance: 합성 닉네임/코알라 대화를 fragment->burst 정답으로 재생한다.
 *
 * `test-fixtures/nexa/bursts/nickname-fragments.yaml`(합성 데이터, 실제 사용자 데이터 아님)을 로드해 실제
 * [FixedGapBurstSegmenter] 로 분할하고, fixture 의 `expectedBursts`(첫 네 줄 = 한 버스트, 이후 화자 변경 =
 * 별도 버스트, D 개입으로 C 분리)와 정확히 일치하는지 검증한다. fixture 의 정답이 코드가 아니라 데이터로
 * 박혀 있어 segmenter 회귀가 같은 정답에 대해 잡힌다.
 */
class NicknameBurstGoldenFixtureTest {
    private val guild = GuildId(1L)
    private val seg = FixedGapBurstSegmenter()

    @Test
    fun `nickname-fragments golden fixture replays into expected bursts`() {
        val fixture = loadFixture()

        // gap 을 넉넉히(60s) 둬 fixture 의 1초 간격이 시간 경계를 만들지 않게 하고, 분할이 작성자 개입(T007)
        // 으로만 결정되게 한다 — fixture 의 정답은 화자 경계 기준이다.
        val policy = BurstGapPolicy(Duration.ofSeconds(60), Duration.ofSeconds(2), Duration.ofSeconds(120))

        val actualBursts = replay(fixture.fragments, policy)

        // 첫 네 줄(A×4)이 한 버스트인지 직접 확인(acceptance T020).
        assertEquals(
            listOf("msg-nickname-001", "msg-nickname-002", "msg-nickname-003", "msg-nickname-004"),
            actualBursts.first { it.first() == "msg-nickname-001" },
        )
        // 후속 화자 메시지가 별도 버스트로 분리되는지 fixture 정답과 정확히 일치하는지 확인.
        assertEquals(
            fixture.expectedBursts.sortedBy { it.first() },
            actualBursts.sortedBy { it.first() },
        )
    }

    /** fixture fragments 를 시간순으로 segmenter 에 흘려보내 종료된 버스트의 messageId 라벨 리스트를 만든다. */
    private fun replay(
        fragments: List<FixtureFragment>,
        policy: BurstGapPolicy,
    ): List<List<String>> {
        val labelOf = fragments.associate { MessageId(it.numericId) to it.messageId }
        var session = BurstSession.empty(BurstLocationKey(ChannelId(100L), null))
        val finalized = mutableListOf<UtteranceBurst>()

        fragments.forEach { f ->
            val frag = f.toMessageFragment()
            seg.finalizeOnIntrusion(session, frag).forEach { closed ->
                finalized += closed
                session = session.withoutAuthor(closed.authorId)
            }
            session = applyDecision(session, seg.decide(guild, session, frag, policy), finalized)
        }
        session.openBursts.values
            .sortedBy { it.startedAt }
            .forEach { finalized += it.finalize() }

        return finalized.map { burst -> burst.messageIds.map { labelOf.getValue(it) } }
    }

    private fun applyDecision(
        session: BurstSession,
        decision: SegmentDecision,
        finalized: MutableList<UtteranceBurst>,
    ): BurstSession =
        when (decision) {
            is SegmentDecision.StartNew -> session.withOpenBurst(decision.started)
            is SegmentDecision.Append -> session.withOpenBurst(decision.updated)
            is SegmentDecision.FinalizeThenStart -> {
                finalized += decision.finalized
                session.withoutAuthor(decision.finalized.authorId).withOpenBurst(decision.started)
            }
        }

    private data class FixtureFragment(
        val seq: Long,
        val messageId: String,
        val numericId: Long,
        val authorNumericId: Long,
        val atOffsetMs: Long,
    ) {
        fun toMessageFragment(): MessageFragment =
            MessageFragment(
                messageId = MessageId(numericId),
                authorId = AuthorId(authorNumericId),
                channelId = ChannelId(100L),
                sourceSequence = seq,
                occurredAt = BASE_INSTANT.plusMillis(atOffsetMs),
                content = MessageContent.Available("text-$messageId"),
                replyTo = null,
                threadId = null,
                type = com.discordassistant.central.conversation.domain.model.burst.FragmentType.NORMAL,
            )
    }

    private data class Fixture(
        val fragments: List<FixtureFragment>,
        val expectedBursts: List<List<String>>,
    )

    @Suppress("UNCHECKED_CAST")
    private fun loadFixture(): Fixture {
        val file = File("../test-fixtures/nexa/bursts/nickname-fragments.yaml")
        val root = Yaml().load<Map<String, Any?>>(file.readText())
        check(root["schemaVersion"] == "nexa.burst-fixture.v1") { "예상치 못한 burst fixture 스키마 버전" }

        val actorNumeric =
            (root["actors"] as List<Map<String, Any?>>).withIndex().associate { (i, a) ->
                a["actorId"].toString() to (i + 1L)
            }
        val msgNumeric =
            (root["fragments"] as List<Map<String, Any?>>).withIndex().associate { (i, f) ->
                f["messageId"].toString() to
                    (i + 1L)
            }

        val fragments =
            (root["fragments"] as List<Map<String, Any?>>).map { f ->
                FixtureFragment(
                    seq = (f["seq"] as Number).toLong(),
                    messageId = f["messageId"].toString(),
                    numericId = msgNumeric.getValue(f["messageId"].toString()),
                    authorNumericId = actorNumeric.getValue(f["authorId"].toString()),
                    atOffsetMs = (f["atOffsetMs"] as Number).toLong(),
                )
            }
        val expected =
            (root["expectedBursts"] as List<Map<String, Any?>>).map { b ->
                (b["messageIds"] as List<Any?>).map { it.toString() }
            }
        return Fixture(fragments = fragments, expectedBursts = expected)
    }

    companion object {
        private val BASE_INSTANT: Instant = Instant.parse("2026-01-01T11:15:01Z")
    }
}
