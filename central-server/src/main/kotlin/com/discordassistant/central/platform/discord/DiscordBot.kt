package com.discordassistant.central.platform.discord

import com.discordassistant.central.channelai.application.AutoRespondChannelRegistry
import com.discordassistant.central.channelai.application.ChannelAiProfileService
import com.discordassistant.central.global.i18n.I18n
import com.discordassistant.central.global.i18n.Messages
import com.discordassistant.central.global.observability.NiaDispatchEvent
import com.discordassistant.central.global.observability.NiaDispatchOutcome
import com.discordassistant.central.global.observability.NiaIngressSource
import com.discordassistant.central.global.observability.NiaRuntimeMetrics
import com.discordassistant.central.global.observability.NiaTurnAddressing
import com.discordassistant.central.global.observability.NiaTurnMetricOutcome
import com.discordassistant.central.guild.application.GuildRemovalCleanupService
import com.discordassistant.central.onboarding.adapter.outbound.persistence.GuildOnboardingOptOutRepository
import com.discordassistant.central.onboarding.application.GuildHistoryBackfillService
import com.discordassistant.central.participation.application.NexaParticipationFlagService
import com.discordassistant.central.participation.application.catchup.NiaCatchUpAdmission
import com.discordassistant.central.participation.application.catchup.NiaCatchUpCadence
import com.discordassistant.central.participation.application.catchup.NiaCatchUpScope
import com.discordassistant.central.platform.discord.command.SettingsCommandHandler
import com.discordassistant.central.platform.discord.nexa.NiaTurnBoundaryAdmission
import com.discordassistant.central.platform.discord.nexa.NiaTurnBoundaryCoordinator
import com.discordassistant.central.platform.discord.nexa.NiaTurnGenerationTracker
import com.discordassistant.central.platform.discord.nexa.ParticipationMessageSignal
import com.discordassistant.central.platform.discord.nexa.ParticipationTurnOutcome
import com.discordassistant.central.platform.discord.nexa.ScheduledExecutorNiaTurnBoundaryScheduler
import com.discordassistant.central.platform.discord.nexa.UnsupportedAttachmentRequest
import com.discordassistant.central.platform.discord.nexa.toNiaCatchUpJudgeResult
import com.discordassistant.central.platform.discord.nexa.toNiaCatchUpMessage
import com.discordassistant.central.quota.application.RateLimiter
import com.discordassistant.central.routing.application.CloudTurn
import com.discordassistant.central.speech.domain.model.LocalSpeechTemplate
import com.discordassistant.central.speech.domain.model.SpeechImageInput
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.MessageType
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.channel.ChannelDeleteEvent
import net.dv8tion.jda.api.events.guild.GuildJoinEvent
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.events.message.MessageDeleteEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.MessageUpdateEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.events.session.ShutdownEvent
import net.dv8tion.jda.api.events.user.UserTypingEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.Interaction
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DM(봇과의 1:1 DM)에서 쓸 수 있는 명령: **읽기/안내 전용**(모델 조회·사용량·프라이버시·도움말 등).
 * AI 호출(/ask)과 기여(/provider-*)·관리/정책 명령은 **길드 전용** — 그 서버 멤버만 가능(멤버십 게이트).
 * 이 명령들은 글로벌 + setGuildOnly(false)(봇 DM 허용)로 등록된다. DM 컨텍스트의 guildId 는 DM_SCOPE sentinel.
 * (JDA 5.2.1 은 신 user-install/InteractionContextType 미지원 — 친구끼리 DM/임의 서버 사용은 JDA 업그레이드 필요.)
 */
private val DM_COMMANDS =
    // DM 허용은 **읽기/안내 명령만**. AI 호출(/ask)도, 기여(/provider-*)도 DM 에서 제외한다 —
    // AI 호출은 그 서버 멤버만, 기여도 서버 단위로만(멤버십 게이트). 길드 슬래시는 디스코드가
    // 멤버에게만 노출하므로 별도 검사 불필요. DM 은 멤버 신원이 없어 이 둘을 차단한다.
    setOf(
        "my-usage",
        "nia",
        "privacy",
        "help",
        "menu",
        "settings",
    )

private const val RECENT_CHANNEL_CONTEXT_FETCH_LIMIT = 120
private const val RECENT_CHANNEL_CONTEXT_CHANNEL_CACHE_LIMIT = 100
private const val RECENT_CHANNEL_CONTEXT_MAX_TURNS = 80
private const val RECENT_CHANNEL_CONTEXT_MAX_RAW_CHARS = 12_000
private const val NIA_TURN_CONTINUATION_MAX_AGE_MILLIS = 10 * 60 * 1_000L
private const val DISCORD_SHUTDOWN_GRACE_MILLIS = 2_000L
private const val DISCORD_FORCED_SHUTDOWN_GRACE_MILLIS = 2_000L
private val NIA_DIRECT_ADDRESS_PREFIX = Regex("""^\s*니아(?:야|아)?(?=$|[\s.!?~,，。！？])""")
private val NIA_DIRECT_ADDRESS_SUFFIX = Regex("""(?:^|\s)니아(?:야|아)?[\s.!?~,，。！？]*$""")
private val NIA_DIRECT_ADDRESS_DECORATION = Regex("""^[\s.!?~,，。！？]*$""")
private val NIA_DIRECT_ADDRESS_LEADING_PUNCTUATION = setOf('!', '?', '.', ',', '~', '！', '？', '。', '，')
private val NIA_DIRECT_ADDRESS_NON_VOCATIVE_PREFIX_ENDINGS = listOf("은", "는", "이", "가", "을", "를")
private val NIA_SENTENCE_PERIOD_RUN = Regex("""(?<!\d)\.+(?!\d)(?=\s|$)""")
private val NIA_PDF_TERM = Regex("""(?i)(?:\bpdf\b|피디에프)""")
private val NIA_PDF_READ_ACTION =
    Regex(
        """(?:읽어|읽을\s*수|읽어\s*줄|읽어줄|봐\s*줘|봐줄|요약(?:해|할)|설명(?:해|할)|분석(?:해|할)|""" +
            """정리(?:해|할)|핵심.{0,12}(?:뽑|알려)|내용.{0,12}(?:알려|답))""",
    )
private val NIA_ATTACHED_FILE_INSPECTION = Regex("""(?:이거|이\s*파일|첨부(?:한|된)?\s*(?:거|파일)?).{0,12}(?:뭐야|뭔지)""")

/**
 * 한글 자모 분리(NFD)로 들어온 입력을 음절(NFC)로 합친다. macOS 등 일부 클라이언트는 "니아야"를
 * "ㄴ+ㅣ+ㅇ+ㅏ…" 형태(decomposed)로 보내 "니아" 리터럴/정규식 매칭이 실패한다(호명 무응답 원인).
 * 이름 호명 감지는 반드시 이 정규화를 거쳐 NFD/NFC 표기 차이에 영향받지 않게 한다.
 */
internal fun String.nfc(): String = java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFC)

internal fun niaDirectAddressPrompt(raw: String): String? {
    val trimmed = raw.nfc().trim()
    if (NIA_DIRECT_ADDRESS_PREFIX.containsMatchIn(trimmed)) {
        val stripped =
            NIA_DIRECT_ADDRESS_PREFIX
                .replaceFirst(trimmed, "")
                .trim()
        if (stripped.firstOrNull() in NIA_DIRECT_ADDRESS_LEADING_PUNCTUATION) return trimmed
        return stripped.ifBlank { trimmed }
    }

    if (!NIA_DIRECT_ADDRESS_SUFFIX.containsMatchIn(trimmed)) return null
    val stripped =
        NIA_DIRECT_ADDRESS_SUFFIX
            .replaceFirst(trimmed, "")
            .trim()
    if (stripped.isBlank()) return trimmed
    if (NIA_DIRECT_ADDRESS_NON_VOCATIVE_PREFIX_ENDINGS.any { stripped.endsWith(it) }) return null
    return stripped
}

internal fun isBareNiaDirectAddress(raw: String): Boolean {
    val trimmed = raw.nfc().trim()
    if (!NIA_DIRECT_ADDRESS_PREFIX.containsMatchIn(trimmed)) return false
    val rest = NIA_DIRECT_ADDRESS_PREFIX.replaceFirst(trimmed, "")
    return NIA_DIRECT_ADDRESS_DECORATION.matches(rest)
}

internal fun stripNiaSentencePeriods(text: String): String =
    text
        .lines()
        .joinToString("\n") { line -> line.replace(NIA_SENTENCE_PERIOD_RUN, "").trimEnd() }
        .trimEnd()

internal fun Reply.withNiaChatStyle(): Reply =
    copy(
        content = stripNiaSentencePeriods(content),
        pseudoStream =
            pseudoStream?.copy(
                snapshots = pseudoStream.snapshots.map(::stripNiaSentencePeriods),
                warning = pseudoStream.warning?.let(::stripNiaSentencePeriods),
            ),
    )

internal fun buildNiaSceneResponsePrompt(
    raw: String,
    addressedContent: String?,
    triggerSource: String,
): String =
    buildString {
        val currentRaw = raw.trim().ifBlank { raw }
        val currentCore = addressedContent?.trim().orEmpty()
        appendLine("[현재 트리거 원문]")
        appendLine(currentRaw)
        appendLine()
        appendLine("[현재 트리거에서 분리한 직접 요청]")
        appendLine(currentCore.ifBlank { "(비어 있음: 이 메시지만 용건 없음으로 단정하지 말고 최근 원문 장면에서 이미 나온 요구·불만·질문을 찾는다)" })
        appendLine()
        appendLine("[트리거 출처]")
        appendLine(triggerSource)
        appendLine()
        appendLine("[응답 계약]")
        appendLine("- 마지막 트리거 하나만 보지 말고, 함께 제공되는 최근 채널 대화 원문 전체를 1차 소스로 판단한다.")
        appendLine("- 사용자가 이전부터 답변·반응을 요구했으면 마지막이 단순 호명이어도 그 이전 요구에 이어 짧게 답한다.")
        appendLine("- 사용자가 니아의 직전 말을 되묻거나 따지면, 같은 말을 반복하지 말고 그 말의 뜻을 설명하거나 이상했으면 짧게 인정하고 수습한다.")
        appendLine("- 오타·짧은 말·거친 말은 능력 비난으로 받아치지 말고, 전체 흐름상 의도를 먼저 추정한다. 불확실하면 짧게 확인한다.")
        appendLine("- 없는 사건·소란·관계를 지어내지 않는다. 감정지원 같은 라벨을 먼저 붙이지 말고 원문에 실제로 이어지는 말을 고른다.")
        appendLine("- 문장 끝에 ASCII 마침표(.)를 붙이지 않는다.")
        appendLine()
        appendLine("[대화 장면 few-shot]")
        appendLine("1. 앞 원문에 '대답해줘', '심심하다'가 있고 마지막 트리거가 '니아야'뿐이면, '왜 불러'가 아니라 앞 요구에 답한다.")
        appendLine("2. 니아가 이상한 표현을 한 뒤 사용자가 '그게 뭔말이야'라고 물으면, '내가 먼저 한 말인데'를 반복하지 말고 표현 의도를 설명하거나 수습한다.")
        append("3. 사용자가 거칠게 말해도 상대 말을 그대로 반사하지 말고, 장면에 맞는 짧은 한마디로 받아친다.")
    }

internal fun buildNiaContinuationPrompt(raw: String): String =
    buildNiaSceneResponsePrompt(raw = raw, addressedContent = null, triggerSource = "nia-continuation-after-recent-nia-reply")

internal fun buildNiaAddressedPrompt(
    raw: String,
    addressedContent: String,
): String =
    buildNiaSceneResponsePrompt(
        raw = raw,
        addressedContent = addressedContent.takeUnless { isBareNiaDirectAddress(raw) },
        triggerSource = "nia-direct-address",
    )

internal fun buildNiaAutoRespondPrompt(raw: String): String =
    buildNiaSceneResponsePrompt(raw = raw, addressedContent = raw.trim(), triggerSource = "auto-respond-channel")

internal fun buildNiaContinuationPromptFromRecentMessages(
    messages: List<DiscordRecentPromptMessage>,
    currentMessageId: Long,
    botUserId: Long,
): String? {
    val current = messages.firstOrNull { it.id == currentMessageId } ?: return null
    val content = current.content
    val trimmed = content.trim()
    if (trimmed.isBlank() || trimmed.startsWith(".")) return null
    val repliedMessageId = current.replyToMessageId ?: return null
    val repliedMessage = messages.firstOrNull { it.id == repliedMessageId } ?: return null
    if (!repliedMessage.bot || repliedMessage.authorId != botUserId) return null
    return buildNiaContinuationPrompt(content)
}

internal data class DiscordRecentPromptMessage(
    val id: Long,
    val authorId: Long,
    val authorLabel: String,
    val bot: Boolean,
    val content: String,
    val createdAtEpochMillis: Long,
    val replyToMessageId: Long? = null,
    /** 파일명·URL·본문은 저장하지 않고 PDF 존재 여부만 보존한다. */
    val hasPdfAttachment: Boolean = false,
)

internal fun unsupportedPdfRequest(
    messages: List<DiscordRecentPromptMessage>,
    currentMessageId: Long,
): UnsupportedAttachmentRequest? {
    val currentIndex = messages.indexOfFirst { it.id == currentMessageId }
    if (currentIndex < 0) return null
    val current = messages[currentIndex]
    if (current.bot) return null
    val text =
        current.content
            .nfc()
            .trim()
            .lowercase()
    if (text.isBlank()) return null

    val explicitPdfRequest = NIA_PDF_TERM.containsMatchIn(text) && NIA_PDF_READ_ACTION.containsMatchIn(text)
    if (explicitPdfRequest) return UnsupportedAttachmentRequest.PDF_READ

    val immediatelyPreviousHuman =
        messages
            .subList(0, currentIndex)
            .lastOrNull { !it.bot }
            ?.takeIf { previous ->
                previous.authorId == current.authorId &&
                    previous.hasPdfAttachment &&
                    (current.createdAtEpochMillis - previous.createdAtEpochMillis) in 0..NIA_TURN_CONTINUATION_MAX_AGE_MILLIS
            }
    val attachedPdfContext = current.hasPdfAttachment || immediatelyPreviousHuman != null
    if (!attachedPdfContext) return null
    return if (
        NIA_PDF_READ_ACTION.containsMatchIn(text) ||
        NIA_ATTACHED_FILE_INSPECTION.containsMatchIn(text)
    ) {
        UnsupportedAttachmentRequest.PDF_READ
    } else {
        null
    }
}

internal fun refreshDelayedTriggerSignal(
    signal: ParticipationMessageSignal,
    recentMessages: List<DiscordRecentPromptMessage>,
    selfId: Long,
): ParticipationMessageSignal {
    val rawText =
        recentMessages
            .firstOrNull { it.id == signal.messageId }
            ?.content
            ?.trim()
            ?: signal.rawText
    val mentioned =
        signal.mentioned ||
            Regex("<@!?$selfId>").containsMatchIn(rawText) ||
            niaDirectAddressPrompt(rawText) != null
    return signal.copy(
        mentioned = mentioned,
        triggerText = rawText.take(500),
        rawText = rawText,
    )
}

internal fun appendRecentPromptMessage(
    buffer: ArrayDeque<DiscordRecentPromptMessage>,
    message: DiscordRecentPromptMessage,
    limit: Int,
) {
    require(limit > 0) { "recent prompt message limit은 양수여야 한다" }
    if (message.bot && message.id > 0) {
        buffer.removeIf { prior ->
            prior.bot &&
                prior.id < 0 &&
                prior.authorId == message.authorId &&
                prior.content == message.content &&
                prior.replyToMessageId == message.replyToMessageId &&
                kotlin.math.abs(prior.createdAtEpochMillis - message.createdAtEpochMillis) <= SYNTHETIC_REPLY_ECHO_WINDOW_MILLIS
        }
    }
    buffer.addLast(message)
    while (buffer.size > limit) buffer.removeFirst()
}

internal fun updateRecentPromptMessage(
    buffer: ArrayDeque<DiscordRecentPromptMessage>,
    message: DiscordRecentPromptMessage,
): Boolean {
    if (buffer.none { it.id == message.id }) return false
    buffer.removeIf { it.id == message.id }
    val ordered = (buffer + message).sortedWith(compareBy<DiscordRecentPromptMessage> { it.createdAtEpochMillis }.thenBy { it.id })
    buffer.clear()
    buffer.addAll(ordered)
    return true
}

internal fun removeRecentPromptMessage(
    buffer: ArrayDeque<DiscordRecentPromptMessage>,
    messageId: Long,
): Boolean = buffer.removeIf { it.id == messageId }

internal data class DiscordMessageScope(
    val channelId: Long,
    val threadId: Long?,
) {
    val routingId: Long = threadId ?: channelId
}

internal fun discordMessageScope(
    channelId: Long,
    isThread: Boolean,
): DiscordMessageScope =
    DiscordMessageScope(
        channelId = channelId,
        threadId = channelId.takeIf { isThread },
    )

private const val SYNTHETIC_REPLY_ECHO_WINDOW_MILLIS: Long = 60_000

private val DiscordChannelEventAdmission.metricOutcome: NiaDispatchOutcome
    get() =
        when (this) {
            DiscordChannelEventAdmission.ACCEPTED -> NiaDispatchOutcome.ACCEPTED
            DiscordChannelEventAdmission.ACCEPTED_AFTER_EVICTION -> NiaDispatchOutcome.ACCEPTED_AFTER_EVICTION
            DiscordChannelEventAdmission.ACCEPTED_TO_MUTATION_OVERFLOW -> NiaDispatchOutcome.ACCEPTED_TO_MUTATION_OVERFLOW
            DiscordChannelEventAdmission.REJECTED -> NiaDispatchOutcome.REJECTED
        }

internal fun NiaRuntimeMetrics.recordTurnBoundary(
    admission: NiaTurnBoundaryAdmission,
    explicitlyAddressed: Boolean,
) {
    val outcome =
        when (admission) {
            NiaTurnBoundaryAdmission.BYPASS -> return
            NiaTurnBoundaryAdmission.DEFERRED -> NiaTurnMetricOutcome.ATTENTION_DEFERRED
            NiaTurnBoundaryAdmission.FAIL_CLOSED -> NiaTurnMetricOutcome.FAILED
        }
    recordTurn(
        outcome = outcome,
        addressing = if (explicitlyAddressed) NiaTurnAddressing.EXPLICIT else NiaTurnAddressing.AMBIENT,
    )
}

internal data class NiaTurnContinuation(
    val likely: Boolean,
    val lastNiaSpokeAgeSeconds: Double?,
)

internal fun deriveNiaTurnContinuation(
    messages: List<DiscordRecentPromptMessage>,
    currentMessageId: Long,
    botUserId: Long,
    currentRepliesToHuman: Boolean,
): NiaTurnContinuation {
    val ordered = messages.sortedBy { it.createdAtEpochMillis }
    val currentIndex = ordered.indexOfLast { it.id == currentMessageId }
    if (currentIndex < 0) return NiaTurnContinuation(likely = false, lastNiaSpokeAgeSeconds = null)

    val current = ordered[currentIndex]
    val earlier = ordered.subList(0, currentIndex)
    val latestNia = earlier.lastOrNull { it.bot && it.authorId == botUserId }
    val ageMillis = latestNia?.let { current.createdAtEpochMillis - it.createdAtEpochMillis }?.takeIf { it >= 0 }
    val lastSpokeAgeSeconds = ageMillis?.div(1_000.0)
    if (latestNia == null || earlier.lastOrNull()?.id != latestNia.id || currentRepliesToHuman) {
        return NiaTurnContinuation(likely = false, lastNiaSpokeAgeSeconds = lastSpokeAgeSeconds)
    }

    val repliedMember =
        latestNia.replyToMessageId
            ?.let { replyId -> earlier.lastOrNull { it.id == replyId && !it.bot } }
    val precedingMember = earlier.dropLast(1).lastOrNull { !it.bot }
    val sameMember = (repliedMember ?: precedingMember)?.authorId == current.authorId
    val recentEnough = ageMillis != null && ageMillis <= NIA_TURN_CONTINUATION_MAX_AGE_MILLIS
    return NiaTurnContinuation(
        likely = sameMember && recentEnough,
        lastNiaSpokeAgeSeconds = lastSpokeAgeSeconds,
    )
}

internal fun buildDiscordRecentContextTurns(
    messages: List<DiscordRecentPromptMessage>,
    currentMessageId: Long,
    botUserId: Long,
    maxTurns: Int = RECENT_CHANNEL_CONTEXT_MAX_TURNS,
    maxRawChars: Int = RECENT_CHANNEL_CONTEXT_MAX_RAW_CHARS,
): List<CloudTurn> {
    val contextTurns =
        messages
            .asSequence()
            .filter { it.id != currentMessageId }
            .filter { it.content.isNotBlank() }
            .filter { !it.bot || it.authorId == botUserId }
            .sortedBy { it.createdAtEpochMillis }
            .toList()
            .takeRecentWithinBudget(maxTurns = maxTurns, maxRawChars = maxRawChars)
            .map { message ->
                if (message.authorId == botUserId) {
                    CloudTurn("assistant", message.content)
                } else {
                    CloudTurn(
                        "user",
                        buildString {
                            appendLine("[채널 메시지 원문]")
                            appendLine("speaker=${message.authorLabel}")
                            appendLine("message_id=${message.id}")
                            appendLine("content:")
                            append(message.content)
                        },
                    )
                }
            }
    if (contextTurns.isEmpty()) return emptyList()
    return contextTurns +
        CloudTurn(
            "user",
            "위 최근 채널 대화 원문을 그대로 참고하세요. 원문을 요약하거나 바꿔 읽지 말고, 반복 호출·불만·답변 누락 같은 흐름을 전체 맥락으로 삼아 자연스럽게 이어 답하세요.",
        )
}

private fun List<DiscordRecentPromptMessage>.takeRecentWithinBudget(
    maxTurns: Int,
    maxRawChars: Int,
): List<DiscordRecentPromptMessage> {
    if (maxTurns <= 0 || maxRawChars <= 0) return emptyList()
    val selected = ArrayDeque<DiscordRecentPromptMessage>()
    var usedChars = 0
    for (message in asReversed()) {
        if (selected.size >= maxTurns) break
        val cost = message.content.length + message.authorLabel.length + 64
        if (selected.isNotEmpty() && usedChars + cost > maxRawChars) break
        selected.addFirst(message)
        usedChars += cost
        if (usedChars >= maxRawChars) break
    }
    return selected.toList()
}

private fun elapsedMs(startNanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

/** 봇이 들어가 있는 서버(길드) 한 건의 식별 정보(어드민 서버 선택 드롭다운용). */
data class BotGuildInfo(
    val id: Long,
    val name: String,
)

/** 서버 안 텍스트 채널 한 건(어드민 채널 선택 드롭다운용). */
data class BotChannelInfo(
    val id: Long,
    val name: String,
)

/** 봇이 현재 들어가 있는 길드(서버)·채널 목록을 제공한다(웹 ‘토큰 받기’·어드민 선택용). */
interface BotGuildLister {
    fun botGuildIds(): Set<Long>

    /** 봇이 들어가 있는 서버를 이름과 함께 반환(미연결/비활성이면 빈 목록). 어드민 드롭다운용. */
    fun botGuilds(): List<BotGuildInfo>

    /** 한 서버의 텍스트 채널을 이름과 함께 반환(미연결/없으면 빈 목록). 어드민 채널 드롭다운용. */
    fun botChannels(guildId: Long): List<BotChannelInfo>

    /**
     * 이 사용자가 해당 길드의 **관리자**(MANAGE_SERVER 또는 ADMINISTRATOR)인지.
     * 데스크톱 앱의 관리 API 권한 게이트용. JDA 미연결·멤버 캐시 미스·권한 없음이면 false(안전 거부).
     */
    fun isGuildAdmin(
        guildId: Long,
        userId: Long,
    ): Boolean

    /** 길드 멤버의 표시 이름(닉네임 우선). JDA 미연결·멤버 캐시 미스면 null(앱에서 ID 폴백). */
    fun memberName(
        guildId: Long,
        userId: Long,
    ): String?
}

/**
 * Discord(JDA) 부트스트랩 + 슬래시 명령 등록/디스패치 (K-차수 13).
 * central.discord.enabled=true 이고 토큰이 있을 때만 연결한다(테스트/CI 는 비활성).
 */
@Component
class DiscordBot(
    private val commands: CommandService,
    private val metrics: CommandMetrics,
    private val niaRuntimeMetrics: NiaRuntimeMetrics,
    private val channelProfiles: ChannelAiProfileService,
    private val autoRespondChannels: AutoRespondChannelRegistry,
    private val guildCleanup: GuildRemovalCleanupService,
    private val reconciliation: ProviderPoolReconciliationService,
    private val gatewayStatus: DiscordGatewayStatus,
    private val historyBackfill: GuildHistoryBackfillService,
    private val onboardingOptOuts: GuildOnboardingOptOutRepository,
    // 설정 마법사(pendingSettings 단일 빈 소유). 모든 Listener 인스턴스가 같은 빈을 참조해 진행중 설정을 공유한다.
    private val settingsWizard: SettingsWizardHandler,
    // 자동응답 채널 단위 비용 캡(분당 상한)에 재사용 — per-user ask 쿨다운과 같은 빈.
    private val rateLimiter: RateLimiter,
    // /그림 결과를 공개 채널에 올리기 전 본인 확인 게이트(설정 영속 + 완성 이미지 임시 보관).
    private val imaginePostConfirm: ImaginePostConfirmService,
    private val pendingImagePosts: PendingImagePostStore,
    // AI 관리 비서: 어드민의 /질문 자연어 관리 명령을 GLM tool calling 으로 해석·실행(JDA 변경은 여기서 게이트웨이로).
    private val adminAssistant: com.discordassistant.central.platform.discord.admin.AdminAssistantService,
    // NEXA participation 자발 발화 wiring(단계 1). flag 활성 채널에서만 평가·emit. 기본 OFF(회귀 0)·SHADOW_PREDICT 전송 0.
    private val participationEmitBridge: com.discordassistant.central.platform.discord.nexa.NexaParticipationEmitBridge,
    private val niaTurnGenerations: NiaTurnGenerationTracker,
    private val niaCatchUpCadence: NiaCatchUpCadence,
    private val participationFlags: NexaParticipationFlagService,
    private val imageAttachmentPreparer: DiscordImageAttachmentPreparer,
    // 니아 채널 자동 만들기 시 ai채팅·ai그림을 LLM 채널 허용 목록에 등록(자동응답↔LLM 정책 불일치 방지). PolicyService 빈.
    private val channelAllowList: com.discordassistant.central.guild.application.ChannelAllowListPort,
    @param:Value("\${central.discord.enabled:false}") private val enabled: Boolean,
    @param:Value("\${central.discord.bot-token:}") private val token: String,
    // 설정 시 해당 길드(서버)에 명령 즉시 등록(전파 지연 없음). 비우면 글로벌 등록(최대 ~1h).
    @param:Value("\${central.discord.guild-id:}") private val guildId: String,
    @param:Value("\${central.discord.message-content-intent-enabled:true}") private val messageContentIntentEnabled: Boolean,
    @param:Value("\${central.discord.typing-intent-enabled:false}") private val typingIntentEnabled: Boolean,
    @param:Value("\${central.nexa.participation.turn-boundary.enabled:false}") private val turnBoundaryEnabled: Boolean,
    @param:Value("\${central.discord.fallback-without-message-content-on-4014:true}") private val fallbackWithoutMessageContentOn4014:
        Boolean,
) : BotGuildLister {
    private val log = LoggerFactory.getLogger(DiscordBot::class.java)
    private val lifecycleLock = Any()
    private val stopping = AtomicBoolean(false)

    @Volatile
    private var jda: JDA? = null

    @Volatile
    private var restartThread: Thread? = null

    /**
     * NEXA 자율 전송 실행기 배선용 — 현재 활성 JDA 인스턴스. Discord 가 비활성이거나 아직 기동 전이면 [IllegalStateException].
     * 자율 전송 config 빈은 이 봇에 의존하므로(빈 초기화 순서상 [start] @PostConstruct 이후 호출), enabled 이면 non-null.
     */
    fun requireActiveJda(): JDA =
        jda ?: throw IllegalStateException(
            "JDA 미기동 — NEXA 자율 전송을 켜려면 central.discord.enabled=true 와 봇 토큰이 필요합니다.",
        )

    /** 봇이 들어가 있는 길드 id 집합(JDA 미연결/비활성이면 빈 집합). */
    override fun botGuildIds(): Set<Long> = jda?.guilds?.map { it.idLong }?.toSet() ?: emptySet()

    /** 봇이 들어가 있는 서버를 이름과 함께(이름 오름차순). JDA 미연결/비활성이면 빈 목록. */
    override fun botGuilds(): List<BotGuildInfo> =
        jda
            ?.guilds
            ?.map { BotGuildInfo(it.idLong, it.name) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /** 한 서버의 텍스트 채널을 이름과 함께(채널 위치 순). JDA 미연결/서버 없음이면 빈 목록. */
    override fun botChannels(guildId: Long): List<BotChannelInfo> =
        jda
            ?.getGuildById(guildId)
            ?.textChannels
            ?.map { BotChannelInfo(it.idLong, it.name) }
            ?: emptyList()

    /**
     * 관리 API 권한 게이트: 사용자가 길드 관리자(소유자 또는 MANAGE_SERVER|ADMINISTRATOR)인지. 미충족은 false(안전 거부).
     *
     * 봇은 `createLight`(GUILD_MEMBERS 인텐트 미사용)라 멤버 캐시가 비어 있을 수 있다 → `getMemberById` 만으로는
     * 데스크톱 관리 probe 에서 소유자조차 null 로 떨어져 "관리자 아님"이 된다(실측 버그). 그래서:
     *   ① 서버 소유자는 항상 관리자 — `ownerIdLong` 은 GUILD_CREATE 로 캐시/인텐트 없이도 안다(가장 흔한 케이스 즉시 해결).
     *   ② 캐시에 멤버가 있으면 캐시로 권한 판정.
     *   ③ 캐시 미스면 REST 로 1회 조회(GUILD_MEMBERS 인텐트 불필요) 후 위임 관리자(MANAGE_SERVER)까지 판정.
     */
    override fun isGuildAdmin(
        guildId: Long,
        userId: Long,
    ): Boolean {
        val guild = jda?.getGuildById(guildId) ?: return false
        if (guild.ownerIdLong == userId) return true // ① 소유자 = 항상 관리자
        guild.getMemberById(userId)?.let { return it.hasAdminPower() } // ② 캐시 적중
        return try {
            // ③ 캐시 미스 → REST 조회(위임 관리자 포함)
            guild.retrieveMemberById(userId).complete()?.hasAdminPower() ?: false
        } catch (e: Exception) {
            // 멤버 조회 실패는 '관리자 아님'으로 보수 처리(broad 유지) — 단 소유자↔권한 진단을 위해 남긴다(예외 원칙 3).
            log.debug("멤버 조회 실패(guild={}, user={}) — 관리자 아님으로 처리: {}", guildId, userId, e.message)
            false
        }
    }

    private fun net.dv8tion.jda.api.entities.Member.hasAdminPower(): Boolean =
        hasPermission(Permission.MANAGE_SERVER) || hasPermission(Permission.ADMINISTRATOR)

    /** 길드 멤버 표시 이름(닉네임 우선). 캐시 미스/미연결이면 null. */
    override fun memberName(
        guildId: Long,
        userId: Long,
    ): String? = jda?.getGuildById(guildId)?.getMemberById(userId)?.effectiveName

    private val fallbackAttempted = AtomicBoolean(false)

    // 추론(ask/imagine)은 길어서 JDA 게이트웨이 스레드를 점유하면 안 된다(동시 요청 시 봇 전체 stall).
    // deferReply 로 ack 한 뒤 실제 처리는 이 전용 풀에서(나머지 빠른 명령은 리스너 스레드 그대로).
    private val commandExecutor: ScheduledExecutorService =
        Executors.newScheduledThreadPool(8) { r -> Thread(r, "discord-cmd").apply { isDaemon = true } }
    private val channelEventDispatcher = DiscordChannelEventDispatcher()
    private val turnBoundaryTimer: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "discord-turn-boundary").apply { isDaemon = true }
        }
    private val turnBoundaryCoordinator =
        NiaTurnBoundaryCoordinator(
            enabled = turnBoundaryEnabled,
            clock = Clock.systemUTC(),
            scheduler = ScheduledExecutorNiaTurnBoundaryScheduler(turnBoundaryTimer),
        )

    @PostConstruct
    fun start() {
        if (stopping.get()) return
        if (!enabled || token.isBlank()) {
            log.info("Discord 비활성(enabled={}, token={}) — JDA 미기동", enabled, token.isNotBlank())
            return
        }
        launchJda(messageContentIntentEnabled)
    }

    private fun launchJda(messageContentIntent: Boolean) {
        if (stopping.get()) return
        gatewayStatus.markStarting(messageContentIntent, typingIntentEnabled)
        val intents = GatewayIntentPolicy.intents(messageContentIntent, typingIntentEnabled)
        val builder = JDABuilder.createLight(token, intents)
        val listener =
            Listener(
                commands,
                metrics,
                niaRuntimeMetrics,
                channelProfiles,
                autoRespondChannels,
                guildCleanup,
                reconciliation,
                gatewayStatus,
                historyBackfill,
                onboardingOptOuts,
                settingsWizard,
                rateLimiter,
                imaginePostConfirm,
                pendingImagePosts,
                adminAssistant,
                participationEmitBridge,
                niaTurnGenerations,
                niaCatchUpCadence,
                participationFlags,
                imageAttachmentPreparer,
                channelAllowList,
                mentionAskEnabled = messageContentIntent,
                messageContentIntentEnabled = messageContentIntent,
                typingIntentEnabled = typingIntentEnabled,
                onDisallowedIntents = { handleDisallowedIntents(messageContentIntent) },
                slowCommandExecutor = commandExecutor,
                channelEventDispatcher = channelEventDispatcher,
                turnBoundaryCoordinator = turnBoundaryCoordinator,
            )
        val instance = builder.addEventListeners(listener).build()
        val accepted =
            synchronized(lifecycleLock) {
                if (stopping.get()) {
                    false
                } else {
                    jda = instance
                    true
                }
            }
        if (!accepted) {
            instance.shutdownNow()
            // `shutdownNow` is asynchronous. The stop path joins a 4014 restart before it closes the serialized
            // raw-context dispatcher, so this unregistered instance must also reach SHUTDOWN before this thread ends.
            if (awaitJdaShutdown(instance)) Thread.currentThread().interrupt()
            return
        }
        if (stopping.get()) {
            instance.shutdownNow()
            return
        }
        registerCommands(instance.updateCommands())
        if (guildId.isNotBlank()) {
            instance.awaitReady()
            instance.getGuildById(guildId)?.updateCommands()?.queue({}, {})
        }
        log.info(
            "Discord(JDA) 기동 완료 — 슬래시 명령 글로벌 등록(봇 DM 포함), messageContentIntent={}",
            messageContentIntent,
        )
    }

    private fun handleDisallowedIntents(messageContentIntent: Boolean) {
        val guide =
            "Discord gateway 4014 DISALLOWED_INTENTS — @멘션 질문에는 Developer Portal Bot 설정의 " +
                "Message Content Intent가 필요합니다. 설정하지 않을 경우 " +
                "central.discord.message-content-intent-enabled=false 로 슬래시 명령만 안전 부팅하세요."
        log.error(guide)
        gatewayStatus.markShutdown(4014, guide)
        if (
            stopping.get() ||
            !messageContentIntent ||
            !fallbackWithoutMessageContentOn4014 ||
            !fallbackAttempted.compareAndSet(false, true)
        ) {
            return
        }
        gatewayStatus.markSafeFallback("Message Content Intent 거부로 @멘션 질문을 끄고 슬래시 명령만 재기동합니다.")
        val restart =
            Thread(
                {
                    try {
                        runCatching { jda?.shutdownNow() }
                        if (!stopping.get()) {
                            runCatching { launchJda(messageContentIntent = false) }
                                .onFailure { e ->
                                    log.error("Message Content Intent 없는 안전 재기동 실패: {}", e.message, e)
                                    gatewayStatus.markShutdown(4014, "Message Content Intent 없는 안전 재기동 실패: ${e.message}")
                                }
                        }
                    } finally {
                        synchronized(lifecycleLock) {
                            if (restartThread === Thread.currentThread()) restartThread = null
                        }
                    }
                },
                "discord-safe-intent-restart",
            )
        synchronized(lifecycleLock) {
            if (stopping.get()) return
            restartThread = restart
        }
        restart.start()
    }

    @PreDestroy
    fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        turnBoundaryCoordinator.close()
        turnBoundaryTimer.shutdownNow()
        val restart = synchronized(lifecycleLock) { restartThread }
        val instance = synchronized(lifecycleLock) { jda }
        instance?.shutdown()
        var interrupted = instance?.let(::awaitJdaShutdown) ?: false
        val restartInterrupted = awaitRestartCompletion(restart)
        interrupted = interrupted || restartInterrupted
        channelEventDispatcher.close()
        commandExecutor.shutdown()
        if (interrupted) Thread.currentThread().interrupt()
    }

    /** A 4014 fallback may be building JDA while shutdown begins; wait until it cannot install a new listener. */
    private fun awaitRestartCompletion(restart: Thread?): Boolean {
        if (restart == null) return false
        var interrupted = false
        while (restart.isAlive) {
            try {
                restart.join(DISCORD_FORCED_SHUTDOWN_GRACE_MILLIS)
                if (restart.isAlive) log.error("Discord safe-intent restart is still stopping; waiting before closing message dispatcher")
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        return interrupted
    }

    /** Do not close the serialized raw-context mutator while JDA can still deliver delete/edit callbacks. */
    private fun awaitJdaShutdown(instance: JDA): Boolean {
        var interrupted = false
        try {
            if (instance.awaitShutdown(DISCORD_SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS)) return false
        } catch (_: InterruptedException) {
            interrupted = true
        }

        log.warn("Discord JDA graceful shutdown timed out or was interrupted; forcing gateway shutdown before closing message dispatcher")
        instance.shutdownNow()
        while (true) {
            try {
                if (instance.awaitShutdown(DISCORD_FORCED_SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS)) return interrupted
                log.error("Discord JDA is still stopping; waiting before closing the raw-context dispatcher")
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    }

    private fun registerCommands(action: net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction) {
        // 관리자 명령은 비관리자 UI 에서 숨김(#186). 서버 관리 권한 보유자만 노출.
        val cmds = SlashCommandCatalog.all()
        // 슬래시 명령을 클라이언트 언어(ko 기본/en/ru)로 로컬라이즈.
        // 설치/컨텍스트(차수 19): DM 대상 명령은 봇 DM 허용(setGuildOnly=false), 그 외(관리자/정책)는 길드 전용.
        cmds.forEach { cmd -> cmd.setGuildOnly(cmd.name !in DM_COMMANDS) }
        cmds
            .filterIsInstance<net.dv8tion.jda.api.interactions.commands.build.SlashCommandData>()
            .forEach { CommandLoc.localize(it) }
        action.addCommands(cmds).queue()
    }

    /** JDA 이벤트 → CommandContext → CommandService → 응답. */
    internal class Listener(
        private val commands: CommandService,
        private val metrics: CommandMetrics,
        private val niaRuntimeMetrics: NiaRuntimeMetrics,
        private val channelProfiles: ChannelAiProfileService,
        private val autoRespondChannels: AutoRespondChannelRegistry,
        private val guildCleanup: GuildRemovalCleanupService,
        private val reconciliation: ProviderPoolReconciliationService,
        private val gatewayStatus: DiscordGatewayStatus,
        private val historyBackfill: GuildHistoryBackfillService,
        private val onboardingOptOuts: GuildOnboardingOptOutRepository,
        private val settingsWizard: SettingsWizardHandler,
        private val rateLimiter: RateLimiter,
        private val imaginePostConfirm: ImaginePostConfirmService,
        private val pendingImagePosts: PendingImagePostStore,
        private val adminAssistant: com.discordassistant.central.platform.discord.admin.AdminAssistantService,
        private val participationEmitBridge: com.discordassistant.central.platform.discord.nexa.NexaParticipationEmitBridge,
        private val niaTurnGenerations: NiaTurnGenerationTracker,
        private val niaCatchUpCadence: NiaCatchUpCadence,
        private val participationFlags: NexaParticipationFlagService,
        private val imageAttachmentPreparer: DiscordImageAttachmentPreparer,
        private val channelAllowList: com.discordassistant.central.guild.application.ChannelAllowListPort,
        private val mentionAskEnabled: Boolean,
        private val messageContentIntentEnabled: Boolean,
        private val typingIntentEnabled: Boolean,
        private val onDisallowedIntents: () -> Unit,
        private val slowCommandExecutor: ScheduledExecutorService,
        private val channelEventDispatcher: DiscordChannelEventDispatcher,
        private val turnBoundaryCoordinator: NiaTurnBoundaryCoordinator,
    ) : ListenerAdapter() {
        // god class 분해(verbatim 이동): 응답 렌더링·채널 프로필 패널·온보딩 인터랙션·설정 마법사를 협력자로 위임한다.
        // 동일 의존성 인스턴스를 그대로 넘겨 동작을 구조적으로 보존한다(로직 불변).
        // settingsWizard 는 단일 빈을 그대로 받아 모든 Listener 가 같은 pendingSettings(진행중 설정)를 공유한다.
        private val answers = DiscordAnswerRenderer(channelProfiles)
        private val channelProfilePanel =
            ChannelProfilePanelRenderer(channelProfiles, settingsWizard::effectiveAllowedChannelIds)
        private val onboarding =
            OnboardingInteractionHandler(commands, historyBackfill, onboardingOptOuts, messageContentIntentEnabled)
        private val setupChannels = NiaChannelSetupHandler(channelProfiles, autoRespondChannels, channelAllowList, participationFlags)
        private val recentMessagesByChannel = ConcurrentHashMap<Long, ArrayDeque<DiscordRecentPromptMessage>>()

        // 니아 톤 히스테리시스(I12): scope 별 직전 렌더 활성 여부. observe 의 wasToneActive 입력으로 재공급.
        private val niaToneActive = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        // NEXA participation 히스토리 신호 도출기(채널별 사람 메시지 ring buffer — 추가 JDA 조회 0). 트리거가 올 때
        // 중복·burst 미완·사적 핑퐁(화자집합/첫메시지/니아 호명)을 이 버퍼에서 도출해 브리지로 넘긴다(graceful).
        private val participationSignals =
            com.discordassistant.central.platform.discord.nexa
                .ParticipationSignalDeriver()

        override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
            metrics.record(event.name) // 명령 사용 통계(#190)
            // DM(유저설치, 길드 없음)이면 글로벌 풀 스코프. 길드 전용 명령이 DM 으로 오면 막는다(방어적 — 컨텍스트로도 차단됨).
            if (event.guild == null && event.name !in DM_COMMANDS) {
                event.reply("이 명령은 서버에서만 사용할 수 있어요.").setEphemeral(true).queue()
                return
            }
            val ctx = ctxOf(event)
            // 인터랙티브 명령은 컴포넌트/모달로 응답(온보딩/설정 판).
            when (event.name) {
                "menu" -> {
                    // 관리자는 봇 입장 배너 외에 /메뉴 에서도 채널 자동 만들기 버튼을 상시 호출할 수 있다(이미 입장한
                    // 서버 대응). 첫 행 버튼 수 초과를 피하려고 별도 ActionRow 로 붙인다.
                    val rows =
                        buildList {
                            add(ActionRow.of(MenuFactory.mainButtons(ctx.isAdmin)))
                            if (ctx.isAdmin) {
                                add(
                                    ActionRow.of(
                                        Button.primary(
                                            NiaChannelSetup.COMPONENT_ID,
                                            I18n.get("niaSetupChannelsButton", I18n.DEFAULT),
                                        ),
                                    ),
                                )
                            }
                        }
                    event
                        .replyEmbeds(EmbedFactory.mainMenuEmbed(ctx.isAdmin))
                        .addComponents(rows)
                        .setEphemeral(true)
                        .queue()
                    return
                }
                "settings" -> {
                    // 공개 명령: 누구나 ephemeral 안내 + 웹 대시보드로 가는 링크 버튼. 베이스 URL 이 없으면 안내 폴백(빈 link 버튼 금지).
                    when (val links = commands.settings(ctx)) {
                        is SettingsCommandHandler.SettingsLinks.WithLinks ->
                            event
                                .replyEmbeds(EmbedFactory.settingsLinkEmbed(links.title, links.description))
                                .addComponents(ActionRow.of(links.buttons.map { Button.link(it.url, it.label) }))
                                .setEphemeral(true)
                                .queue()
                        is SettingsCommandHandler.SettingsLinks.Unavailable ->
                            event
                                .replyEmbeds(EmbedFactory.settingsLinkEmbed(links.title, links.description))
                                .setEphemeral(true)
                                .queue()
                    }
                    return
                }
                "provider-join" -> {
                    if (commands.providerLinked(ctx)) {
                        // 이미 연동된(앱 연결된) 사용자 → 가이드 대신 실제 참여 완료(앱이 동기화로 자동 연결).
                        event.reply(commands.providerJoin(ctx).content).setEphemeral(true).queue()
                    } else {
                        // 미연동 → 먼저 설치할 컴퓨터(OS)를 버튼으로 묻는다(차수 19). 클릭 → 그 OS 복붙 설치 명령.
                        event
                            .reply(
                                "🖥️ **내 컴퓨터의 AI로 함께 도와주기**\n\n" +
                                    "내 컴퓨터에 있는 AI가 커뮤니티 질문에 답하는 일을 함께 도와줘요.\n" +
                                    "복잡한 설정은 안내를 따라 하면 되고, 원할 때 언제든 멈출 수 있어요.\n\n" +
                                    "**설치할 컴퓨터**를 고르세요. 버튼을 누르면 복사해서 붙여넣을 명령을 보여드릴게요.",
                            ).addComponents(ActionRow.of(MenuFactory.osButtons()))
                            .setEphemeral(true)
                            .queue()
                    }
                    return
                }
                "help" -> {
                    // 명령 이름을 보는 사람 클라이언트 언어로 표시(슬래시 메뉴와 일치).
                    event.replyEmbeds(EmbedFactory.helpEmbed(ctx.isAdmin, event.userLocale)).setEphemeral(true).queue()
                    return
                }
                // 관리/운영 명령(설정·채널 AI·온보딩·지식·프리셋·다중응답 등)은 슬래시에서 제거되고 웹 대시보드로
                // 이관됐다(/설정 → 웹). 채널 AI 자동 만들기는 입장 배너 버튼/웹에서, 긴 질문은 메시지 컨텍스트 메뉴로.
            }
            // 모든 명령을 defer 로 먼저 ack(3초 제한 회피) 후 결과 편집. 공유/원격 서버의 지연에도 안전.
            // 공개 명령만 비-ephemeral, 나머지는 ephemeral. defer 시점에 결정.
            val useWebhookProfile = event.name == "ask" && channelProfiles.get(ctx.guildId, ctx.channelId) != null
            val isPublic = event.name in PUBLIC_COMMANDS
            // 어드민 /질문이 관리 액션 경로(AI 관리 비서)로 처리될 가능성이 있으면 반드시 ephemeral 로 defer 해야 한다.
            // 차단 대상·관리 행위 결과가 채널에 공개되는 것을 방지. 일반(비어드민) /질문은 isPublic 경로 그대로.
            val isAdminAskPath =
                event.name == "ask" &&
                    ctx.isAdmin &&
                    adminAssistant.isAvailable()
            // /그림 게시 확인 게이트: confirm 옵션이 주어지면 먼저 유저 설정을 갱신한 뒤, 그 설정대로 이번 요청을 처리한다.
            // 게이트 ON 이면 전 과정을 본인만 보이게(ephemeral) 진행하고 완성 후 게시 확인 버튼을 띄운다(아래 work 에서 분기).
            val imagineGateOn =
                if (event.name == "imagine") {
                    event.getOption("confirm")?.asBoolean?.let { imaginePostConfirm.setEnabled(ctx.userId, it) }
                    imaginePostConfirm.isEnabled(ctx.userId)
                } else {
                    false
                }
            event
                .deferReply(
                    if (useWebhookProfile || imagineGateOn) {
                        true
                    } else if (isAdminAskPath) {
                        true
                    } else {
                        !isPublic
                    },
                ).queue()
            val work =
                Runnable {
                    try {
                        // 어드민 /질문: 자연어 관리 명령이면 GLM tool calling 으로 실행/확인(아니면 false → 일반 /질문).
                        if (event.name == "ask" && ctx.isAdmin && handleAdminAsk(event, ctx)) return@Runnable
                        val reply =
                            if (event.name == "imagine") {
                                // 이미지 생성 진행률을 '생각 중' 메시지에 N% 로 라이브 편집(SD progress 청크 → onProgress).
                                val lastPct =
                                    java.util.concurrent.atomic
                                        .AtomicInteger(-1)
                                commands.imagine(
                                    ctx,
                                    event.getOption("prompt")?.asString.orEmpty(),
                                    onStart = { requestId ->
                                        // 생성 시작 → '취소' 버튼을 메시지에 붙인다(클릭 시 중간 취소).
                                        event.hook
                                            .editOriginal("🖼️ 생성 시작… (취소하려면 아래 버튼)")
                                            .setActionRow(Button.danger("$IMG_CANCEL_PREFIX$requestId", "🛑 취소"))
                                            .queue({}, {})
                                    },
                                ) { pct ->
                                    if (pct > lastPct.get()) {
                                        lastPct.set(pct)
                                        // 내용만 편집 — 취소 버튼(컴포넌트)은 유지된다.
                                        event.hook.editOriginal("🖼️ 그리는 중… $pct%").queue({}, {})
                                    }
                                }
                            } else {
                                dispatch(event, ctx)
                            }
                        if (imagineGateOn && reply.imagePng != null) {
                            // 게이트 ON: 채널에 바로 붙이지 않고 본인만 보이는 미리보기 + 게시/버리기/안묻기 버튼을 띄운다.
                            renderImaginePostConfirm(event, ctx, reply)
                        } else if (useWebhookProfile) {
                            answers.completePublicAnswerWithProfileFallback(event.hook, event.channel, ctx, reply)
                        } else {
                            // 게이트 OFF(기존 동작) 또는 게이트 ON 이라도 imagePng==null(실패/취소) → 그대로 편집.
                            // 게이트 ON 폴백은 hook 이 ephemeral 이라 본인만 본다.
                            answers.editOriginalWithPseudoStream(event.hook, reply)
                        }
                    } catch (e: Exception) {
                        // 명령 처리 실패는 사용자에겐 일반 안내, 서버엔 명령·채널 맥락과 스택을 남긴다(예외 원칙 4).
                        log.warn("명령 처리 실패: {} (channel={}) — {}", event.name, event.channel.idLong, e.message, e)
                        event.hook.editOriginal("⚠️ 처리 중 오류가 발생했어요. 잠시 후 다시 시도해 주세요.").queue({}, {})
                    }
                }
            // 추론(ask/imagine)은 길어서 게이트웨이 스레드 밖에서. 나머지 빠른 명령은 그대로 실행.
            if (event.name in SLOW_COMMANDS) slowCommandExecutor.execute(work) else work.run()
        }

        /**
         * AI 관리 비서(어드민 전용): 어드민의 /질문 자연어를 GLM tool calling 으로 해석한다. 관리 의도가 없으면
         * false 를 돌려 호출자가 기존 /질문 으로 폴백한다(회귀 0). SAFE 면 즉시 실행 후 결과 보고, CONFIRM 이면
         * 확인 버튼([실행]/[취소])을 단다(클릭 시 [onButtonInteraction] 에서 권한 재검증 후 실행).
         */
        private fun handleAdminAsk(
            event: SlashCommandInteractionEvent,
            ctx: CommandContext,
        ): Boolean {
            if (!adminAssistant.isAvailable()) return false
            val guild = event.guild ?: return false
            val prompt = event.getOption("prompt")?.asString.orEmpty()
            val decision = adminAssistant.plan(ctx.guildId, ctx.userId, prompt)
            return when (decision) {
                is com.discordassistant.central.platform.discord.admin.AdminAssistantDecision.NotAdminAction -> false
                is com.discordassistant.central.platform.discord.admin.AdminAssistantDecision.ReadyToRun -> {
                    val result =
                        adminAssistant.runSafe(
                            ctx.guildId,
                            ctx.userId,
                            decision.plan,
                            com.discordassistant.central.platform.discord.admin
                                .JdaAdminGuildGateway(guild),
                        )
                    event.hook
                        .editOriginal(result.message)
                        .setComponents()
                        .queue({}, {})
                    true
                }
                is com.discordassistant.central.platform.discord.admin.AdminAssistantDecision.NeedsConfirm -> {
                    event.hook
                        .editOriginal(adminAssistant.confirmPrompt(decision.plan))
                        .setActionRow(
                            Button.danger("$ADMIN_ACT_RUN_PREFIX${decision.token}", "✅ 실행"),
                            Button.secondary("$ADMIN_ACT_CANCEL_PREFIX${decision.token}", "✖️ 취소"),
                        ).queue({}, {})
                    true
                }
            }
        }

        /**
         * AI 관리 비서 확인 버튼 처리: 클릭한 사용자가 어드민인지 **재검증**하고(다른 사람이 누르면 거부),
         * 요청자 일치·토큰 만료까지 확인한 뒤 실행한다(안전장치 1·5). JDA 변경은 클릭 이벤트의 길드로 만든 게이트웨이로.
         */
        private fun handleAdminActionButton(
            event: ButtonInteractionEvent,
            ctx: CommandContext,
        ) {
            val isRun = event.componentId.startsWith(ADMIN_ACT_RUN_PREFIX)
            val token = event.componentId.removePrefix(if (isRun) ADMIN_ACT_RUN_PREFIX else ADMIN_ACT_CANCEL_PREFIX)
            if (!isRun) {
                event.editMessage("✖️ 작업을 취소했어요.").setComponents().queue({}, {})
                return
            }
            val guild = event.guild
            if (guild == null) {
                event.reply("이 작업은 서버에서만 실행할 수 있어요.").setEphemeral(true).queue()
                return
            }
            event.deferEdit().queue({}, {})
            val result =
                adminAssistant.confirmAndRun(token, ctx.userId, ctx.isAdmin) {
                    com.discordassistant.central.platform.discord.admin
                        .JdaAdminGuildGateway(guild)
                }
            event.hook
                .editOriginal(result.message)
                .setComponents()
                .queue({}, {})
        }

        /**
         * /그림 게시 확인 게이트 ON 경로: 완성 이미지를 본인만 보이는(ephemeral) 미리보기로 보여주고,
         * 채널 게시에 필요한 PNG/캡션/대상 채널을 토큰으로 잠깐 보관한 뒤 버튼 3개(게시/버리기/안묻기)를 단다.
         * 토큰은 PendingImagePostStore 가 소유·TTL 관리하며, 버튼 클릭 시 onButtonInteraction 에서 꺼내 처리한다.
         */
        private fun renderImaginePostConfirm(
            event: SlashCommandInteractionEvent,
            ctx: CommandContext,
            reply: Reply,
        ) {
            val bytes = reply.imagePng ?: return // 호출부에서 null 아님을 보장하지만 방어적으로.
            val token =
                pendingImagePosts.put(
                    pngBytes = bytes,
                    caption = reply.content,
                    guildId = ctx.guildId,
                    channelId = event.channel.idLong,
                    userId = ctx.userId,
                )
            event.hook
                .editOriginal("🖼️ 그림이 완성됐어요! 본인만 보이는 미리보기예요.\n채널에 올릴지 선택해 주세요.")
                .setFiles(
                    net.dv8tion.jda.api.utils.FileUpload
                        .fromData(bytes, "image.png"),
                ).setActionRow(
                    Button.success("$IMG_POST_PREFIX$token", "✅ 채널에 게시"),
                    Button.danger("$IMG_DISCARD_PREFIX$token", "🗑️ 버리기"),
                    Button.secondary("$IMG_MUTE_PREFIX$token", "🔕 게시하고 다음부터 안 묻기"),
                ).queue({}, { e ->
                    log.warn("이미지 게시 확인 미리보기 응답 실패: {}", e.message)
                    event.hook.editOriginal("⚠️ 이미지를 전송하지 못했어요.").queue({}, {})
                })
        }

        /** /그림 게시 확인 버튼(게시/버리기/안묻기) 처리. 토큰 소유자 검증·TTL 은 PendingImagePostStore 가 한다. */
        private fun handleImaginePostButton(event: ButtonInteractionEvent) {
            val userId = event.user.idLong
            // 채널 게시(complete, 블로킹)는 큰 이미지에서 수백 ms~수 초 걸릴 수 있다. 먼저 deferEdit 로 ack 해
            // 버튼 인터랙션 3초 타임아웃("상호작용 실패")을 없앤 뒤, 결과는 hook 으로 편집한다.
            event.deferEdit().queue({}, {})
            val hook = event.hook
            when {
                event.componentId.startsWith(IMG_POST_PREFIX) -> {
                    val token = event.componentId.removePrefix(IMG_POST_PREFIX)
                    val pending = pendingImagePosts.take(token, userId)
                    if (pending == null) {
                        editExpired(hook)
                        return
                    }
                    val msg =
                        if (postPendingToChannel(event, pending)) {
                            "✅ 채널에 게시했어요."
                        } else {
                            "⚠️ 채널에 게시하지 못했어요. 권한을 확인하고 다시 시도해 주세요."
                        }
                    hook.editOriginal(msg).setReplace(true).queue({}, {})
                }
                event.componentId.startsWith(IMG_DISCARD_PREFIX) -> {
                    val token = event.componentId.removePrefix(IMG_DISCARD_PREFIX)
                    pendingImagePosts.discard(token, userId)
                    hook
                        .editOriginal("🗑️ 버렸어요. 채널엔 아무것도 올라가지 않았어요.")
                        .setReplace(true)
                        .queue({}, {})
                }
                event.componentId.startsWith(IMG_MUTE_PREFIX) -> {
                    val token = event.componentId.removePrefix(IMG_MUTE_PREFIX)
                    val pending = pendingImagePosts.take(token, userId)
                    if (pending == null) {
                        editExpired(hook)
                        return
                    }
                    // 이번 이미지는 채널 게시 + 다음부터 확인 안 묻도록 설정 OFF 저장.
                    imaginePostConfirm.setEnabled(userId, false)
                    val msg =
                        if (postPendingToChannel(event, pending)) {
                            "✅ 게시했어요. 다음부터는 확인 없이 바로 올라가요. (`/그림 confirm:켜기` 로 다시 켤 수 있어요)"
                        } else {
                            "⚠️ 채널 게시는 실패했지만 다음부터 확인은 끄도록 했어요. (`/그림 confirm:켜기` 로 다시 켤 수 있어요)"
                        }
                    hook.editOriginal(msg).setReplace(true).queue({}, {})
                }
            }
        }

        /** 보관 항목을 원본 채널에 공개 메시지로 전송(요청자가 만든 그림임을 캡션에 표시). 성공 시 true. */
        private fun postPendingToChannel(
            event: ButtonInteractionEvent,
            pending: PendingImagePostStore.Pending,
        ): Boolean =
            runCatching {
                val channel = event.guild?.getTextChannelById(pending.channelId) ?: event.channel.asTextChannel()
                channel
                    .sendMessage("${pending.caption}\n— <@${pending.userId}> 님이 만든 그림")
                    .setFiles(
                        net.dv8tion.jda.api.utils.FileUpload
                            .fromData(pending.pngBytes, "image.png"),
                    ).complete()
                true
            }.onFailure { e ->
                log.warn("이미지 채널 게시 실패(channel={}): {}", pending.channelId, e.message)
            }.getOrDefault(false)

        private fun editExpired(hook: InteractionHook) {
            // 토큰 만료/부재/타인 → 안전 안내(미리보기는 본인 ephemeral 이라 보통 본인 클릭).
            hook
                .editOriginal("⌛ 이 미리보기는 만료됐어요. 다시 `/그림` 으로 만들어 주세요.")
                .setReplace(true)
                .queue({}, {})
        }

        companion object {
            private val log = LoggerFactory.getLogger(Listener::class.java)

            // 추론으로 오래 걸리는 명령 — 게이트웨이 스레드 밖(전용 풀)에서 처리한다.
            // ai-onboard 는 위 switch 에서 직접 defer + executor 로 처리하므로 여기 포함하지 않는다.
            private val SLOW_COMMANDS = setOf("ask", "imagine")

            /** /ai-onboard 본문 백필 수집 상한(JDA 레이트리밋·메모리 보호). */
            private const val MAX_ONBOARD_HISTORY_LIMIT = 200

            /** NEXA participation continuation 토큰화(한글/영숫자) — core hard_policy `_TOKEN_RE` 와 동일 패턴. */
            private val NIA_TOKEN_RE = Regex("[0-9A-Za-z가-힣]+")

            /** 공개(비-ephemeral) 응답 명령. 나머지는 본인만 보이게(ephemeral). */
            private val PUBLIC_COMMANDS = setOf("ask", "imagine", "contributions", "community-stats", "welcome", "nia")
            private const val CHANNEL_PROFILE_EDIT = "channel-profile:edit"
            private const val CHANNEL_PROFILE_AVATAR = "channel-profile:avatar"
            private const val CHANNEL_PROFILE_RESET = "channel-profile:reset"
            private const val CHANNEL_PROFILE_ROLLBACK = "channel-profile:rollback"
            private const val CHANNEL_PROFILE_SAVE_MODAL = "channel-profile:save-modal"
            private const val CHANNEL_PROFILE_AVATAR_MODAL = "channel-profile:avatar-modal"
            private const val ONBOARD_PREFIX = "onboard:"

            // AI 관리 비서 확인 게이트 버튼 customId 접두사(뒤에 PendingAdminActionStore 토큰).
            private const val ADMIN_ACT_RUN_PREFIX = "adminact:run:" // ✅ 실행
            private const val ADMIN_ACT_CANCEL_PREFIX = "adminact:cancel:" // ✖️ 취소

            // 이미지 생성 취소 버튼 customId 접두사(뒤에 requestId). 누르면 ComfyUI /interrupt 유발.
            private const val IMG_CANCEL_PREFIX = "img-cancel:"

            // /그림 게시 확인 게이트 버튼 customId 접두사(뒤에 PendingImagePostStore 토큰).
            private const val IMG_POST_PREFIX = "img-post:" // ✅ 채널에 게시
            private const val IMG_DISCARD_PREFIX = "img-discard:" // 🗑️ 버리기
            private const val IMG_MUTE_PREFIX = "img-mute:" // 🔕 게시하고 다음부터 안 묻기
            private const val ONBOARD_ACTION_START = "start"

            /**
             * 온보딩 배너를 보낼 채널 선택(순수 로직 — 단위 테스트 가능). 봇이 쓸 수 있는 시스템 채널을 우선하고,
             * 없으면(시스템 채널 미설정/권한 없음) 쓰기 가능한 첫 텍스트 채널로 폴백한다. 둘 다 없으면 null(graceful 무시).
             */
            fun <T> selectOnboardingChannel(
                systemChannel: T?,
                writableChannels: List<T>,
            ): T? = systemChannel ?: writableChannels.firstOrNull()
        }

        override fun onReady(event: ReadyEvent) {
            gatewayStatus.markReady(messageContentIntentEnabled, typingIntentEnabled)
            log.info(
                "Discord gateway ready — guilds available={} unavailable={} mentionAskEnabled={}",
                event.guildAvailableCount,
                event.guildUnavailableCount,
                mentionAskEnabled,
            )
        }

        override fun onShutdown(event: ShutdownEvent) {
            val problem =
                if (event.code == 4014) {
                    "DISALLOWED_INTENTS: Message Content Intent 권한/설정 불일치 가능"
                } else {
                    event.closeCode?.meaning ?: "Discord gateway shutdown"
                }
            gatewayStatus.markShutdown(event.code, problem)
            if (event.code == 4014) onDisallowedIntents()
        }

        /** 슬래시 옵션 자동완성(#179): model 옵션에 풀 제공 모델 제안. */
        override fun onCommandAutoCompleteInteraction(event: CommandAutoCompleteInteractionEvent) {
            if (event.focusedOption.name != "model") return
            val guild = event.guild ?: return
            val ctx =
                CommandContext(
                    guildId = guild.idLong,
                    channelId = event.channelIdLong,
                    userId = event.user.idLong,
                    roleIds = emptySet(),
                    isAdmin = false,
                )
            val typed = event.focusedOption.value
            val choices =
                commands
                    .autocompleteModels(ctx)
                    .filter { it.startsWith(typed, ignoreCase = true) }
                    .take(25)
                    .map { Command.Choice(it, it) }
            event.replyChoices(choices).queue()
        }

        /** 패널 버튼: 온보딩(질문/기여/상태/도움말) + 설정. */
        override fun onButtonInteraction(event: ButtonInteractionEvent) {
            val ctx = ctxOf(event) // DM(유저설치)에서도 패널 버튼 동작(관리자 버튼은 isAdmin=false 로 거부됨)
            if (event.componentId.startsWith(IMG_CANCEL_PREFIX)) {
                // 이미지 생성 취소: 진행 중 요청을 중단(ComfyUI /interrupt). 최종 '취소됨' 응답은 imagine 흐름이 편집.
                val requestId = event.componentId.removePrefix(IMG_CANCEL_PREFIX)
                commands.cancelImage(requestId)
                event.editButton(Button.danger(event.componentId, "🛑 취소됨").asDisabled()).queue({}, {})
                return
            }
            if (event.componentId.startsWith(IMG_POST_PREFIX) ||
                event.componentId.startsWith(IMG_DISCARD_PREFIX) ||
                event.componentId.startsWith(IMG_MUTE_PREFIX)
            ) {
                // /그림 게시 확인 게이트: 본인만 보이는 미리보기에서 채널 게시/버리기/안묻기 선택.
                handleImaginePostButton(event)
                return
            }
            if (event.componentId.startsWith(ADMIN_ACT_RUN_PREFIX) || event.componentId.startsWith(ADMIN_ACT_CANCEL_PREFIX)) {
                handleAdminActionButton(event, ctx)
                return
            }
            if (event.componentId.startsWith(ONBOARD_PREFIX)) {
                onboarding.handleOnboardingButton(event, ctx)
                return
            }
            if (event.componentId == NiaChannelSetup.COMPONENT_ID) {
                // "🏗️ 니아 채널 자동 만들기" — 권한/멱등/생성은 핸들러가 자체 처리(RAG 온보딩과는 별개 기능).
                setupChannels.handle(event, ctx, ctx.userLang ?: commands.guildLanguage(ctx))
                return
            }
            when (event.componentId) {
                MenuFactory.ASK -> {
                    // 질문하기 → 모달로 질문 입력
                    event.replyModal(channelProfilePanel.askModal()).queue()
                    return
                }
                MenuFactory.SETTINGS -> {
                    if (!ctx.isAdmin) {
                        event.reply("⛔ 설정은 관리자만 가능합니다.").setEphemeral(true).queue()
                    } else {
                        event
                            .replyEmbeds(settingsWizard.settingsEmbed(ctx))
                            .addComponents(settingsWizard.settingsRows(ctx))
                            .setEphemeral(true)
                            .queue()
                    }
                    return
                }
                MenuFactory.HELP, "settings:help" -> {
                    event.replyEmbeds(EmbedFactory.helpEmbed(ctx.isAdmin, event.userLocale)).setEphemeral(true).queue()
                    return
                }
                CHANNEL_PROFILE_EDIT -> {
                    if (!ctx.isAdmin) {
                        event.reply("⛔ 채널 AI 프로필 설정은 관리자만 가능합니다.").setEphemeral(true).queue()
                    } else {
                        event.replyModal(channelProfilePanel.channelProfileModal(ctx)).queue()
                    }
                    return
                }
                CHANNEL_PROFILE_AVATAR -> {
                    if (!ctx.isAdmin) {
                        event.reply("⛔ 채널 AI 프로필 설정은 관리자만 가능합니다.").setEphemeral(true).queue()
                    } else {
                        event.replyModal(channelProfilePanel.channelProfileAvatarModal(ctx)).queue()
                    }
                    return
                }
                CHANNEL_PROFILE_RESET -> {
                    val reply = commands.setChannelAiProfile(ctx, null, null, reset = true)
                    event.reply(reply.content).setEphemeral(true).queue()
                    return
                }
                CHANNEL_PROFILE_ROLLBACK -> {
                    val reply = commands.setChannelAiProfile(ctx, null, null, reset = false, rollback = true)
                    event.reply(reply.content).setEphemeral(true).queue()
                    return
                }
                MenuFactory.PROVIDER -> {
                    if (commands.providerLinked(ctx)) {
                        // 이미 연동된 사용자 → 실제 참여 완료(앱이 동기화로 자동 연결).
                        event.reply(commands.providerJoin(ctx).content).setEphemeral(true).queue()
                    } else {
                        // 미연동: '내 PC 기여' → 먼저 설치할 OS 를 버튼으로 묻는다(차수 19).
                        event
                            .reply(
                                "🖥️ **내 컴퓨터의 AI로 함께 도와주기**\n\n" +
                                    "내 컴퓨터에 있는 AI가 커뮤니티 질문에 답하는 일을 함께 도와줘요.\n" +
                                    "복잡한 설정은 안내를 따라 하면 되고, 원할 때 언제든 멈출 수 있어요.\n\n" +
                                    "**설치할 컴퓨터**를 고르세요. 버튼을 누르면 복사해서 붙여넣을 명령을 보여드릴게요.",
                            ).addComponents(ActionRow.of(MenuFactory.osButtons()))
                            .setEphemeral(true)
                            .queue()
                    }
                    return
                }
            }
            // OS 선택 → 그 OS 복붙 설치 명령(토큰 포함, ephemeral).
            if (event.componentId.startsWith(MenuFactory.OS_PREFIX)) {
                val os = event.componentId.removePrefix(MenuFactory.OS_PREFIX)
                event.reply(commands.providerInstallGuide(ctx, os).content).setEphemeral(true).queue()
                return
            }
            // 설정 마법사 전용 버튼(자동승인 on/off·모든채널·채널일괄·취소·저장)은 단일 빈 핸들러로 위임.
            if (settingsWizard.handleButton(event, ctx)) return
            val reply =
                when (event.componentId) {
                    MenuFactory.STATUS -> commands.providerStatus(ctx)
                    MenuFactory.AUTO_APPROVE, "settings:autoapprove" -> commands.toggleAutoApprove(ctx)
                    else -> Replies.reject(Messages.get(Messages.Key.UNKNOWN_ACTION, langOf(ctx)))
                }
            event.reply(reply.content).setEphemeral(true).queue()
        }

        /** 설정 드롭다운(언어/모델). */
        override fun onStringSelectInteraction(event: StringSelectInteractionEvent) {
            val guild = event.guild ?: return
            val ctx = buildCtx(guild.idLong, event.member, event.channelIdLong, event.user.idLong)
            val value = event.values.firstOrNull().orEmpty()
            // 설정 마법사 전용 드롭다운(언어/모델/자동승인)은 단일 빈 핸들러로 위임.
            if (settingsWizard.handleStringSelect(event, ctx, value)) return
            event.reply(Replies.reject(Messages.get(Messages.Key.UNKNOWN_SELECTION, langOf(ctx))).content).setEphemeral(true).queue()
        }

        /** 설정 채널 허용(엔티티 선택). */
        override fun onEntitySelectInteraction(event: EntitySelectInteractionEvent) {
            if (event.componentId != MenuFactory.CHANNEL) return
            val guild = event.guild ?: return
            val ctx = buildCtx(guild.idLong, event.member, event.channelIdLong, event.user.idLong)
            settingsWizard.handleEntitySelect(event, ctx)
        }

        /** 봇이 서버에 들어오면 자동 온보딩 패널 게시. */
        override fun onGuildJoin(event: GuildJoinEvent) {
            // 시스템 채널이 꺼져 있어도 안내 0 이 되지 않게, 봇이 쓸 수 있는 첫 텍스트 채널로 폴백한다.
            // 시스템 채널이 있어도 봇이 못 쓰면(권한 없음) 다른 쓰기 가능한 채널을 고른다. 없으면 graceful 무시.
            val systemChannel = event.guild.systemChannel?.takeIf { it.canTalk() }
            val writableChannels = event.guild.textChannels.filter { it.canTalk() }
            val channel = selectOnboardingChannel(systemChannel, writableChannels) ?: return
            // 입장 즉시 자동 실행 금지 — consent-first. 관리자가 "AI 자동 설정하기" 버튼을 눌러야 시작된다.
            channel
                .sendMessageEmbeds(EmbedFactory.mainMenuEmbed(isAdmin = true))
                .setComponents(
                    ActionRow.of(MenuFactory.mainButtons(isAdmin = true)),
                    // 🐾 = RAG 자동 온보딩(별개), 🏗️ = 니아 채널 자동 만들기(카테고리/채널/가이드 생성). 둘 다 노출.
                    ActionRow.of(
                        Button.primary("$ONBOARD_PREFIX$ONBOARD_ACTION_START", "🐾 AI 자동 설정하기"),
                        Button.primary(NiaChannelSetup.COMPONENT_ID, I18n.get("niaSetupChannelsButton", I18n.DEFAULT)),
                    ),
                ).queue({}, {})
        }

        /** 봇이 서버에서 제거되면 그 서버의 프로바이더 연결/등록/설정을 정리한다. */
        override fun onGuildLeave(event: GuildLeaveEvent) {
            turnBoundaryCoordinator.cancelGuild(event.guild.idLong)
            niaCatchUpCadence.clearGuild(event.guild.idLong)
            participationEmitBridge.onGuildDisabled(event.guild.idLong)
            guildCleanup.cleanup(event.guild.idLong)
        }

        /** 프로바이더 유저가 서버를 떠나면 해당 서버의 provider 상태만 정리한다. 기여 로그는 유지한다. */
        override fun onGuildMemberRemove(event: GuildMemberRemoveEvent) {
            turnBoundaryCoordinator.cancelUser(guildId = event.guild.idLong, userId = event.user.idLong)
            participationEmitBridge.onUserOptedOut(guildId = event.guild.idLong, userId = event.user.idLong)
            reconciliation.cleanupMember(event.guild.idLong, event.user.idLong)
        }

        /** 채널 삭제 이벤트가 오면 허용 채널 정책과 채널 AI 프로필을 같이 정리한다. */
        override fun onChannelDelete(event: ChannelDeleteEvent) {
            if (event.isFromGuild) {
                val routingId = event.channel.idLong
                niaTurnGenerations.invalidateCurrent(routingId)
                turnBoundaryCoordinator.cancel(routingId)
                niaCatchUpCadence.clearChannel(guildId = event.guild.idLong, channelId = event.channel.idLong)
                participationEmitBridge.onChannelDisabled(guildId = event.guild.idLong, channelId = event.channel.idLong)
                reconciliation.cleanupChannel(event.guild.idLong, event.channel.idLong)
            }
        }

        /** 메시지 삭제 이벤트는 raw context 에서 해당 원문을 즉시 제거한다. */
        override fun onMessageDelete(event: MessageDeleteEvent) {
            if (!event.isFromGuild) return
            niaRuntimeMetrics.recordIngress(NiaIngressSource.DELETE)
            val guildId = event.guild.idLong
            val scope = discordMessageScope(event.channel.idLong, event.channel.type.isThread)
            val messageId = event.messageIdLong
            niaTurnGenerations.invalidateCurrent(scope.routingId)
            turnBoundaryCoordinator.cancel(scope.routingId)
            submitRawContextMutation(event = NiaDispatchEvent.DELETE, channelId = scope.routingId) {
                removeRecentMessage(scope.routingId, messageId)
                niaCatchUpCadence.clearScope(scope.toNiaCatchUpScope(guildId))
                participationEmitBridge.onMessageDeleted(
                    com.discordassistant.central.platform.discord.nexa.ParticipationRawContextRedactionSignal(
                        guildId = guildId,
                        channelId = scope.channelId,
                        threadId = scope.threadId,
                        messageId = messageId,
                    ),
                )
            }
        }

        /** 메시지 수정 이벤트는 raw context 의 원문만 갱신하고, participation judge/emit 은 다시 실행하지 않는다. */
        override fun onMessageUpdate(event: MessageUpdateEvent) {
            if (!event.isFromGuild) return
            niaRuntimeMetrics.recordIngress(NiaIngressSource.EDIT)
            val occurredAt =
                (event.message.timeEdited ?: event.message.timeCreated)
                    .toInstant()
            val scope = discordMessageScope(event.channel.idLong, event.channel.type.isThread)
            niaTurnGenerations.invalidateCurrent(scope.routingId)
            turnBoundaryCoordinator.cancel(scope.routingId)
            val signal =
                com.discordassistant.central.platform.discord.nexa.ParticipationRawContextEditSignal(
                    guildId = event.guild.idLong,
                    channelId = scope.channelId,
                    messageId = event.messageIdLong,
                    userId = event.author.idLong,
                    threadId = scope.threadId,
                    replyToMessageId = event.message.referencedMessage?.idLong,
                    sourceType = participationSourceTypeOf(event),
                    rawText = event.message.contentRaw.trim(),
                    occurredAt = occurredAt,
                )
            submitRawContextMutation(event = NiaDispatchEvent.EDIT, channelId = scope.routingId) {
                updateRecentMessage(event, scope.routingId)
                niaCatchUpCadence.clearScope(scope.toNiaCatchUpScope(event.guild.idLong))
                participationEmitBridge.onMessageEdited(signal)
            }
        }

        override fun onUserTyping(event: UserTypingEvent) {
            if (!typingIntentEnabled || !event.type.isGuild) return
            if (event.user.isBot || event.user.idLong == event.jda.selfUser.idLong) return
            val scope = discordMessageScope(event.channel.idLong, event.channel.type.isThread)
            turnBoundaryCoordinator.onTyping(scope.routingId, event.user.idLong)
        }

        /**
         * Redaction/edit events must not disappear just because the bounded receive dispatcher is closing. Saturation
         * normally enters its ordered mutation overflow; rejection can only happen after shutdown, when this direct
         * fallback is safer than retaining raw context.
         */
        private fun submitRawContextMutation(
            event: NiaDispatchEvent,
            channelId: Long,
            mutation: () -> Unit,
        ) {
            val task = { runChannelEvent(event.label, channelId, mutation) }
            val admission = channelEventDispatcher.submitMutation(channelId, task)
            niaRuntimeMetrics.recordAdmission(event, admission.metricOutcome)
            logMutationAdmission(event.label, channelId, admission)
            if (admission == DiscordChannelEventAdmission.REJECTED) {
                log.error("Discord message {} dispatcher rejected mutation; applying privacy fallback(channel={})", event.label, channelId)
                task()
            }
        }

        private fun logMutationAdmission(
            eventType: String,
            channelId: Long,
            admission: DiscordChannelEventAdmission,
        ) {
            when (admission) {
                DiscordChannelEventAdmission.ACCEPTED -> Unit
                DiscordChannelEventAdmission.ACCEPTED_AFTER_EVICTION ->
                    log.warn(
                        "Discord message {} admission evicted one queued ordinary event(channel={})",
                        eventType,
                        channelId,
                    )
                DiscordChannelEventAdmission.ACCEPTED_TO_MUTATION_OVERFLOW ->
                    log.warn(
                        "Discord message {} admission entered ordered mutation overflow(channel={})",
                        eventType,
                        channelId,
                    )
                DiscordChannelEventAdmission.REJECTED ->
                    log.error("Discord message {} admission rejected(channel={})", eventType, channelId)
            }
        }

        private fun runChannelEvent(
            eventType: String,
            channelId: Long,
            task: () -> Unit,
        ) {
            try {
                task()
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                log.warn("Discord message {} processing failed(channel={})", eventType, channelId, e)
            }
        }

        /** 긴 질문 모달 제출(#189). */
        override fun onModalInteraction(event: ModalInteractionEvent) {
            val ctx = ctxOf(event) // DM(유저설치)에서도 긴 질문 모달 동작
            if (event.modalId == CHANNEL_PROFILE_SAVE_MODAL) {
                val reply =
                    commands.setChannelAiProfile(
                        ctx,
                        event.getValue("name")?.asString,
                        channelProfiles.get(ctx.guildId, ctx.channelId)?.avatarUrl,
                        reset = false,
                        purpose = event.getValue("purpose")?.asString,
                        tone = event.getValue("tone")?.asString,
                        answerLength = event.getValue("answer-length")?.asString,
                        constitution = event.getValue("constitution")?.asString,
                    )
                event.reply(reply.content).setEphemeral(true).queue()
                return
            }
            // 설정 마법사 채널 일괄 모달은 단일 빈 핸들러로 위임(pending 갱신 + 패널 재렌더).
            if (settingsWizard.handleModal(event, ctx)) return
            if (event.modalId == CHANNEL_PROFILE_AVATAR_MODAL) {
                val current = channelProfiles.get(ctx.guildId, ctx.channelId)
                if (current == null) {
                    event.reply("먼저 `프로필 편집`에서 이름을 저장한 뒤 아이콘을 설정하세요.").setEphemeral(true).queue()
                    return
                }
                val reply =
                    commands.setChannelAiProfile(
                        ctx,
                        current.displayName,
                        event.getValue("avatar-url")?.asString,
                        reset = false,
                        purpose = current.purpose,
                        tone = current.tone,
                        answerLength = current.answerLength,
                        constitution = current.constitution,
                    )
                event.reply(reply.content).setEphemeral(true).queue()
                return
            }
            if (event.modalId != "ask-long-modal") return
            val prompt = event.getValue("prompt")?.asString.orEmpty()
            val useWebhookProfile = channelProfiles.get(ctx.guildId, ctx.channelId) != null
            event.deferReply(useWebhookProfile).queue()
            val reply = commands.ask(ctx, prompt)
            if (useWebhookProfile) {
                answers.completePublicAnswerWithProfileFallback(event.hook, event.channel, ctx, reply)
            } else {
                answers.editOriginalWithPseudoStream(event.hook, reply)
            }
        }

        /** 메시지 컨텍스트 메뉴(#181): 그 메시지 내용으로 질문. */
        override fun onMessageContextInteraction(event: MessageContextInteractionEvent) {
            if (event.name != "AI에게 질문") return
            val guild = event.guild ?: return
            val ctx = buildCtx(guild.idLong, event.member, event.channelIdLong, event.user.idLong)
            val useWebhookProfile = channelProfiles.get(ctx.guildId, ctx.channelId) != null
            event.deferReply(useWebhookProfile).queue()
            val reply = commands.ask(ctx, event.target.contentRaw)
            if (useWebhookProfile) {
                answers.completePublicAnswerWithProfileFallback(event.hook, event.channel, ctx, reply)
            } else {
                answers.editOriginalWithPseudoStream(event.hook, reply)
            }
        }

        /**
         * 메시지 자동 처리(콘텐츠 인텐트 필요):
         *  ① 봇 멘션(@니아) → 멘션 텍스트로 /ask 흐름.
         *  ② "AI 채팅 채널"(자동응답 켜진 채널) → 멘션 없이 모든 텍스트 메시지에 자동 응답.
         * 자동응답은 카미봇처럼 `.` 로 시작하는 메시지·빈 내용·봇/웹훅 메시지는 제외한다.
         * 자동응답 채널 판정은 [autoRespondChannels] 인메모리 캐시(O(1))로 — 메시지당 DB 조회를 하지 않는다.
         */
        override fun onMessageReceived(event: MessageReceivedEvent) {
            if (!mentionAskEnabled || !event.isFromGuild) return
            niaRuntimeMetrics.recordIngress(NiaIngressSource.MESSAGE)
            val scope = discordMessageScope(event.channel.idLong, event.channel.type.isThread)
            val channelId = scope.routingId
            val sourceType = participationSourceTypeOf(event)
            val rawContextPreCaptured = AtomicBoolean(false)
            if (sourceType == com.discordassistant.central.platform.discord.nexa.ParticipationMessageSourceType.HUMAN) {
                // FIFO worker 밖에서 먼저 갱신해야, 이미 judge 중인 이전 메시지도 새 장면 도착을 감지해 결과를 버린다.
                turnBoundaryCoordinator.onMessageIngress(channelId, event.messageIdLong)
                niaTurnGenerations.observe(channelId, event.messageIdLong)
                val rawSignal =
                    com.discordassistant.central.platform.discord.nexa.ParticipationRawContextEditSignal(
                        guildId = event.guild.idLong,
                        channelId = scope.channelId,
                        messageId = event.messageIdLong,
                        userId = event.author.idLong,
                        threadId = scope.threadId,
                        replyToMessageId = event.message.referencedMessage?.idLong,
                        sourceType = sourceType,
                        rawText = event.message.contentRaw.trim(),
                        occurredAt = event.message.timeCreated.toInstant(),
                    )
                submitRawContextMutation(event = NiaDispatchEvent.RECEIVE_CONTEXT, channelId = channelId) {
                    rawContextPreCaptured.set(
                        participationEmitBridge.onHumanMessageObserved(rawSignal) ==
                            com.discordassistant.central.platform.discord.nexa.ParticipationRawContextMutationOutcome.Upserted,
                    )
                }
            }
            val admission =
                channelEventDispatcher.submit(channelId) {
                    runChannelEvent("receive_evaluation", channelId) {
                        try {
                            processMessageReceived(event, scope, rawContextPreCaptured.get())
                        } catch (e: Exception) {
                            turnBoundaryCoordinator.cancel(channelId)
                            throw e
                        }
                    }
                }
            niaRuntimeMetrics.recordAdmission(NiaDispatchEvent.RECEIVE_EVALUATION, admission.metricOutcome)
            if (!admission.accepted) {
                turnBoundaryCoordinator.cancel(channelId)
                log.warn("Discord message admission rejected — fail closed(channel={})", channelId)
            }
        }

        private fun processMessageReceived(
            event: MessageReceivedEvent,
            scope: DiscordMessageScope,
            rawContextPreCaptured: Boolean,
        ) {
            rememberRecentMessage(event, scope.routingId)
            val selfId = event.jda.selfUser.idLong
            if (event.author.isBot) {
                if (event.author.idLong == selfId && !event.message.isWebhookMessage) {
                    participationEmitBridge.onAssistantMessageObserved(
                        com.discordassistant.central.platform.discord.nexa.ParticipationRawContextEditSignal(
                            guildId = event.guild.idLong,
                            channelId = scope.channelId,
                            messageId = event.messageIdLong,
                            userId = selfId,
                            threadId = scope.threadId,
                            replyToMessageId = event.message.referencedMessage?.idLong,
                            sourceType = com.discordassistant.central.platform.discord.nexa.ParticipationMessageSourceType.BOT,
                            rawText = event.message.contentRaw.trim(),
                            occurredAt = event.message.timeCreated.toInstant(),
                        ),
                    )
                }
                return
            }
            val mentioned =
                event.message.mentions.users
                    .any { it.idLong == selfId }
            val directNamePrompt = niaDirectAddressPrompt(event.message.contentRaw)
            val directlyAddressed = directNamePrompt != null
            val recentMessages = recentMessagesSnapshot(scope.routingId)
            val replyToNia =
                event.message.referencedMessage
                    ?.author
                    ?.idLong == selfId
            val replyToHuman = event.message.referencedMessage?.let { !it.author.isBot && it.author.idLong != selfId } ?: false
            val niaTurnContinuation =
                deriveNiaTurnContinuation(
                    messages = recentMessages,
                    currentMessageId = event.messageIdLong,
                    botUserId = selfId,
                    currentRepliesToHuman = replyToHuman,
                )
            val attachmentShownToNia =
                isAttachmentShownToNia(
                    directlyAddressed = mentioned || directlyAddressed,
                    replyToNia = replyToNia,
                    niaTurnContinuationLikely = niaTurnContinuation.likely,
                )
            val unsupportedAttachmentRequest =
                unsupportedPdfRequest(
                    messages = recentMessages,
                    currentMessageId = event.messageIdLong,
                ).takeIf { attachmentShownToNia }
            val speechImageInput =
                if (attachmentShownToNia && unsupportedAttachmentRequest == null) {
                    when (val prepared = imageAttachmentPreparer.prepare(event.message.attachments)) {
                        TargetedImagePreparation.NoImage -> null
                        is TargetedImagePreparation.Ready -> prepared.image
                        is TargetedImagePreparation.Rejected -> {
                            respondLocallyInChannel(event, prepared.template)
                            return
                        }
                    }
                } else {
                    null
                }
            // The bridge captures rollout mode once with its social decision. Shadow preserves legacy behavior;
            // CANARY/LIVE owns the turn only through FINAL, and a non-final misconfiguration fails closed rather than
            // allowing an unjudged legacy response.
            val participationTurn =
                forwardToParticipation(
                    event = event,
                    scope = scope,
                    mentioned = mentioned || directlyAddressed,
                    rawContextPreCaptured = rawContextPreCaptured,
                    unsupportedAttachmentRequest = unsupportedAttachmentRequest,
                    speechImageInput = speechImageInput,
                )
            if (participationTurn.ownsTurn) return

            // OFF/SHADOW 및 final judge가 아직 real-send가 아닌 채널은 기존 명시 호출/자동응답 계약을 유지한다.
            if (speechImageInput != null) {
                respondLocallyInChannel(event, LocalSpeechTemplate.IMAGE_FEATURE_UNAVAILABLE)
                return
            }
            if (mentioned) {
                if (unsupportedAttachmentRequest == UnsupportedAttachmentRequest.PDF_READ) {
                    respondLocallyInChannel(event, LocalSpeechTemplate.PDF_UNSUPPORTED)
                } else {
                    handleMentionAsk(event, selfId)
                }
                return
            }
            if (directNamePrompt != null) {
                if (unsupportedAttachmentRequest == UnsupportedAttachmentRequest.PDF_READ) {
                    respondLocallyInChannel(event, LocalSpeechTemplate.PDF_UNSUPPORTED)
                } else {
                    handleDirectNameAsk(event, directNamePrompt)
                }
                return
            }
            val continuationPrompt = niaContinuationPrompt(event)
            if (continuationPrompt != null) {
                if (unsupportedAttachmentRequest == UnsupportedAttachmentRequest.PDF_READ) {
                    respondLocallyInChannel(event, LocalSpeechTemplate.PDF_UNSUPPORTED)
                } else {
                    metrics.record("name-ask-continuation")
                    respondInChannel(event, continuationPrompt, fastResponse = false)
                }
                return
            }
            handleAutoRespond(
                event,
                localSpeechTemplate =
                    LocalSpeechTemplate.PDF_UNSUPPORTED
                        .takeIf { unsupportedAttachmentRequest == UnsupportedAttachmentRequest.PDF_READ },
            )
        }

        /**
         * NEXA participation 발화 브리지에 메시지 신호를 위임한다(단계 1 wiring). 가명화는 브리지가 하므로 raw 식별자와
         * 가명 라벨 turn 만 만들어 넘긴다(원문 user id 미저장). 멱등·seed 는 채널·메시지 id 로 결정론 도출한다.
         * 호출 자체도 fail-closed다. 신호 구성 또는 judge가 실패해도 활성 participation 채널에서 legacy 응답으로 우회하지 않는다.
         */
        private fun forwardToParticipation(
            event: MessageReceivedEvent,
            scope: DiscordMessageScope,
            mentioned: Boolean,
            rawContextPreCaptured: Boolean,
            unsupportedAttachmentRequest: UnsupportedAttachmentRequest?,
            speechImageInput: SpeechImageInput?,
        ): ParticipationTurnOutcome =
            try {
                val messageId = event.messageIdLong
                val selfId = event.jda.selfUser.idLong
                val speakerLabel = "user_${event.author.idLong % 100000}"
                val contentRaw = event.message.contentRaw.trim()
                val tsMs =
                    event.message.timeCreated
                        .toInstant()
                        .toEpochMilli()
                val recentMessages = recentMessagesSnapshot(scope.routingId)
                val recentTurns = recentParticipationTurns(recentMessages, selfId)
                val recentRawMessages = recentParticipationRawMessages(recentMessages, selfId)
                // core 결정론 규칙([CoreInterventionRules])용 raw 신호: 트리거 원문(짧게)·화자 라벨·니아 발화 reply 여부.
                // referencedMessage 가 봇 자신(니아) 메시지면 reply-to-nia(RESPOND_NOW). 없으면 보수적 기본값(false).
                // niaReply = 트리거가 reply 한 대상이 니아 메시지일 때만 그 메시지(아니면 null).
                val niaReply = event.message.referencedMessage?.takeIf { it.author.idLong == selfId }
                val replyToNia = niaReply != null
                val replyToHuman = event.message.referencedMessage?.let { !it.author.isBot && it.author.idLong != selfId } ?: false
                val niaTurnContinuation =
                    deriveNiaTurnContinuation(
                        messages = recentMessages,
                        currentMessageId = event.messageIdLong,
                        botUserId = selfId,
                        currentRepliesToHuman = replyToHuman,
                    )

                // continuation(A7): 트리거가 니아 발화에 대한 reply 면, 그 referencedMessage(니아 발화) 토큰을 continuation
                // 후보로 쓴다. 니아 메시지는 봇 author 라 이 핸들러로 직접 오지 않으므로(early-return), reply 의
                // referencedMessage 가 유일하게 신뢰할 수 있는 "니아 직전 발화" 출처다. TTL 은 그 메시지 시각과 비교.
                // reply 가 아니면 빈 토큰(continuation 시도 안 함 — 보수적·덜 발화).
                val niaRecentTokens: List<String>
                val withinContinuationTtl: Boolean
                if (niaReply != null) {
                    val niaTsMs = niaReply.timeCreated.toInstant().toEpochMilli()
                    niaRecentTokens = niaTokensOf(niaReply.contentRaw)
                    withinContinuationTtl =
                        (tsMs - niaTsMs) in
                        0..com.discordassistant.central.participation.domain.service.CoreInterventionRules.CONTINUATION_TTL_MS
                } else {
                    niaRecentTokens = emptyList()
                    withinContinuationTtl = false
                }

                // 히스토리 도출 신호(A4 중복·B1 burst 미완·B17 사적 핑퐁) — 채널별 사람 메시지 버퍼에서 도출(추가 JDA 조회 0).
                val mentionsNiaInTrigger = mentioned || contentRaw.nfc().contains("니아")
                val derived =
                    participationSignals.deriveAndRecord(
                        channelId = scope.channelId,
                        contextBoundaryId = scope.threadId,
                        trigger =
                            com.discordassistant.central.platform.discord.nexa.ParticipationSignalDeriver
                                .HumanMessage(
                                    speakerLabel = speakerLabel,
                                    text = contentRaw,
                                    tsMs = tsMs,
                                    mentionsNia = mentionsNiaInTrigger,
                                ),
                    )

                val signal =
                    com.discordassistant.central.platform.discord.nexa.ParticipationMessageSignal(
                        guildId = event.guild.idLong,
                        channelId = scope.routingId,
                        messageId = messageId,
                        userId = event.author.idLong,
                        threadId = scope.threadId,
                        replyToMessageId = event.message.referencedMessage?.idLong,
                        sourceType = participationSourceTypeOf(event),
                        mentioned = mentioned,
                        recentTurns = recentTurns,
                        recentRawMessages = recentRawMessages,
                        triggerText = contentRaw.take(500),
                        rawText = contentRaw,
                        unsupportedAttachmentRequest = unsupportedAttachmentRequest,
                        speechImageInput = speechImageInput,
                        speakerLabel = speakerLabel,
                        replyToNia = replyToNia,
                        replyToHuman = replyToHuman,
                        niaRecentTokens = niaRecentTokens,
                        withinContinuationTtl = withinContinuationTtl,
                        duplicateOfPrevHuman = derived.duplicateOfPrevHuman,
                        burstIncomplete = derived.burstIncomplete,
                        priorHumanSpeakerLabels = derived.priorHumanSpeakerLabels,
                        firstMessageText = derived.firstMessageText,
                        conversationMentionsNia = derived.conversationMentionsNia,
                        lastNiaSpokeAgeSeconds = niaTurnContinuation.lastNiaSpokeAgeSeconds,
                        niaTurnContinuationLikely = niaTurnContinuation.likely,
                        tsMs = tsMs,
                        // 실제 값은 participation bridge가 conversation projection read-after-write 결과로 덮어쓴다.
                        sceneSeq = 0,
                        contextVersion = 0,
                        seed = messageId,
                        turnGeneration = messageId,
                        rawContextPreCaptured = rawContextPreCaptured,
                    )
                deferOrEvaluateParticipation(signal, selfId)
            } catch (e: Exception) {
                turnBoundaryCoordinator.cancel(scope.routingId)
                log.debug("NEXA participation 신호 구성 실패(channel={}) — fail-closed 여부를 브리지 모드로 결정: {}", event.channel.idLong, e.message)
                participationEmitBridge.failedMessageTurn(guildId = event.guild.idLong, channelId = event.channel.idLong)
            }

        private fun deferOrEvaluateParticipation(
            signal: com.discordassistant.central.platform.discord.nexa.ParticipationMessageSignal,
            selfId: Long,
        ): ParticipationTurnOutcome {
            if (!participationFlags.isNexaActive(signal.guildId, signal.channelId)) {
                return participationEmitBridge.onMessageTurn(signal)
            }
            when (niaCatchUpCadence.admit(signal.toNiaCatchUpMessage())) {
                NiaCatchUpAdmission.DEFERRED ->
                    return ParticipationTurnOutcome(
                        outcome =
                            com.discordassistant.central.platform.discord.nexa.ParticipationEmitOutcome.AttentionDeferred(
                                "catch-up:${signal.channelId}",
                            ),
                        ownsTurn = participationFlags.allowsRealSend(signal.guildId, signal.channelId),
                    )
                NiaCatchUpAdmission.WAKE_NOW -> {
                    turnBoundaryCoordinator.cancel(signal.channelId)
                    return evaluateParticipationAndRecord(signal)
                }
                NiaCatchUpAdmission.EVALUATE_NOW -> Unit
            }
            val admission =
                turnBoundaryCoordinator.onMessage(
                    realSendAtIngress = participationFlags.allowsRealSend(signal.guildId, signal.channelId),
                    routingId = signal.channelId,
                    generation = signal.turnGeneration,
                    signal = signal,
                    callbacks =
                        NiaTurnBoundaryCoordinator.Callbacks(
                            stillRealSendEnabled = {
                                participationFlags.allowsRealSend(signal.guildId, signal.channelId)
                            },
                            isLatestGeneration = { routingId, generation ->
                                niaTurnGenerations.isLatest(routingId, generation)
                            },
                            enqueueOnDispatcher = { routingId, task ->
                                channelEventDispatcher.submit(routingId, task).accepted
                            },
                            judge = judge@{ delayedSignal ->
                                val refreshed = refreshDelayedParticipationSignal(delayedSignal, selfId)
                                evaluateParticipationAndRecord(refreshed)
                            },
                            onFailClosed = {
                                niaRuntimeMetrics.recordTurnBoundary(
                                    NiaTurnBoundaryAdmission.FAIL_CLOSED,
                                    explicitlyAddressed = signal.mentioned,
                                )
                            },
                        ),
                )
            niaRuntimeMetrics.recordTurnBoundary(admission, explicitlyAddressed = signal.mentioned)
            return when (admission) {
                NiaTurnBoundaryAdmission.BYPASS -> evaluateParticipationAndRecord(signal)
                NiaTurnBoundaryAdmission.DEFERRED ->
                    ParticipationTurnOutcome(
                        outcome =
                            com.discordassistant.central.platform.discord.nexa.ParticipationEmitOutcome.AttentionDeferred(
                                "turn-boundary:${signal.channelId}",
                            ),
                        ownsTurn = true,
                    )
                NiaTurnBoundaryAdmission.FAIL_CLOSED ->
                    ParticipationTurnOutcome(
                        outcome = com.discordassistant.central.platform.discord.nexa.ParticipationEmitOutcome.Failed,
                        ownsTurn = true,
                    )
            }
        }

        private fun evaluateParticipationAndRecord(signal: ParticipationMessageSignal): ParticipationTurnOutcome {
            val outcome = participationEmitBridge.onMessageTurn(signal)
            runCatching {
                niaCatchUpCadence.recordEvaluation(signal.toNiaCatchUpMessage(), outcome.outcome.toNiaCatchUpJudgeResult())
            }.onFailure { error ->
                log.warn("NIA CATCH_UP 상태 기록 실패(channel={}) — 기존 Judge 결과는 유지: {}", signal.channelId, error.message)
            }
            return outcome
        }

        private fun DiscordMessageScope.toNiaCatchUpScope(guildId: Long): NiaCatchUpScope =
            NiaCatchUpScope(guildId = guildId, channelId = routingId, threadId = threadId)

        private fun refreshDelayedParticipationSignal(
            signal: com.discordassistant.central.platform.discord.nexa.ParticipationMessageSignal,
            selfId: Long,
        ): com.discordassistant.central.platform.discord.nexa.ParticipationMessageSignal {
            val recentMessages = recentMessagesSnapshot(signal.channelId)
            return refreshDelayedTriggerSignal(signal, recentMessages, selfId).copy(
                recentTurns = recentParticipationTurns(recentMessages, selfId),
                recentRawMessages = recentParticipationRawMessages(recentMessages, selfId),
            )
        }

        private fun participationSourceTypeOf(
            event: MessageReceivedEvent,
        ): com.discordassistant.central.platform.discord.nexa.ParticipationMessageSourceType =
            when {
                event.message.type != MessageType.DEFAULT && event.message.type != MessageType.INLINE_REPLY ->
                    com.discordassistant.central.platform.discord.nexa.ParticipationMessageSourceType.SYSTEM
                event.message.isWebhookMessage ->
                    com.discordassistant.central.platform.discord.nexa.ParticipationMessageSourceType.WEBHOOK
                event.author.isBot ->
                    com.discordassistant.central.platform.discord.nexa.ParticipationMessageSourceType.BOT
                else -> com.discordassistant.central.platform.discord.nexa.ParticipationMessageSourceType.HUMAN
            }

        private fun participationSourceTypeOf(
            event: MessageUpdateEvent,
        ): com.discordassistant.central.platform.discord.nexa.ParticipationMessageSourceType =
            when {
                event.message.type != MessageType.DEFAULT && event.message.type != MessageType.INLINE_REPLY ->
                    com.discordassistant.central.platform.discord.nexa.ParticipationMessageSourceType.SYSTEM
                event.message.isWebhookMessage ->
                    com.discordassistant.central.platform.discord.nexa.ParticipationMessageSourceType.WEBHOOK
                event.author.isBot ->
                    com.discordassistant.central.platform.discord.nexa.ParticipationMessageSourceType.BOT
                else -> com.discordassistant.central.platform.discord.nexa.ParticipationMessageSourceType.HUMAN
            }

        /**
         * 니아 직전 발화(reply 의 referencedMessage)에서 continuation 매칭용 토큰을 뽑는다 — core
         * [CoreInterventionRules] continuation 과 같은 규칙(한글/영숫자, 최소 길이 2, 소문자). 규칙이 다시 토큰화하므로
         * 여기선 길이 필터만 맞춰 잡음을 줄인다.
         */
        private fun niaTokensOf(text: String): List<String> =
            NIA_TOKEN_RE
                .findAll(text)
                .map { it.value.lowercase() }
                .filter { it.length >= 2 }
                .toList()

        /** 봇 멘션 질문: `@니아 질문` 을 기존 /ask 와 같은 Provider Pool 흐름으로 처리한다. */
        private fun handleMentionAsk(
            event: MessageReceivedEvent,
            selfId: Long,
        ) {
            val prompt = mentionPrompt(event.message.contentRaw, selfId)
            if (prompt.isBlank()) {
                event.message
                    .reply("질문 내용을 같이 적어주세요. 예: `@니아 오늘 회의 요약해줘`")
                    .mentionRepliedUser(false)
                    .queue()
                return
            }
            metrics.record("mention-ask")
            respondInChannel(event, buildNiaAddressedPrompt(event.message.contentRaw, prompt))
        }

        /** 이름 호명 질문: `니아야`/`니아 ...` 를 @멘션과 같은 Provider Pool 흐름으로 처리한다. */
        private fun handleDirectNameAsk(
            event: MessageReceivedEvent,
            prompt: String,
        ) {
            metrics.record("name-ask")
            respondInChannel(event, buildNiaAddressedPrompt(event.message.contentRaw, prompt), fastResponse = false)
        }

        /**
         * AI 채팅 채널 자동응답: 자동응답이 켜진 채널의 모든 텍스트 메시지에 멘션 없이 응답한다.
         * `.` 로 시작하는 메시지(카미봇 컨벤션)·빈 내용은 무시한다(스킵). 캐시 조회가 O(1) 라 비-AI채팅 채널은 즉시 return.
         *
         * **비용 캡**: 자동응답 채널은 N명이 떠들면 N배의 LLM 추론이 일어나 관리자 클라우드 키 비용이 폭주한다.
         * per-user ask 쿨다운은 관리자가 우회하지만, 이 **채널 단위 분당 상한은 누구도(관리자 포함) 우회하지 못한다**.
         * 한도를 넘으면 채널에 안내를 도배하지 않고 조용히 드롭(⏳ 리액션만) — 일반 /ask 의 쿨다운 안내는 그대로 유지된다.
         */
        private fun handleAutoRespond(
            event: MessageReceivedEvent,
            localSpeechTemplate: LocalSpeechTemplate? = null,
        ) {
            val guildId = event.guild.idLong
            val channelId = event.channel.idLong
            if (!autoRespondChannels.isAutoRespond(guildId, channelId)) return
            val content = event.message.contentRaw
            if (!AutoRespondChannelRegistry.shouldRespond(content)) return // `.` 시작·빈 내용 제외
            if (!rateLimiter.tryAcquire("autorespond:$guildId:$channelId")) {
                metrics.record("auto-respond-throttled")
                event.message.addReaction(Emoji.fromUnicode("⏳")).queue({}, {}) // 조용히 드롭(도배 금지)
                return
            }
            metrics.record("auto-respond")
            if (localSpeechTemplate == null) {
                respondInChannel(event, buildNiaAutoRespondPrompt(content))
            } else {
                respondLocallyInChannel(event, localSpeechTemplate)
            }
        }

        /** PDF 미지원처럼 모델 판단이 필요 없는 고정 정책 응답. OpenAI/provider 경로는 호출하지 않는다. */
        private fun respondLocallyInChannel(
            event: MessageReceivedEvent,
            template: LocalSpeechTemplate,
        ) {
            metrics.record("local-${template.name.lowercase()}")
            val ctx =
                buildCtx(
                    event.guild.idLong,
                    event.member,
                    event.channel.idLong,
                    event.author.idLong,
                )
            val reply = Reply(content = template.text, ephemeral = false).withNiaChatStyle()
            if (channelProfiles.get(ctx.guildId, ctx.channelId) != null && answers.sendAnswerWebhook(event.channel, ctx, reply)) {
                event.message.addReaction(Emoji.fromUnicode("✅")).queue({}, {})
            } else {
                answers.replyToMessageWithPseudoStream(event.message, reply)
            }
            rememberNiaReply(
                channelId = event.channel.idLong,
                botUserId = event.jda.selfUser.idLong,
                content = reply.content,
                replyToMessageId = event.messageIdLong,
            )
        }

        /** 멘션/자동응답 공통: /ask → (프로필 있으면 웹훅 페르소나, 없으면 답장 스트림). 에러는 동일 처리. */
        private fun respondInChannel(
            event: MessageReceivedEvent,
            prompt: String,
            fastResponse: Boolean = false,
        ) {
            val totalStartedAt = System.nanoTime()
            val ctx =
                buildCtx(
                    event.guild.idLong,
                    event.member,
                    event.channel.idLong,
                    event.author.idLong,
                )
            val useWebhookProfile = channelProfiles.get(ctx.guildId, ctx.channelId) != null
            // Discord typing 은 약 10초 유지되고 취소할 수 없어 GLM tail latency 와 묶지 않는다.
            // 메시지 응답은 실시간성이 우선이다. social-mind/appraiser 는 외부 LLM 호출이라 답변 앞에 두지 않는다.
            val toneMs = 0L
            val toneDirective = ""
            val ambientHistory = recentChannelContext(event)
            var askMs = 0L
            var renderMs = 0L
            try {
                event.channel
                    .sendTyping()
                    .queue(
                        {},
                        { e ->
                            log.debug(
                                "Discord typing 시작 실패(channel={}): {}",
                                event.channel.idLong,
                                e.message,
                            )
                        },
                    )
                val askStartedAt = System.nanoTime()
                val rawReply =
                    commands.ask(
                        ctx,
                        prompt,
                        toneDirective = toneDirective,
                        ambientHistory = ambientHistory,
                        fastResponse = fastResponse,
                    )
                val reply = rawReply.withNiaChatStyle()
                askMs = elapsedMs(askStartedAt)
                val renderStartedAt = System.nanoTime()
                if (useWebhookProfile && answers.sendAnswerWebhook(event.channel, ctx, reply)) {
                    event.message
                        .addReaction(Emoji.fromUnicode("✅"))
                        .queue({}, {})
                } else {
                    answers.replyToMessageWithPseudoStream(event.message, reply)
                }
                rememberNiaReply(
                    channelId = event.channel.idLong,
                    botUserId = event.jda.selfUser.idLong,
                    content = reply.content,
                    replyToMessageId = event.messageIdLong,
                )
                renderMs = elapsedMs(renderStartedAt)
                log.info(
                    "Discord message AI latency guild={} channel={} user={} fast={} webhook={} toneMs={} askMs={} renderMs={} totalMs={} replyChars={}",
                    ctx.guildId,
                    ctx.channelId,
                    ctx.userId,
                    fastResponse,
                    useWebhookProfile,
                    toneMs,
                    askMs,
                    renderMs,
                    elapsedMs(totalStartedAt),
                    reply.content.length,
                )
            } catch (e: Exception) {
                log.warn(
                    "메시지 자동 처리 실패(channel={}, user={}, fast={}, toneMs={}, askMs={}, renderMs={}, totalMs={}): {}",
                    event.channel.idLong,
                    event.author.idLong,
                    fastResponse,
                    toneMs,
                    askMs,
                    renderMs,
                    elapsedMs(totalStartedAt),
                    e.message,
                )
                event.message
                    .reply("⚠️ 처리 중 오류가 발생했어요. 잠시 후 다시 시도해 주세요.")
                    .mentionRepliedUser(false)
                    .queue({}, {})
            }
        }

        private fun recentChannelContext(event: MessageReceivedEvent): List<CloudTurn> {
            val selfId = event.jda.selfUser.idLong
            val messages = recentMessagesSnapshot(event.channel.idLong)
            return buildDiscordRecentContextTurns(
                messages = messages,
                currentMessageId = event.messageIdLong,
                botUserId = selfId,
            )
        }

        private fun recentParticipationTurns(
            messages: List<DiscordRecentPromptMessage>,
            botUserId: Long,
        ): List<com.discordassistant.central.speech.domain.model.ConversationTurn> =
            messages
                .asSequence()
                .filter { it.content.isNotBlank() }
                .filter { !it.bot || it.authorId == botUserId }
                .sortedBy { it.createdAtEpochMillis }
                .toList()
                .takeLast(RECENT_CHANNEL_CONTEXT_MAX_TURNS)
                .map { message ->
                    com.discordassistant.central.speech.domain.model.ConversationTurn(
                        speakerLabel = if (message.bot && message.authorId == botUserId) "nia" else "user_${message.authorId % 100000}",
                        text = message.content.take(500),
                    )
                }

        private fun recentParticipationRawMessages(
            messages: List<DiscordRecentPromptMessage>,
            botUserId: Long,
        ): List<com.discordassistant.central.platform.discord.nexa.ParticipationRawSceneMessage> =
            messages
                .asSequence()
                .filter { it.content.isNotBlank() }
                .filter { !it.bot || it.authorId == botUserId }
                .sortedBy { it.createdAtEpochMillis }
                .toList()
                .takeLast(RECENT_CHANNEL_CONTEXT_MAX_TURNS)
                .map { message ->
                    com.discordassistant.central.platform.discord.nexa.ParticipationRawSceneMessage(
                        messageId = message.id,
                        authorId = message.authorId,
                        authorLabel = message.authorLabel,
                        bot = message.bot && message.authorId == botUserId,
                        content = message.content,
                        occurredAtMs = message.createdAtEpochMillis,
                        replyToMessageId = message.replyToMessageId,
                    )
                }

        private fun rememberRecentMessage(
            event: MessageReceivedEvent,
            routingId: Long,
        ) {
            val message =
                DiscordRecentPromptMessage(
                    id = event.messageIdLong,
                    authorId = event.author.idLong,
                    authorLabel = event.member?.effectiveName ?: event.author.name,
                    bot = event.author.isBot,
                    content = event.message.contentRaw,
                    createdAtEpochMillis =
                        event.message.timeCreated
                            .toInstant()
                            .toEpochMilli(),
                    replyToMessageId = event.message.referencedMessage?.idLong,
                    hasPdfAttachment =
                        event.message.attachments.any { attachment ->
                            attachment.contentType.equals("application/pdf", ignoreCase = true) ||
                                attachment.fileName.endsWith(".pdf", ignoreCase = true)
                        },
                )
            rememberRecentMessage(routingId, message)
        }

        private fun updateRecentMessage(
            event: MessageUpdateEvent,
            routingId: Long,
        ) {
            val buffer = recentMessagesByChannel[routingId] ?: return
            val message =
                DiscordRecentPromptMessage(
                    id = event.messageIdLong,
                    authorId = event.author.idLong,
                    authorLabel = event.member?.effectiveName ?: event.author.name,
                    bot = event.author.isBot,
                    content = event.message.contentRaw,
                    createdAtEpochMillis =
                        event.message.timeCreated
                            .toInstant()
                            .toEpochMilli(),
                    replyToMessageId = event.message.referencedMessage?.idLong,
                    hasPdfAttachment =
                        event.message.attachments.any { attachment ->
                            attachment.contentType.equals("application/pdf", ignoreCase = true) ||
                                attachment.fileName.endsWith(".pdf", ignoreCase = true)
                        },
                )
            synchronized(buffer) { updateRecentPromptMessage(buffer, message) }
        }

        private fun removeRecentMessage(
            routingId: Long,
            messageId: Long,
        ) {
            val buffer = recentMessagesByChannel[routingId] ?: return
            synchronized(buffer) { removeRecentPromptMessage(buffer, messageId) }
        }

        private fun rememberNiaReply(
            channelId: Long,
            botUserId: Long,
            content: String,
            replyToMessageId: Long,
        ) {
            if (content.isBlank()) return
            val now = System.currentTimeMillis()
            val message =
                DiscordRecentPromptMessage(
                    id = -now,
                    authorId = botUserId,
                    authorLabel = "니아",
                    bot = true,
                    content = content,
                    createdAtEpochMillis = now,
                    replyToMessageId = replyToMessageId,
                )
            rememberRecentMessage(channelId, message)
        }

        private fun rememberRecentMessage(
            channelId: Long,
            message: DiscordRecentPromptMessage,
        ) {
            val buffer = recentMessagesByChannel.computeIfAbsent(channelId) { ArrayDeque() }
            synchronized(buffer) {
                appendRecentPromptMessage(buffer, message, RECENT_CHANNEL_CONTEXT_FETCH_LIMIT)
            }
            if (recentMessagesByChannel.size > RECENT_CHANNEL_CONTEXT_CHANNEL_CACHE_LIMIT) {
                recentMessagesByChannel.keys.firstOrNull { it != channelId }?.let { recentMessagesByChannel.remove(it) }
            }
        }

        private fun recentMessagesSnapshot(channelId: Long): List<DiscordRecentPromptMessage> {
            val buffer = recentMessagesByChannel[channelId] ?: return emptyList()
            return synchronized(buffer) { buffer.toList() }
        }

        private fun niaContinuationPrompt(event: MessageReceivedEvent): String? {
            val selfId = event.jda.selfUser.idLong
            return buildNiaContinuationPromptFromRecentMessages(
                messages = recentMessagesSnapshot(event.channel.idLong),
                currentMessageId = event.messageIdLong,
                botUserId = selfId,
            )
        }

        /** 만족도 리액션 수집(#171): 👍/👎 를 메트릭으로 집계. */
        override fun onMessageReactionAdd(event: MessageReactionAddEvent) {
            if (event.user?.isBot == true) return
            when (event.emoji.formatted) {
                "👍" -> metrics.record("reaction:up")
                "👎" -> metrics.record("reaction:down")
            }
        }

        private fun mentionPrompt(
            raw: String,
            selfId: Long,
        ): String =
            raw
                .replace(Regex("<@!?$selfId>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        /** 길드면 길드 컨텍스트, DM 이면 DM_SCOPE sentinel 컨텍스트(읽기/안내 명령 전용 — 관리자/역할 없음). */
        private fun ctxOf(interaction: Interaction): CommandContext {
            val userLang = I18n.resolveOrNull(interaction.userLocale) // ko/en/ja 또는 null(미지원 → 길드 기본 폴백)
            val guild = interaction.guild
            return if (guild != null) {
                buildCtx(guild.idLong, interaction.member, interaction.channelIdLong, interaction.user.idLong, userLang)
            } else {
                CommandContext(
                    guildId = CommandService.DM_SCOPE,
                    channelId = interaction.channelIdLong,
                    userId = interaction.user.idLong,
                    roleIds = emptySet(),
                    isAdmin = false,
                    userLang = userLang,
                )
            }
        }

        /** 응답 언어 코드(ko/en/ja): 요청자 로케일 우선 → 길드 기본. 어댑터에서 i18n 문구 직접 조회용. */
        private fun langOf(ctx: CommandContext): String = ctx.userLang ?: commands.guildLanguage(ctx)

        private fun buildCtx(
            guildId: Long,
            member: net.dv8tion.jda.api.entities.Member?,
            channelId: Long,
            userId: Long,
            userLang: String? = null,
        ): CommandContext =
            CommandContext(
                guildId = guildId,
                channelId = channelId,
                userId = userId,
                roleIds = member?.roles?.map { it.idLong }?.toSet() ?: emptySet(),
                isAdmin =
                    member?.let {
                        it.hasPermission(Permission.MANAGE_SERVER) || it.hasPermission(Permission.ADMINISTRATOR)
                    } ?: false,
                userLang = userLang,
            )

        private fun dispatch(
            event: SlashCommandInteractionEvent,
            ctx: CommandContext,
        ): Reply =
            when (event.name) {
                "ask" ->
                    commands.ask(
                        ctx,
                        event.getOption("prompt")?.asString.orEmpty(),
                        requestedModel = event.getOption("model")?.asString,
                        requestedResponseMode = event.getOption("mode")?.asString,
                        webSearch = event.getOption("web")?.asBoolean ?: false,
                    )
                "imagine" -> commands.imagine(ctx, event.getOption("prompt")?.asString.orEmpty())
                "my-usage" -> commands.myUsage(ctx)
                "nia" -> commands.niaAffinity(ctx)
                "privacy" -> commands.privacy(ctx)
                "help" -> commands.help(ctx, event.userLocale)
                "provider-join" -> commands.providerJoin(ctx)
                "provider-status" -> commands.providerStatus(ctx)
                else -> Reply("알 수 없는 명령입니다.")
            }
    }
}
