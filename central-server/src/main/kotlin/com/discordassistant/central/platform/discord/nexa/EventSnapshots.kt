package com.discordassistant.central.platform.discord.nexa

import java.time.Instant

/*
 * 매퍼 입력 스냅샷(NEXA-P03). JDA 이벤트에서 추출한 JDA-free 원시 필드 묶음이다.
 *
 * 스냅샷은 어댑터-로컬 타입이라 JDA 참조를 운반하지 않는다 — 매퍼의 toEvent 순수 함수가 이 스냅샷만 보고 도메인
 * 이벤트를 만든다. 그래서 매핑 로직을 JDA mock 없이 단위 테스트할 수 있고(불변식 1·테스트 가능성), 추출 단계만
 * JDA 를 본다. 모든 스냅샷은 receivedAt(수집 시각, 주입된 Clock 에서 추출 시 채움)과 sourceSequence(같은 채널
 * 단조 증가 수신 순번, 어댑터가 부여)를 운반해 toEvent 를 스냅샷만의 순수 함수로 유지한다 — receivedAt/
 * sourceSequence 를 매퍼 내부에서 하드코딩하지 않고 추출 단계 주입값으로 받는다(재생 추적 가능).
 */

/** 모든 매퍼 스냅샷이 공유하는 수집 메타(추출 단계에서 주입된 Clock·시퀀서로 채운다). */
interface IngestSnapshot {
    /** conversation 수집 경계가 이벤트를 받은 시각 — 주입된 Clock 에서 읽은 값(하드코딩 금지). */
    val receivedAt: Instant

    /** 같은 채널 내 단조 증가 수신 순번 — 어댑터가 부여한 결정론적 순서 키. */
    val sourceSequence: Long
}

/** 메시지 본문 텍스트의 추출 상태 — "인텐트 없음" 과 "빈 본문" 과 "사용 가능" 을 명시 구분(단일 null 금지). */
sealed interface ContentSnapshot {
    /** MESSAGE_CONTENT 인텐트가 있고 원문을 읽었다. [text] 가 빈 문자열이면 매퍼가 Empty 로 판정한다. */
    data class Readable(
        val text: String,
    ) : ContentSnapshot

    /** MESSAGE_CONTENT 인텐트가 없어 원문을 **볼 권한이 없다**(관찰 한계, 빈 본문과 다름). */
    data object IntentMissing : ContentSnapshot
}

/**
 * 메시지의 출처 종류 — 봇/웹훅/시스템/사람을 명시 구분(T003 acceptance).
 *
 * 같은 텍스트라도 누가/무엇이 보냈는지에 따라 하류 처리(관찰 가중·페르소나 응답 여부)가 달라진다. 단일 boolean
 * (isBot) 로 뭉개지 않고 종류를 enum 으로 보존한다.
 */
enum class MessageSourceType {
    /** 사람(일반 유저) 메시지. */
    HUMAN,

    /** 봇 메시지(application bot). */
    BOT,

    /** 웹훅 메시지(webhook 으로 게시). */
    WEBHOOK,

    /** 시스템 메시지(가입/핀/스레드 생성 등 Discord 시스템 발행). */
    SYSTEM,
}

/** 첨부 metadata 스냅샷(원문 바이트 아님 — 참조/메타만). */
data class AttachmentSnapshot(
    val attachmentId: Long,
    val fileName: String?,
    val contentType: String?,
    val sizeBytes: Long?,
)

/** MessageCreated 매퍼 입력 스냅샷(T003). */
data class MessageCreatedSnapshot(
    val guildId: Long,
    val channelId: Long,
    val messageId: Long,
    val authorId: Long,
    val sourceType: MessageSourceType,
    val content: ContentSnapshot,
    val occurredAt: Instant,
    override val receivedAt: Instant,
    override val sourceSequence: Long,
    val replyToMessageId: Long?,
    val mentionedUserIds: Set<Long>,
    val attachments: List<AttachmentSnapshot>,
    /** 스레드 안의 메시지면 스레드 채널 id, 채널 직속이면 null. */
    val threadId: Long?,
) : IngestSnapshot

/** MessageUpdated 매퍼 입력 스냅샷(T004). */
data class MessageUpdatedSnapshot(
    val guildId: Long,
    val channelId: Long,
    val messageId: Long,
    /** 동일 메시지의 편집 순번(단조 증가). 캐시 미스 등으로 모르면 0(최소 키). */
    val revision: Long,
    val content: ContentSnapshot,
    val occurredAt: Instant,
    override val receivedAt: Instant,
    override val sourceSequence: Long,
) : IngestSnapshot

/**
 * MessageDeleted 매퍼 입력 스냅샷(T004). Discord MESSAGE_DELETE 는 원문/작성자/시각을 싣지 않으므로 최소 키만
 * 운반한다 — 캐시 미스에서도 이 스냅샷으로 이벤트를 만들 수 있다.
 */
data class MessageDeletedSnapshot(
    val guildId: Long,
    val channelId: Long,
    val messageId: Long,
    /** 수신 시각(이벤트가 원천 시각을 안 주므로 어댑터 수신 시각으로 채운다). */
    val occurredAt: Instant,
    override val receivedAt: Instant,
    override val sourceSequence: Long,
) : IngestSnapshot

/** 리액션 추가/삭제 방향(스냅샷). */
enum class ReactionChangeSnapshot {
    ADDED,
    REMOVED,
}

/** 이모지 정체성 스냅샷(unicode/custom 구분). */
sealed interface EmojiSnapshot {
    data class Unicode(
        val codepoints: String,
    ) : EmojiSnapshot

    data class Custom(
        val customEmojiId: Long,
        val name: String?,
    ) : EmojiSnapshot
}

/** Reaction 매퍼 입력 스냅샷(T005). */
data class ReactionSnapshot(
    val guildId: Long,
    val channelId: Long,
    val messageId: Long,
    val actorId: Long,
    val emoji: EmojiSnapshot,
    val change: ReactionChangeSnapshot,
    val occurredAt: Instant,
    override val receivedAt: Instant,
    override val sourceSequence: Long,
    /** 버스트(짧은 간격 다수)면 true. 어댑터가 시간창 판정해 채운다(모르면 false=단발). */
    val burst: Boolean,
) : IngestSnapshot

/** TypingStarted 매퍼 입력 스냅샷(T005). */
data class TypingSnapshot(
    val guildId: Long,
    val channelId: Long,
    val actorId: Long,
    val startedAt: Instant,
    val expiresAt: Instant,
    override val receivedAt: Instant,
    override val sourceSequence: Long,
) : IngestSnapshot

/**
 * 표시 정체성 한 항목의 변경 가용 상태(T005). "변경 없음" 과 "변경됨(old→new)" 과 "이전 값 모름" 을 명시 구분한다 —
 * 미지원/미관측 필드를 단일 null 로 뭉개지 않는다.
 */
sealed interface IdentityFieldSnapshot {
    /** 이 필드는 이번 이벤트에서 바뀌지 않았다(관측됐고 변경 없음). */
    data object Unchanged : IdentityFieldSnapshot

    /** 이 필드가 바뀌었다. [old] 를 모르면 null(이전 값 unavailable), [new] 가 null 이면 설정 해제. */
    data class Changed(
        val old: String?,
        val new: String?,
    ) : IdentityFieldSnapshot
}

/** MemberIdentityChanged 매퍼 입력 스냅샷(T005). */
data class MemberIdentitySnapshot(
    val guildId: Long,
    val channelId: Long,
    val actorId: Long,
    val occurredAt: Instant,
    override val receivedAt: Instant,
    override val sourceSequence: Long,
    val nickname: IdentityFieldSnapshot,
    val displayName: IdentityFieldSnapshot,
) : IngestSnapshot
