package com.discordassistant.central.platform.discord

import com.discordassistant.central.global.observability.NiaRuntimeMetrics
import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.platform.discord.nexa.NiaTurnBoundaryAdmission
import com.discordassistant.central.platform.discord.nexa.ParticipationEmitOutcome
import com.discordassistant.central.platform.discord.nexa.ParticipationMessageSignal
import com.discordassistant.central.platform.discord.nexa.ParticipationTurnOutcome
import com.discordassistant.central.platform.discord.nexa.UnsupportedAttachmentRequest
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NiaMessageContextTest {
    @Test
    fun `PDF 읽기 요청만 미지원으로 잡고 PDF 사용법 질문은 일반 대화로 둔다`() {
        assertThat(
            unsupportedPdfRequest(
                listOf(msg(id = 1L, authorId = 10L, authorLabel = "HJ", content = "PDF 읽을 수 있니")),
                currentMessageId = 1L,
            ),
        ).isEqualTo(UnsupportedAttachmentRequest.PDF_READ)
        assertThat(
            unsupportedPdfRequest(
                listOf(msg(id = 2L, authorId = 10L, authorLabel = "HJ", content = "PDF 읽는 법 알려줘")),
                currentMessageId = 2L,
            ),
        ).isNull()
    }

    @Test
    fun `현재 PDF나 같은 작성자의 바로 전 PDF만 후속 읽기 요청과 연결한다`() {
        assertThat(
            unsupportedPdfRequest(
                listOf(
                    msg(
                        id = 1L,
                        authorId = 10L,
                        authorLabel = "HJ",
                        content = "이거 요약해줘",
                        hasPdfAttachment = true,
                    ),
                ),
                currentMessageId = 1L,
            ),
        ).isEqualTo(UnsupportedAttachmentRequest.PDF_READ)

        val followUp =
            listOf(
                msg(
                    id = 2L,
                    authorId = 10L,
                    authorLabel = "HJ",
                    content = "",
                    hasPdfAttachment = true,
                    createdAtEpochMillis = 1_000L,
                ),
                msg(
                    id = 3L,
                    authorId = 10L,
                    authorLabel = "HJ",
                    content = "이거 읽어줘",
                    createdAtEpochMillis = 7_000L,
                ),
            )
        assertThat(unsupportedPdfRequest(followUp, currentMessageId = 3L))
            .isEqualTo(UnsupportedAttachmentRequest.PDF_READ)
    }

    @Test
    fun `다른 사람 PDF와 오래된 PDF와 무관한 후속 문장은 연결하지 않는다`() {
        val otherAuthor =
            listOf(
                msg(
                    id = 1L,
                    authorId = 11L,
                    authorLabel = "A",
                    content = "",
                    hasPdfAttachment = true,
                    createdAtEpochMillis = 1_000L,
                ),
                msg(id = 2L, authorId = 10L, authorLabel = "B", content = "이거 읽어줘", createdAtEpochMillis = 2_000L),
            )
        assertThat(unsupportedPdfRequest(otherAuthor, currentMessageId = 2L)).isNull()

        val oldPdf =
            listOf(
                msg(
                    id = 3L,
                    authorId = 10L,
                    authorLabel = "HJ",
                    content = "",
                    hasPdfAttachment = true,
                    createdAtEpochMillis = 1_000L,
                ),
                msg(
                    id = 4L,
                    authorId = 10L,
                    authorLabel = "HJ",
                    content = "이거 읽어줘",
                    createdAtEpochMillis = 10 * 60 * 1_000L + 1_001L,
                ),
            )
        assertThat(unsupportedPdfRequest(oldPdf, currentMessageId = 4L)).isNull()

        val unrelated =
            listOf(
                msg(
                    id = 5L,
                    authorId = 10L,
                    authorLabel = "HJ",
                    content = "",
                    hasPdfAttachment = true,
                    createdAtEpochMillis = 1_000L,
                ),
                msg(id = 6L, authorId = 10L, authorLabel = "HJ", content = "오늘 뭐함", createdAtEpochMillis = 2_000L),
            )
        assertThat(unsupportedPdfRequest(unrelated, currentMessageId = 6L)).isNull()
    }

    @Test
    fun `turn boundary admission은 explicit ambient와 fail closed를 운영 메트릭에 남긴다`() {
        val registry = SimpleMeterRegistry()
        val metrics = NiaRuntimeMetrics(registry)

        metrics.recordTurnBoundary(NiaTurnBoundaryAdmission.DEFERRED, explicitlyAddressed = true)
        metrics.recordTurnBoundary(NiaTurnBoundaryAdmission.FAIL_CLOSED, explicitlyAddressed = false)
        metrics.recordTurnBoundary(NiaTurnBoundaryAdmission.BYPASS, explicitlyAddressed = false)

        assertThat(
            registry
                .find("nexa_turn_outcome_total")
                .tags("outcome", "attention_deferred", "stage", "none", "addressing", "explicit")
                .counter()
                ?.count(),
        ).isEqualTo(1.0)
        assertThat(
            registry
                .find("nexa_turn_outcome_total")
                .tags("outcome", "failed", "stage", "none", "addressing", "ambient")
                .counter()
                ?.count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `bridge의 단일 rollout snapshot이 legacy 응답 소유권을 전달한다`() {
        fun owns(
            outcome: ParticipationEmitOutcome,
            ownsTurn: Boolean,
        ): Boolean = ParticipationTurnOutcome(outcome, ownsTurn).ownsTurn

        assertThat(owns(ParticipationEmitOutcome.NotSpeaking(SocialActionKind.IGNORE), ownsTurn = true)).isTrue()
        assertThat(owns(ParticipationEmitOutcome.NotSpeaking(SocialActionKind.WAIT), ownsTurn = true)).isTrue()
        assertThat(owns(ParticipationEmitOutcome.Failed, ownsTurn = true)).isTrue()
        assertThat(owns(ParticipationEmitOutcome.Inactive, ownsTurn = false)).isFalse()
        assertThat(owns(ParticipationEmitOutcome.NotSpeaking(SocialActionKind.IGNORE), ownsTurn = false)).isFalse()
        assertThat(owns(ParticipationEmitOutcome.Failed, ownsTurn = false)).isFalse()
    }

    @Test
    fun `니아야 이름 호명은 직접 질문 프롬프트로 잡는다`() {
        assertThat(niaDirectAddressPrompt("니아야")).isEqualTo("니아야")
        assertThat(niaDirectAddressPrompt("니아야 왜 답 안 해")).isEqualTo("왜 답 안 해")
        assertThat(niaDirectAddressPrompt("무슨소란 니아야")).isEqualTo("무슨소란")
        assertThat(niaDirectAddressPrompt("니아? 지금 있어?")).isEqualTo("니아? 지금 있어?")
        assertThat(niaDirectAddressPrompt("니아야!!!")).isEqualTo("니아야!!!")
    }

    @Test
    fun `NFD 자모 분리로 들어온 호명도 인식한다`() {
        // macOS 등 일부 클라이언트는 "니아야"를 자모 분해(NFD)해 보내 "니아" 매칭이 실패했다(호명 무응답 원인).
        val decomposed = java.text.Normalizer.normalize("니아야", java.text.Normalizer.Form.NFD)
        assertThat(decomposed).isNotEqualTo("니아야") // 실제로 분해됐는지 선확인
        assertThat(niaDirectAddressPrompt(decomposed)).isEqualTo("니아야")
        val decomposedWithBody = java.text.Normalizer.normalize("니아야 왜 답 안 해", java.text.Normalizer.Form.NFD)
        assertThat(niaDirectAddressPrompt(decomposedWithBody)).isEqualTo("왜 답 안 해")
        assertThat(isBareNiaDirectAddress(decomposed)).isTrue()
    }

    @Test
    fun `내용 없는 니아 호명은 마지막 트리거가 비어 있음을 구분한다`() {
        assertThat(isBareNiaDirectAddress("니아야")).isTrue()
        assertThat(isBareNiaDirectAddress("니아야?")).isTrue()
        assertThat(isBareNiaDirectAddress("니아야!!!")).isTrue()
        assertThat(isBareNiaDirectAddress("니아야 왜 답 안 해")).isFalse()
        assertThat(isBareNiaDirectAddress("니아? 지금 있어?")).isFalse()
    }

    @Test
    fun `짧은 니아 호명도 되묻기 전용 땜빵이 아니라 전체 원문 장면 계약을 탄다`() {
        val prompt = buildNiaAddressedPrompt("니아야?", "니아야?")

        assertThat(prompt).contains("[현재 트리거 원문]\n니아야?")
        assertThat(prompt).contains("최근 채널 대화와 현재 요청을 이어서 답한다")
        assertThat(prompt).doesNotContain("[응답 계약]", "[대화 장면 few-shot]", "ASCII 마침표")
        assertThat(prompt).doesNotContain("왜 불렀는지 되물으세요")
        assertThat(prompt).doesNotContain("최근 10번 연속")
    }

    @Test
    fun `니아 답변 직후 후속 발화 후보는 자기 발화 수습 계약으로 감싼다`() {
        val prompt = buildNiaContinuationPrompt("??? 어휘력 없음이 뭔말이야")

        assertThat(prompt).contains("[현재 트리거 원문]\n??? 어휘력 없음이 뭔말이야")
        assertThat(prompt).contains("최근 채널 대화와 현재 요청을 이어서 답한다")
        assertThat(prompt).doesNotContain("같은 말을 반복하지 말고", "오타·짧은 말·거친 말")
    }

    @Test
    fun `니아 답변에 명시적으로 reply한 발화만 후속 후보로 감싼다`() {
        val prompt =
            buildNiaContinuationPromptFromRecentMessages(
                messages =
                    listOf(
                        msg(id = 1, authorId = 10, authorLabel = "HJ", content = "너머함"),
                        msg(id = 2, authorId = 99, authorLabel = "니아", bot = true, content = "어휘력 없음"),
                        msg(
                            id = 3,
                            authorId = 10,
                            authorLabel = "HJ",
                            content = "??? 어휘력 없음이 뭔말이야",
                            replyToMessageId = 2,
                        ),
                    ),
                currentMessageId = 3,
                botUserId = 99,
            )

        assertThat(prompt).isNotNull
        assertThat(prompt!!).contains("[현재 트리거 원문]\n??? 어휘력 없음이 뭔말이야")
        assertThat(prompt).contains("최근 채널 대화와 현재 요청을 이어서 답한다")
    }

    @Test
    fun `니아 답변 뒤에 이어진 타인 대화는 후속 응답으로 강제하지 않는다`() {
        val prompt =
            buildNiaContinuationPromptFromRecentMessages(
                messages =
                    listOf(
                        msg(id = 1, authorId = 99, authorLabel = "니아", bot = true, content = "무슨 일인데"),
                        msg(id = 2, authorId = 10, authorLabel = "HJ", content = "지은아 잠깐 얘기할 게 있어"),
                    ),
                currentMessageId = 2,
                botUserId = 99,
            )

        assertThat(prompt).isNull()
    }

    @Test
    fun `같은 멤버가 니아 직후 질문하면 명시적 reply 없이도 대화 차례가 이어진다`() {
        val continuation =
            deriveNiaTurnContinuation(
                messages =
                    listOf(
                        msg(id = 1, authorId = 10, authorLabel = "HJ", content = "니아야 안녕", createdAtEpochMillis = 0),
                        msg(
                            id = 2,
                            authorId = 99,
                            authorLabel = "니아",
                            bot = true,
                            content = "어 안녕",
                            replyToMessageId = 1,
                            createdAtEpochMillis = 1_000,
                        ),
                        msg(id = 3, authorId = 10, authorLabel = "HJ", content = "머하노", createdAtEpochMillis = 194_000),
                    ),
                currentMessageId = 3,
                botUserId = 99,
                currentRepliesToHuman = false,
            )

        assertThat(continuation.likely).isTrue()
        assertThat(continuation.lastNiaSpokeAgeSeconds).isEqualTo(193.0)
    }

    @Test
    fun `응답 생성 중 다른 멤버가 끼어들어도 니아의 원래 reply 대상이 차례를 이어받는다`() {
        val continuation =
            deriveNiaTurnContinuation(
                messages =
                    listOf(
                        msg(id = 1, authorId = 10, authorLabel = "HJ", content = "니아야 안녕", createdAtEpochMillis = 0),
                        msg(id = 2, authorId = 11, authorLabel = "서연", content = "잠깐", createdAtEpochMillis = 500),
                        msg(
                            id = 3,
                            authorId = 99,
                            authorLabel = "니아",
                            bot = true,
                            content = "어 안녕",
                            replyToMessageId = 1,
                            createdAtEpochMillis = 1_000,
                        ),
                        msg(id = 4, authorId = 10, authorLabel = "HJ", content = "머하노", createdAtEpochMillis = 2_000),
                    ),
                currentMessageId = 4,
                botUserId = 99,
                currentRepliesToHuman = false,
            )

        assertThat(continuation.likely).isTrue()
    }

    @Test
    fun `내용 없는 첨부 메시지도 끼어든 대화 차례로 보존한다`() {
        val continuation =
            deriveNiaTurnContinuation(
                messages =
                    listOf(
                        msg(id = 1, authorId = 10, authorLabel = "HJ", content = "니아야 안녕", createdAtEpochMillis = 0),
                        msg(
                            id = 2,
                            authorId = 99,
                            authorLabel = "니아",
                            bot = true,
                            content = "어 안녕",
                            replyToMessageId = 1,
                            createdAtEpochMillis = 1_000,
                        ),
                        msg(id = 3, authorId = 11, authorLabel = "서연", content = "", createdAtEpochMillis = 2_000),
                        msg(id = 4, authorId = 10, authorLabel = "HJ", content = "머하노", createdAtEpochMillis = 3_000),
                    ),
                currentMessageId = 4,
                botUserId = 99,
                currentRepliesToHuman = false,
            )

        assertThat(continuation.likely).isFalse()
    }

    @Test
    fun `니아 다음 메시지라도 다른 멤버거나 사람에게 답한 말이면 차례를 이어받지 않는다`() {
        val messages =
            listOf(
                msg(id = 1, authorId = 10, authorLabel = "HJ", content = "니아야 안녕", createdAtEpochMillis = 0),
                msg(
                    id = 2,
                    authorId = 99,
                    authorLabel = "니아",
                    bot = true,
                    content = "어 안녕",
                    replyToMessageId = 1,
                    createdAtEpochMillis = 1_000,
                ),
                msg(id = 3, authorId = 11, authorLabel = "서연", content = "HJ야 뭐해", createdAtEpochMillis = 2_000),
            )

        val differentMember = deriveNiaTurnContinuation(messages, 3, 99, currentRepliesToHuman = false)
        val replyToHuman = deriveNiaTurnContinuation(messages.dropLast(1) + messages.last().copy(authorId = 10), 3, 99, true)
        val staleSameMember =
            deriveNiaTurnContinuation(
                messages.dropLast(1) + messages.last().copy(authorId = 10, createdAtEpochMillis = 700_000),
                3,
                99,
                currentRepliesToHuman = false,
            )

        assertThat(differentMember.likely).isFalse()
        assertThat(replyToHuman.likely).isFalse()
        assertThat(staleSameMember.likely).isFalse()
    }

    @Test
    fun `직접 호명 프롬프트는 현재 원문과 호명 제거 내용을 함께 전달한다`() {
        val prompt = buildNiaAddressedPrompt("무슨소란   니아야\n진짜?", "무슨소란   \n진짜?")

        assertThat(prompt).contains("[현재 트리거 원문]\n무슨소란   니아야\n진짜?")
        assertThat(prompt).contains("[현재 트리거에서 분리한 직접 요청]\n무슨소란   \n진짜?")
    }

    @Test
    fun `자동응답 프롬프트도 마지막 메시지가 아니라 원문 장면 계약을 탄다`() {
        val prompt = buildNiaAutoRespondPrompt("돈들어")

        assertThat(prompt).contains("[트리거 출처]\nauto-respond-channel")
        assertThat(prompt).contains("최근 채널 대화와 현재 요청을 이어서 답한다")
    }

    @Test
    fun `니아 채팅 스타일은 문장 끝 ASCII 마침표를 제거하되 숫자 구조는 보존한다`() {
        val reply =
            Reply(
                content = "그거 내가 먼저 한 말인데.\n버전 v1.2는 그대로.",
                ephemeral = false,
                pseudoStream = ReplyPseudoStream(editIntervalMs = 1, snapshots = listOf("뭐 갑자기.", "아니... 왜")),
            ).withNiaChatStyle()

        assertThat(reply.content).isEqualTo("그거 내가 먼저 한 말인데\n버전 v1.2는 그대로")
        assertThat(reply.pseudoStream!!.snapshots).containsExactly("뭐 갑자기", "아니 왜")
    }

    @Test
    fun `문장 속 니아 언급은 직접 호명으로 오인하지 않는다`() {
        assertThat(niaDirectAddressPrompt("너는 니아야")).isNull()
        assertThat(niaDirectAddressPrompt("니아는 어떤 캐릭터야?")).isNull()
        assertThat(niaDirectAddressPrompt("\"니아야\" 라고 불렀어")).isNull()
    }

    @Test
    fun `최근 채널 맥락은 원문을 수정하지 않고 멀티턴으로 전달한다`() {
        val turns =
            buildDiscordRecentContextTurns(
                messages =
                    listOf(
                        msg(id = 1, authorId = 10, authorLabel = "HJ", content = "니아야\n니아야   니아야"),
                        msg(id = 2, authorId = 99, authorLabel = "니아", bot = true, content = "응?\n왜 불러"),
                        msg(id = 3, authorId = 11, authorLabel = "yeon", content = "니아야 싸가지가 없네"),
                        msg(id = 4, authorId = 10, authorLabel = "HJ", content = "CURRENT_TRIGGER_니아야"),
                    ),
                currentMessageId = 4,
                botUserId = 99,
            )

        assertThat(turns.map { it.role }).containsExactly("user", "assistant", "user", "user")
        assertThat(turns[0].content).contains("speaker=HJ")
        assertThat(turns[0].content).contains("content:\n니아야\n니아야   니아야")
        assertThat(turns[1].content).isEqualTo("응?\n왜 불러")
        assertThat(turns[2].content).contains("speaker=yeon")
        assertThat(turns[2].content).contains("content:\n니아야 싸가지가 없네")
        assertThat(turns.joinToString("\n") { it.content }).doesNotContain("CURRENT_TRIGGER_니아야")
        assertThat(turns.last().content).isEqualTo("위 최근 채널 대화 원문을 현재 요청의 맥락으로 사용한다.")
    }

    @Test
    fun `최근 채널 맥락은 오래된 원문부터 char budget 밖이면 버린다`() {
        val turns =
            buildDiscordRecentContextTurns(
                messages =
                    listOf(
                        msg(id = 1, authorId = 10, authorLabel = "HJ", content = "오래된말".repeat(80)),
                        msg(id = 2, authorId = 11, authorLabel = "yeon", content = "바로 앞 말"),
                        msg(id = 3, authorId = 10, authorLabel = "HJ", content = "CURRENT"),
                    ),
                currentMessageId = 3,
                botUserId = 99,
                maxTurns = 10,
                maxRawChars = 140,
            )

        val joined = turns.joinToString("\n") { it.content }
        assertThat(joined).contains("바로 앞 말")
        assertThat(joined).doesNotContain("오래된말")
        assertThat(joined).doesNotContain("CURRENT")
    }

    @Test
    fun `합성 니아 답변 뒤 실제 Discord echo가 오면 같은 답변을 두 번 보관하지 않는다`() {
        val buffer = ArrayDeque<DiscordRecentPromptMessage>()
        appendRecentPromptMessage(
            buffer,
            msg(
                id = -1_000L,
                authorId = 99L,
                authorLabel = "니아",
                content = "응 여기 있어",
                bot = true,
                replyToMessageId = 10L,
                createdAtEpochMillis = 1_000L,
            ),
            limit = 20,
        )

        appendRecentPromptMessage(
            buffer,
            msg(
                id = 11L,
                authorId = 99L,
                authorLabel = "니아",
                content = "응 여기 있어",
                bot = true,
                replyToMessageId = 10L,
                createdAtEpochMillis = 1_500L,
            ),
            limit = 20,
        )

        assertThat(buffer.map { it.id }).containsExactly(11L)
    }

    @Test
    fun `delayed snapshot용 최근 버퍼는 edit을 제자리 교체하고 delete를 제거한다`() {
        val buffer =
            ArrayDeque(
                listOf(
                    msg(id = 1L, authorId = 10L, authorLabel = "HJ", content = "수정 전"),
                    msg(id = 2L, authorId = 11L, authorLabel = "SY", content = "그대로"),
                ),
            )

        assertThat(
            updateRecentPromptMessage(
                buffer,
                msg(id = 1L, authorId = 10L, authorLabel = "HJ", content = "수정 후"),
            ),
        ).isTrue()
        assertThat(buffer.map { it.content }).containsExactly("수정 후", "그대로")
        assertThat(removeRecentPromptMessage(buffer, 1L)).isTrue()
        assertThat(buffer.map { it.id }).containsExactly(2L)
    }

    @Test
    fun `delayed target이 최근 버퍼에서 밀려나도 원래 원문과 직접 요청 성격을 보존한다`() {
        val signal =
            ParticipationMessageSignal(
                guildId = 10L,
                channelId = 20L,
                messageId = 1L,
                userId = 30L,
                mentioned = true,
                recentTurns = emptyList(),
                triggerText = "니아야 질문",
                rawText = "니아야 질문",
                sceneSeq = 0L,
                contextVersion = 0L,
                seed = 1L,
                turnGeneration = 120L,
            )
        val refreshed =
            refreshDelayedTriggerSignal(
                signal = signal,
                recentMessages =
                    listOf(
                        msg(id = 119L, authorId = 31L, authorLabel = "SY", content = "다른 말"),
                        msg(id = 120L, authorId = 32L, authorLabel = "JH", content = "최신 말"),
                    ),
                selfId = 99L,
            )

        assertThat(refreshed.messageId).isEqualTo(1L)
        assertThat(refreshed.turnGeneration).isEqualTo(120L)
        assertThat(refreshed.rawText).isEqualTo("니아야 질문")
        assertThat(refreshed.mentioned).isTrue()
    }

    @Test
    fun `같은 작성자의 후속 문장은 멘션 문자가 없어도 묶음의 직접 요청 성격을 잃지 않는다`() {
        val inherited =
            ParticipationMessageSignal(
                guildId = 10L,
                channelId = 20L,
                messageId = 2L,
                userId = 30L,
                mentioned = true,
                recentTurns = emptyList(),
                triggerText = "몇 시야",
                rawText = "몇 시야",
                sceneSeq = 0L,
                contextVersion = 0L,
                seed = 2L,
                turnGeneration = 2L,
            )
        val refreshed =
            refreshDelayedTriggerSignal(
                signal = inherited,
                recentMessages = listOf(msg(id = 2L, authorId = 30L, authorLabel = "HJ", content = "몇 시야")),
                selfId = 99L,
            )

        assertThat(refreshed.rawText).isEqualTo("몇 시야")
        assertThat(refreshed.mentioned).isTrue()
    }

    @Test
    fun `thread receive edit delete는 같은 routing과 raw context scope를 사용한다`() {
        val thread = discordMessageScope(channelId = 200L, isThread = true)
        val channel = discordMessageScope(channelId = 100L, isThread = false)

        assertThat(thread.routingId).isEqualTo(200L)
        assertThat(thread.channelId).isEqualTo(200L)
        assertThat(thread.threadId).isEqualTo(200L)
        assertThat(channel.routingId).isEqualTo(100L)
        assertThat(channel.threadId).isNull()
    }

    private fun msg(
        id: Long,
        authorId: Long,
        authorLabel: String,
        content: String,
        bot: Boolean = false,
        replyToMessageId: Long? = null,
        createdAtEpochMillis: Long = id,
        hasPdfAttachment: Boolean = false,
    ) = DiscordRecentPromptMessage(
        id = id,
        authorId = authorId,
        authorLabel = authorLabel,
        bot = bot,
        content = content,
        createdAtEpochMillis = createdAtEpochMillis,
        replyToMessageId = replyToMessageId,
        hasPdfAttachment = hasPdfAttachment,
    )
}
