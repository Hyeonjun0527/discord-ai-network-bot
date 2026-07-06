package com.discordassistant.central.platform.discord.nexa

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [ParticipationSignalDeriver] 단위 테스트 — 채널 버퍼에서 히스토리 신호(A4 중복·B1 burst 미완·B17 사적 핑퐁) 도출.
 */
class ParticipationSignalDeriverTest {
    private fun msg(
        speaker: String,
        text: String,
        tsMs: Long,
        mentionsNia: Boolean = false,
    ) = ParticipationSignalDeriver.HumanMessage(speaker, text, tsMs, mentionsNia)

    @Test
    fun `첫 메시지는 모든 신호가 보수적 기본값`() {
        val d = ParticipationSignalDeriver()
        val s = d.deriveAndRecord(1L, msg("u1", "안녕", 1_000))
        assertThat(s.duplicateOfPrevHuman).isFalse()
        assertThat(s.burstIncomplete).isFalse()
        assertThat(s.priorHumanSpeakerLabels).isEmpty()
        assertThat(s.firstMessageText).isNull()
        assertThat(s.conversationMentionsNia).isFalse()
    }

    @Test
    fun `직전 사람 메시지와 같은 본문이면 duplicate(A4)`() {
        val d = ParticipationSignalDeriver()
        d.deriveAndRecord(1L, msg("u1", "ㅋㅋㅋ", 1_000))
        val s = d.deriveAndRecord(1L, msg("u2", "ㅋㅋㅋ", 2_000))
        assertThat(s.duplicateOfPrevHuman).isTrue()
    }

    @Test
    fun `같은 화자가 짧은 간격으로 이어가면 burstIncomplete(B1)`() {
        val d = ParticipationSignalDeriver(burstGapMs = 7_000)
        d.deriveAndRecord(1L, msg("u1", "그래서", 1_000))
        val s = d.deriveAndRecord(1L, msg("u1", "내 생각엔", 3_000)) // 같은 화자, 2s 간격(<7s).
        assertThat(s.burstIncomplete).isTrue()
    }

    @Test
    fun `다른 화자면 burstIncomplete 아님`() {
        val d = ParticipationSignalDeriver(burstGapMs = 7_000)
        d.deriveAndRecord(1L, msg("u1", "그래서", 1_000))
        val s = d.deriveAndRecord(1L, msg("u2", "응?", 2_000))
        assertThat(s.burstIncomplete).isFalse()
    }

    @Test
    fun `간격이 burstGap 보다 크면 burstIncomplete 아님`() {
        val d = ParticipationSignalDeriver(burstGapMs = 7_000)
        d.deriveAndRecord(1L, msg("u1", "그래서", 1_000))
        val s = d.deriveAndRecord(1L, msg("u1", "음", 1_000 + 10_000))
        assertThat(s.burstIncomplete).isFalse()
    }

    @Test
    fun `사적 핑퐁 신호(화자집합·첫 메시지·니아 미호명)를 도출한다(B17)`() {
        val d = ParticipationSignalDeriver()
        d.deriveAndRecord(1L, msg("u1", "준호야 봤어?", 1_000))
        d.deriveAndRecord(1L, msg("u2", "응 봤어", 2_000))
        val s = d.deriveAndRecord(1L, msg("u1", "재밌더라", 3_000))
        assertThat(s.firstMessageText).isEqualTo("준호야 봤어?")
        assertThat(s.priorHumanSpeakerLabels).contains("u2") // 트리거 화자(u1) 제외.
        assertThat(s.conversationMentionsNia).isFalse()
    }

    @Test
    fun `대화 중 니아 호명이 있으면 conversationMentionsNia true`() {
        val d = ParticipationSignalDeriver()
        d.deriveAndRecord(1L, msg("u1", "니아 어때", 1_000, mentionsNia = true))
        val s = d.deriveAndRecord(1L, msg("u2", "글쎄", 2_000))
        assertThat(s.conversationMentionsNia).isTrue()
    }

    @Test
    fun `채널 버퍼는 maxPerChannel 로 제한된다`() {
        val d = ParticipationSignalDeriver(maxPerChannel = 3)
        repeat(10) { i -> d.deriveAndRecord(1L, msg("u1", "m$i", (i + 1) * 1_000L)) }
        // 11번째 도출 시 firstMessageText 는 버퍼 잘림으로 최근 것만 남는다(첫 m0 가 아님).
        val s = d.deriveAndRecord(1L, msg("u2", "끝", 99_000))
        assertThat(s.firstMessageText).isNotEqualTo("m0")
    }

    @Test
    fun `유휴 경계는 maxTrackedContexts 상한으로 축출된다`() {
        val d = ParticipationSignalDeriver(maxTrackedContexts = 1)
        d.deriveAndRecord(1L, msg("u1", "채널1 첫 메시지", 1_000))
        // 새 채널이 상한(1)을 넘겨 이전 채널(1) 버퍼를 축출한다(맵 무한 성장 방지).
        d.deriveAndRecord(2L, msg("u2", "채널2", 2_000))
        // 채널 1 은 축출됐으므로 다시 기록하면 빈 버퍼에서 시작 → 첫 메시지 신호(도출 semantics 자체는 불변).
        val s = d.deriveAndRecord(1L, msg("u1", "채널1 재등장", 3_000))
        assertThat(s.firstMessageText).isNull()
    }

    @Test
    fun `채널별로 버퍼가 분리된다`() {
        val d = ParticipationSignalDeriver()
        d.deriveAndRecord(1L, msg("u1", "A", 1_000))
        val s = d.deriveAndRecord(2L, msg("u1", "B", 1_000))
        // 채널 2 는 비어 있었으므로 첫 메시지 신호.
        assertThat(s.firstMessageText).isNull()
    }

    @Test
    fun `같은 부모 채널이어도 thread forum boundary 가 다르면 사적 핑퐁 히스토리를 섞지 않는다`() {
        val d = ParticipationSignalDeriver()
        val parentChannel = 10L
        val threadA = 100L
        val threadB = 200L

        d.deriveAndRecord(parentChannel, contextBoundaryId = threadA, trigger = msg("u1", "준호야 봤어?", 1_000))
        d.deriveAndRecord(parentChannel, contextBoundaryId = threadA, trigger = msg("u2", "응 봤어", 2_000))

        val s = d.deriveAndRecord(parentChannel, contextBoundaryId = threadB, trigger = msg("u1", "여긴 다른 글타래", 3_000))

        assertThat(s.firstMessageText).isNull()
        assertThat(s.priorHumanSpeakerLabels).isEmpty()
        assertThat(s.conversationMentionsNia).isFalse()
    }
}
