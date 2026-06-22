package com.discordassistant.central.participation.domain.service.sim

import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * NEXA 시뮬레이터(어드민 "NEXA 테스트" 레퍼런스 모델) 도메인 단위 테스트.
 *
 * 핵심 acceptance: shadow only(전송 0)·결정론·participation 정책 불변식(직접 질문 답함·호명 없으면 침묵·
 * 멘션 도배 1:1 응답 안함·사람이 먼저 답하면 취소·삭제 시 발사 취소·결함 시 침묵 fallback).
 */
class NexaSimulatorTest {
    private fun nexa() = SimActor("actor-nexa", SimActorKind.NEXA, "니아")

    private fun human(id: String) = SimActor(id, SimActorKind.HUMAN)

    private fun msg(
        seq: Int,
        atMs: Long,
        mid: String,
        author: String,
        mention: Boolean = false,
        thread: String? = null,
    ) = SimEvent(
        seq,
        atMs,
        SimEventType.MESSAGE_CREATE,
        messageId = mid,
        authorId = author,
        content = "x",
        mentionsNexa = mention,
        threadId = thread,
    )

    @Test
    fun `shadow only — 어떤 시나리오에서도 실제 전송은 0이다`() {
        val scenario =
            SimScenario(
                "direct",
                "직접 질문",
                SimChannelKind.MEMBER,
                seed = 1,
                actors = listOf(human("a"), nexa()),
                events = listOf(msg(1, 0, "m-1", "a", mention = true), msg(2, 30_000, "m-2", "a")),
            )
        val result = NexaSimulator(scenario).run()
        assertThat(result.sends).isZero()
        assertThat(result.shadow).isTrue()
    }

    @Test
    fun `직접 호명 질문은 정확히 한 번 SPEAK 로 확정된다`() {
        val scenario =
            SimScenario(
                "direct",
                "직접 질문",
                SimChannelKind.MEMBER,
                seed = 1,
                actors = listOf(human("a"), nexa()),
                events = listOf(msg(1, 0, "m-1", "a", mention = true), msg(2, 30_000, "m-2", "a")),
            )
        val result = NexaSimulator(scenario).run()
        assertThat(result.speakCount).isEqualTo(1)
        val speak = result.decisions.single { it.action == SocialActionKind.SPEAK }
        assertThat(speak.targetMessageId).isEqualTo("m-1")
        assertThat(speak.consumesGenerationQuota).isTrue()
    }

    @Test
    fun `호명 없는 잡담에는 침묵한다(SPEAK 0)`() {
        val scenario =
            SimScenario(
                "silent",
                "조용한 서버",
                SimChannelKind.MEMBER,
                seed = 5,
                actors = listOf(human("a"), human("b"), nexa()),
                events = listOf(msg(1, 0, "m-1", "a"), msg(2, 30_000, "m-2", "b"), msg(3, 70_000, "m-3", "a")),
            )
        val result = NexaSimulator(scenario).run()
        assertThat(result.speakCount).isZero()
    }

    @Test
    fun `같은 thread 에서 사람이 먼저 답하면 예약 SPEAK 가 취소된다`() {
        val scenario =
            SimScenario(
                "answered",
                "이미 답함",
                SimChannelKind.MEMBER,
                seed = 3,
                actors = listOf(human("asker"), human("helper"), nexa()),
                events =
                    listOf(
                        msg(1, 0, "m-1", "asker", mention = true),
                        msg(2, 2_000, "m-2", "helper"),
                    ),
            )
        val result = NexaSimulator(scenario).run()
        assertThat(result.cancelCount).isGreaterThanOrEqualTo(1)
        assertThat(result.speakCount).isZero()
    }

    @Test
    fun `멘션 도배는 1대1로 응답하지 않는다(SPEAK 1회 이하)`() {
        val scenario =
            SimScenario(
                "spam",
                "멘션 도배",
                SimChannelKind.MEMBER,
                seed = 2,
                actors = listOf(human("s"), nexa()),
                events =
                    listOf(
                        msg(1, 0, "m-1", "s", mention = true),
                        msg(2, 1_000, "m-2", "s", mention = true),
                        msg(3, 2_000, "m-3", "s", mention = true),
                        msg(4, 3_000, "m-4", "s", mention = true),
                    ),
            )
        val result = NexaSimulator(scenario).run()
        assertThat(result.speakCount).isLessThanOrEqualTo(1)
    }

    @Test
    fun `예약 SPEAK 대상이 삭제되면 발사를 취소한다`() {
        val scenario =
            SimScenario(
                "del",
                "삭제",
                SimChannelKind.MEMBER,
                seed = 4,
                actors = listOf(human("a"), nexa()),
                events =
                    listOf(
                        msg(1, 0, "m-1", "a", mention = true),
                        SimEvent(2, 1_000, SimEventType.MESSAGE_DELETE, messageId = "m-1"),
                    ),
            )
        val result = NexaSimulator(scenario).run()
        assertThat(result.speakCount).isZero()
        assertThat(result.cancelCount).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `발화 차단 결함이 주입되면 침묵 fallback 한다(SPEAK 0)`() {
        val scenario =
            SimScenario(
                "fault",
                "결함",
                SimChannelKind.MEMBER,
                seed = 10,
                actors = listOf(human("a"), nexa()),
                events =
                    listOf(
                        msg(1, 0, "m-1", "a", mention = true),
                        SimEvent(2, 500, SimEventType.FAULT_INJECT, fault = NexaSimulator.FAULT_RATE_LIMIT),
                    ),
            )
        val result = NexaSimulator(scenario).run()
        assertThat(result.faults).containsExactly(NexaSimulator.FAULT_RATE_LIMIT)
        assertThat(result.speakCount).isZero()
    }

    @Test
    fun `ASSISTANT 채널은 호명이 없어도 답한다`() {
        val scenario =
            SimScenario(
                "assist",
                "AI 질문 채널",
                SimChannelKind.ASSISTANT,
                seed = 7,
                actors = listOf(human("a"), nexa()),
                events = listOf(msg(1, 0, "m-1", "a"), msg(2, 30_000, "m-2", "a")),
            )
        val result = NexaSimulator(scenario).run()
        assertThat(result.speakCount).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `같은 seed·이벤트열이면 결정 궤적이 동일하다(결정론)`() {
        val build = {
            SimScenario(
                "det",
                "결정론",
                SimChannelKind.MEMBER,
                seed = 99,
                actors = listOf(human("a"), nexa()),
                events = listOf(msg(1, 0, "m-1", "a", mention = true), msg(2, 60_000, "m-2", "a")),
            )
        }
        val a = NexaSimulator(build()).run().decisions.map { it.action to it.reason }
        val b = NexaSimulator(build()).run().decisions.map { it.action to it.reason }
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `잘못된 시나리오는 SimScenarioException 으로 거부된다`() {
        assertThatThrownBy {
            SimScenario(
                "bad",
                "NEXA 없음",
                SimChannelKind.MEMBER,
                seed = 1,
                actors = listOf(human("a")),
                events = listOf(msg(1, 0, "m-1", "a")),
            )
        }.isInstanceOf(SimScenarioException::class.java)
    }
}
