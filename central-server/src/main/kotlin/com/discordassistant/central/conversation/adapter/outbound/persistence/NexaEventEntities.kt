package com.discordassistant.central.conversation.adapter.outbound.persistence

import com.discordassistant.central.global.crypto.EncryptedStringConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * conversation 정규화 이벤트 저장소 JPA 엔티티(NEXA-P03-T009). domain model([NormalizedDiscordEvent])과 **분리**된
 * 영속 표현이다 — 도메인은 순수(Spring/JPA 미참조)를 유지하고, 어댑터가 이 엔티티로 매핑한다.
 *
 * **content payload / 검색 메타 분리(acceptance T009)**: 검색 가능한 최소 메타데이터(채널/순서/시각/PII 등급/계보)와
 * 암호화 content payload([contentCipher])를 분리 보관한다. 원문(High)은 data-categories.md 불변식 1 에 따라 기본
 * 비영속이라 [contentCipher] 는 기본 null(미저장)이며, 앱레벨 암호화(ADR 0012)된 참조가 있을 때만 enc1: ciphertext 가
 * 들어간다([EncryptedStringConverter] 가 at-rest 암호/복호).
 *
 * **원문 toString 금지(acceptance T009)**: 이 엔티티는 data class 가 아니며 [toString] 을 메타데이터(redaction 여부)만
 * 노출하도록 오버라이드한다 — content cipher·식별자 원문이 로그/직렬화로 새지 않는다(logging-boundary.md).
 */
@Entity
@Table(name = "nexa_event_store")
class NexaEventEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "event_id") var eventId: String = "",
    @Column(name = "event_type") var eventType: String = "",
    @Column(name = "guild_id") var guildId: Long = 0,
    @Column(name = "channel_id") var channelId: Long = 0,
    @Column(name = "source_sequence") var sourceSequence: Long = 0,
    @Column(name = "occurred_at") var occurredAt: Instant = Instant.EPOCH,
    @Column(name = "received_at") var receivedAt: Instant = Instant.EPOCH,
    @Column(name = "privacy_class") var privacyClass: String = "",
    @Column(name = "source_event_id") var sourceEventId: String? = null,
    /** 앱레벨 암호화된 content 참조(기본 null = 원문 미저장). 평문으로 절대 노출하지 않는다. */
    @Convert(converter = EncryptedStringConverter::class)
    @Column(name = "content_cipher")
    var contentCipher: String? = null,
    @Column(name = "redacted") var redacted: Boolean = false,
    @Column(name = "redacted_at") var redactedAt: Instant? = null,
) {
    /** 원문·암호문을 노출하지 않는 toString — 메타데이터(키·순서·redaction 상태)만 남긴다. */
    override fun toString(): String =
        "NexaEventEntity(eventId=$eventId, channelId=$channelId, sourceSequence=$sourceSequence, redacted=$redacted)"
}

interface NexaEventRepository : JpaRepository<NexaEventEntity, Long> {
    fun findByEventId(eventId: String): NexaEventEntity?

    fun existsByEventId(eventId: String): Boolean

    fun findByChannelIdOrderBySourceSequenceAscOccurredAtAsc(channelId: Long): List<NexaEventEntity>

    fun findByReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderBySourceSequenceAscOccurredAtAsc(
        from: Instant,
        to: Instant,
    ): List<NexaEventEntity>

    // ── replay(T019): guild(+optional channel) + occurred 시각 범위, 채널 순서 → 시각 결정론적 정렬 ──
    fun findByGuildIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByChannelIdAscSourceSequenceAscOccurredAtAsc(
        guildId: Long,
        from: Instant,
        to: Instant,
    ): List<NexaEventEntity>

    fun findByGuildIdAndChannelIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderBySourceSequenceAscOccurredAtAsc(
        guildId: Long,
        channelId: Long,
        from: Instant,
        to: Instant,
    ): List<NexaEventEntity>
}

/**
 * conversation transactional outbox JPA 엔티티(NEXA-P03-T012). 저장된 이벤트를 projection worker 로 전달하기 위한
 * 발행 레코드다. 이벤트 append 와 같은 트랜잭션에서 기록돼 둘 다 커밋/롤백된다(원자성, T011).
 *
 * [eventId] 유니크(스키마 제약)라 같은 이벤트의 outbox 가 한 행만 생긴다 — 재시도가 중복 side effect 를 만들지
 * 않는다(멱등, acceptance T012). 원문/식별자 원문을 담지 않는다(전달 키와 상태만).
 */
@Entity
@Table(name = "nexa_conversation_outbox")
class NexaConversationOutboxEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "event_id") var eventId: String = "",
    @Column(name = "channel_id") var channelId: Long = 0,
    @Column(name = "status") var status: String = OutboxStatus.PENDING.name,
    @Column(name = "attempts") var attempts: Int = 0,
    @Column(name = "created_at") var createdAt: Instant = Instant.EPOCH,
    @Column(name = "published_at") var publishedAt: Instant? = null,
)

/** outbox 레코드 전달 상태(PENDING→PUBLISHED). publisher 가 PENDING 만 집어 전달 후 PUBLISHED 로 전이. */
enum class OutboxStatus {
    PENDING,
    PUBLISHED,
}

interface NexaConversationOutboxRepository : JpaRepository<NexaConversationOutboxEntity, Long> {
    fun existsByEventId(eventId: String): Boolean

    fun findByStatusOrderByIdAsc(status: String): List<NexaConversationOutboxEntity>
}
