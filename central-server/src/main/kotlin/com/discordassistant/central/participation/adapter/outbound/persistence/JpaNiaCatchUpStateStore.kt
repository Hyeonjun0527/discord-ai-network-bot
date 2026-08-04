package com.discordassistant.central.participation.adapter.outbound.persistence

import com.discordassistant.central.global.crypto.FieldCrypto
import com.discordassistant.central.participation.application.catchup.NiaCatchUpClaim
import com.discordassistant.central.participation.application.catchup.NiaCatchUpMessage
import com.discordassistant.central.participation.application.catchup.NiaCatchUpScope
import com.discordassistant.central.participation.application.catchup.NiaCatchUpState
import com.discordassistant.central.participation.application.catchup.NiaJudgeCadenceMode
import com.discordassistant.central.participation.application.port.out.NiaCatchUpStateStorePort
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** CATCH_UP 상태를 영속하고 due 행을 lease와 함께 선점한다. 원문은 이 테이블에 저장하지 않는다. */
@Repository
class JpaNiaCatchUpStateStore(
    private val rows: NiaCatchUpStateRepository,
    private val clock: Clock = Clock.systemUTC(),
) : NiaCatchUpStateStorePort {
    @Transactional
    override fun lock(scope: NiaCatchUpScope): NiaCatchUpState? =
        rows
            .lockByScope(
                guildId = scope.guildId,
                channelId = scope.channelId,
                threadId = scope.threadId ?: 0,
            )?.toDomain()

    @Transactional
    override fun lockClaim(claim: NiaCatchUpClaim): NiaCatchUpState? {
        val row = rows.lockById(claim.stateId) ?: return null
        if (row.leaseOwner != claim.leaseOwner || row.leaseToken != claim.leaseToken) return null
        return row.toDomain()
    }

    @Transactional
    override fun save(state: NiaCatchUpState): NiaCatchUpState {
        val now = clock.instant()
        val row =
            state.id
                ?.let { rows.findById(it).orElseThrow { IllegalStateException("CATCH_UP 상태를 찾을 수 없다: $it") } }
                ?.also { it.apply(state, now) }
                ?: state.toEntity(now)
        return rows.save(row).toDomain()
    }

    @Transactional
    override fun claimDue(
        now: Instant,
        leaseOwner: String,
        leaseExpiresAt: Instant,
        limit: Int,
    ): List<NiaCatchUpClaim> {
        require(leaseOwner.isNotBlank()) { "leaseOwner 는 비어 있을 수 없다" }
        require(leaseExpiresAt.isAfter(now)) { "leaseExpiresAt 은 now 이후여야 한다" }
        require(limit > 0) { "limit 은 양수여야 한다" }
        return rows.lockDue(now, limit).map { row ->
            row.leaseOwner = leaseOwner
            row.leaseToken = UUID.randomUUID().toString()
            row.leaseExpiresAt = leaseExpiresAt
            row.updatedAt = now
            NiaCatchUpClaim(
                stateId = row.id,
                scope = row.scope(),
                target = row.latestMessage(),
                leaseOwner = leaseOwner,
                leaseToken = checkNotNull(row.leaseToken),
            )
        }
    }

    @Transactional
    override fun deleteScope(scope: NiaCatchUpScope) {
        rows.deleteByGuildIdAndChannelIdAndThreadId(scope.guildId, scope.channelId, scope.threadId ?: 0)
    }

    @Transactional
    override fun deleteChannel(
        guildId: Long,
        channelId: Long,
    ) {
        rows.deleteByGuildIdAndChannelId(guildId, channelId)
    }

    @Transactional
    override fun deleteGuild(guildId: Long) {
        rows.deleteByGuildId(guildId)
    }
}

@Entity
@Table(name = "nexa_channel_judge_state")
class NiaCatchUpStateEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
    @Column(name = "guild_id") var guildId: Long = 0,
    @Column(name = "channel_id") var channelId: Long = 0,
    @Column(name = "thread_id") var threadId: Long = 0,
    @Column(name = "mode") var mode: String = NiaJudgeCadenceMode.ACTIVE.name,
    @Column(name = "consecutive_ignore_count") var consecutiveIgnoreCount: Int = 0,
    @Column(name = "retry_count") var retryCount: Int = 0,
    @Column(name = "last_judged_message_id") var lastJudgedMessageId: Long = 0,
    @Column(name = "latest_message_id") var latestMessageId: Long? = null,
    /** Discord user snowflake는 routing metadata와 같은 field encryption으로 보관한다. */
    @Column(name = "latest_user_id_cipher") var latestUserIdCipher: String? = null,
    @Column(name = "latest_reply_to_message_id") var latestReplyToMessageId: Long? = null,
    @Column(name = "latest_occurred_at") var latestOccurredAt: Instant? = null,
    @Column(name = "latest_mentioned") var latestMentioned: Boolean = false,
    @Column(name = "latest_reply_to_nia") var latestReplyToNia: Boolean = false,
    @Column(name = "next_catch_up_at") var nextCatchUpAt: Instant? = null,
    @Column(name = "lease_owner") var leaseOwner: String? = null,
    @Column(name = "lease_token") var leaseToken: String? = null,
    @Column(name = "lease_expires_at") var leaseExpiresAt: Instant? = null,
    @Version @Column(name = "version") var version: Long = 0,
    @Column(name = "created_at") var createdAt: Instant = Instant.EPOCH,
    @Column(name = "updated_at") var updatedAt: Instant = Instant.EPOCH,
) {
    override fun toString(): String =
        "NiaCatchUpStateEntity(scope=$guildId:$channelId:$threadId, mode=$mode, latestMessageId=$latestMessageId)"
}

interface NiaCatchUpStateRepository : JpaRepository<NiaCatchUpStateEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "SELECT e FROM NiaCatchUpStateEntity e " +
            "WHERE e.guildId = :guildId AND e.channelId = :channelId AND e.threadId = :threadId",
    )
    fun lockByScope(
        @Param("guildId") guildId: Long,
        @Param("channelId") channelId: Long,
        @Param("threadId") threadId: Long,
    ): NiaCatchUpStateEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM NiaCatchUpStateEntity e WHERE e.id = :id")
    fun lockById(
        @Param("id") id: Long,
    ): NiaCatchUpStateEntity?

    @Query(
        value =
            "SELECT * FROM nexa_channel_judge_state " +
                "WHERE mode = 'CATCH_UP' " +
                "AND next_catch_up_at <= :now " +
                "AND latest_message_id IS NOT NULL " +
                "AND latest_message_id > last_judged_message_id " +
                "AND (lease_expires_at IS NULL OR lease_expires_at < :now) " +
                "ORDER BY next_catch_up_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
        nativeQuery = true,
    )
    fun lockDue(
        @Param("now") now: Instant,
        @Param("limit") limit: Int,
    ): List<NiaCatchUpStateEntity>

    fun deleteByGuildIdAndChannelIdAndThreadId(
        guildId: Long,
        channelId: Long,
        threadId: Long,
    ): Long

    fun deleteByGuildIdAndChannelId(
        guildId: Long,
        channelId: Long,
    ): Long

    fun deleteByGuildId(guildId: Long): Long
}

private fun NiaCatchUpState.toEntity(now: Instant): NiaCatchUpStateEntity =
    NiaCatchUpStateEntity(
        guildId = scope.guildId,
        channelId = scope.channelId,
        threadId = scope.threadId ?: 0,
        createdAt = now,
    ).also { it.apply(this, now) }

private fun NiaCatchUpStateEntity.apply(
    state: NiaCatchUpState,
    now: Instant,
) {
    guildId = state.scope.guildId
    channelId = state.scope.channelId
    threadId = state.scope.threadId ?: 0
    mode = state.mode.name
    consecutiveIgnoreCount = state.consecutiveIgnoreCount
    retryCount = state.retryCount
    lastJudgedMessageId = state.lastJudgedMessageId
    latestMessageId = state.latestMessage?.messageId
    latestUserIdCipher = state.latestMessage?.userId?.let(::encryptUserId)
    latestReplyToMessageId = state.latestMessage?.replyToMessageId
    latestOccurredAt = state.latestMessage?.occurredAt
    latestMentioned = state.latestMessage?.mentioned ?: false
    latestReplyToNia = state.latestMessage?.replyToNia ?: false
    nextCatchUpAt = state.nextCatchUpAt
    leaseOwner = state.leaseOwner
    leaseToken = state.leaseToken
    leaseExpiresAt = state.leaseExpiresAt
    updatedAt = now
}

private fun NiaCatchUpStateEntity.toDomain(): NiaCatchUpState =
    NiaCatchUpState(
        id = id,
        scope = scope(),
        mode = NiaJudgeCadenceMode.valueOf(mode),
        consecutiveIgnoreCount = consecutiveIgnoreCount,
        retryCount = retryCount,
        lastJudgedMessageId = lastJudgedMessageId,
        latestMessage = latestMessage(),
        nextCatchUpAt = nextCatchUpAt,
        leaseOwner = leaseOwner,
        leaseToken = leaseToken,
        leaseExpiresAt = leaseExpiresAt,
    )

private fun NiaCatchUpStateEntity.scope(): NiaCatchUpScope =
    NiaCatchUpScope(
        guildId = guildId,
        channelId = channelId,
        threadId = threadId.takeIf { it != 0L },
    )

private fun NiaCatchUpStateEntity.latestMessage(): NiaCatchUpMessage? {
    val messageId = latestMessageId ?: return null
    val userId = latestUserIdCipher?.let(FieldCrypto::decryptOrNull)?.toLongOrNull() ?: return null
    val occurredAt = latestOccurredAt ?: return null
    return NiaCatchUpMessage(
        scope = scope(),
        messageId = messageId,
        userId = userId,
        replyToMessageId = latestReplyToMessageId,
        occurredAt = occurredAt,
        mentioned = latestMentioned,
        replyToNia = latestReplyToNia,
    )
}

private fun encryptUserId(userId: Long): String {
    require(FieldCrypto.isConfigured()) { "CATCH_UP routing metadata encryption key is not configured" }
    return checkNotNull(FieldCrypto.encrypt(userId.toString()))
}
