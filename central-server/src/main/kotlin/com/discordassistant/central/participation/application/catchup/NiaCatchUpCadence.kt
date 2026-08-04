package com.discordassistant.central.participation.application.catchup

import com.discordassistant.central.participation.application.port.out.NiaCatchUpStateStorePort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Judge 호출 주기를 조절하는 채널 단위 상태의 식별자다. */
data class NiaCatchUpScope(
    val guildId: Long,
    val channelId: Long,
    val threadId: Long? = null,
) {
    init {
        require(guildId > 0) { "guildId 는 양수여야 한다: $guildId" }
        require(channelId > 0) { "channelId 는 양수여야 한다: $channelId" }
        threadId?.let { require(it > 0) { "threadId 는 양수여야 한다: $it" } }
    }
}

/** CATCH_UP 재판단에 필요한 마지막 사람 메시지의 비원문 메타데이터다. */
data class NiaCatchUpMessage(
    val scope: NiaCatchUpScope,
    val messageId: Long,
    val userId: Long,
    val replyToMessageId: Long?,
    val occurredAt: Instant,
    val mentioned: Boolean,
    val replyToNia: Boolean,
) {
    init {
        require(messageId > 0) { "messageId 는 양수여야 한다: $messageId" }
        require(userId > 0) { "userId 는 양수여야 한다: $userId" }
        replyToMessageId?.let { require(it > 0) { "replyToMessageId 는 양수여야 한다: $it" } }
    }

    val explicitlyAddressed: Boolean get() = mentioned || replyToNia
}

enum class NiaJudgeCadenceMode {
    ACTIVE,
    CATCH_UP,
}

/** Discord 수신 경로가 현재 메시지를 어떻게 처리할지 나타낸다. */
enum class NiaCatchUpAdmission {
    /** 기존 ACTIVE 흐름(짧은 turn-boundary debounce 포함)으로 즉시 판단한다. */
    EVALUATE_NOW,

    /** CATCH_UP 큐에 원문만 누적하고 due tick에서 같은 Judge를 한 번 호출한다. */
    DEFERRED,

    /** 명시 호출이 CATCH_UP을 깨웠으므로 debounce 없이 즉시 판단한다. */
    WAKE_NOW,
}

/** 실제 Judge 결과가 cadence 상태를 어떻게 바꿀지 나타낸다. */
enum class NiaCatchUpJudgeResult {
    IGNORE,
    NON_IGNORE,
    UNPROCESSED,
}

/** participation 채널의 활성 상태가 사라질 때 남은 cadence 상태를 정리하는 경계다. */
interface NiaCatchUpStateLifecycle {
    fun clearChannel(
        guildId: Long,
        channelId: Long,
    )

    fun clearGuild(guildId: Long)

    data object Noop : NiaCatchUpStateLifecycle {
        override fun clearChannel(
            guildId: Long,
            channelId: Long,
        ) = Unit

        override fun clearGuild(guildId: Long) = Unit
    }
}

data class NiaCatchUpState(
    val id: Long? = null,
    val scope: NiaCatchUpScope,
    val mode: NiaJudgeCadenceMode = NiaJudgeCadenceMode.ACTIVE,
    val consecutiveIgnoreCount: Int = 0,
    val retryCount: Int = 0,
    val lastJudgedMessageId: Long = 0,
    val latestMessage: NiaCatchUpMessage? = null,
    val nextCatchUpAt: Instant? = null,
    val leaseOwner: String? = null,
    val leaseToken: String? = null,
    val leaseExpiresAt: Instant? = null,
) {
    init {
        require(consecutiveIgnoreCount >= 0) { "consecutiveIgnoreCount 는 음수일 수 없다" }
        require(retryCount >= 0) { "retryCount 는 음수일 수 없다" }
        require(lastJudgedMessageId >= 0) { "lastJudgedMessageId 는 음수일 수 없다" }
        latestMessage?.let { require(it.scope == scope) { "latestMessage scope 가 일치해야 한다" } }
        if (mode == NiaJudgeCadenceMode.CATCH_UP) {
            require(nextCatchUpAt != null) { "CATCH_UP 상태에는 nextCatchUpAt 이 필요하다" }
        }
        val hasLease = leaseOwner != null || leaseToken != null || leaseExpiresAt != null
        if (hasLease) {
            require(!leaseOwner.isNullOrBlank()) { "leaseOwner 는 lease와 함께 비어 있을 수 없다" }
            require(!leaseToken.isNullOrBlank()) { "leaseToken 은 lease와 함께 비어 있을 수 없다" }
            require(leaseExpiresAt != null) { "leaseExpiresAt 은 lease와 함께 필요하다" }
        }
    }
}

/** DB lease를 보유한 CATCH_UP 재판단 작업이다. target이 없으면 안전하게 폐기한다. */
data class NiaCatchUpClaim(
    val stateId: Long,
    val scope: NiaCatchUpScope,
    val target: NiaCatchUpMessage?,
    val leaseOwner: String,
    val leaseToken: String,
) {
    init {
        require(stateId > 0) { "stateId 는 양수여야 한다: $stateId" }
        require(leaseOwner.isNotBlank()) { "leaseOwner 는 비어 있을 수 없다" }
        require(leaseToken.isNotBlank()) { "leaseToken 은 비어 있을 수 없다" }
    }
}

private data class NiaCatchUpSettings(
    val enabled: Boolean,
    val consecutiveIgnoreThreshold: Int,
    val interval: Duration,
    val leaseDuration: Duration,
    val retryDelay: Duration,
    val maxRetryCount: Int,
    val workerId: String,
) {
    init {
        require(consecutiveIgnoreThreshold > 0) { "consecutiveIgnoreThreshold 는 양수여야 한다" }
        require(!interval.isZero && !interval.isNegative) { "interval 은 양수여야 한다" }
        require(!leaseDuration.isZero && !leaseDuration.isNegative) { "leaseDuration 은 양수여야 한다" }
        require(!retryDelay.isZero && !retryDelay.isNegative) { "retryDelay 는 양수여야 한다" }
        require(maxRetryCount >= 0) { "maxRetryCount 는 음수일 수 없다" }
        require(workerId.isNotBlank()) { "workerId 는 비어 있을 수 없다" }
    }
}

/**
 * 동일한 Final Judge의 호출 시점만 조절한다. 별도 판별 모델이나 별도 LLM 호출은 만들지 않는다.
 *
 * ACTIVE에서는 기존 메시지 turn을 즉시 Judge에 보내고, 연속 IGNORE가 기준을 넘으면 CATCH_UP으로 전환한다.
 * CATCH_UP에서는 마지막 원문 위치만 갱신하고 주기 작업이 한 번의 Judge로 누적 장면을 확인한다.
 */
@Service
class NiaCatchUpCadence(
    private val states: NiaCatchUpStateStorePort,
    @Value("\${central.nexa.participation.catch-up.enabled:false}") enabled: Boolean = false,
    @Value("\${central.nexa.participation.catch-up.consecutive-ignore-threshold:10}")
    consecutiveIgnoreThreshold: Int = 10,
    @Value("\${central.nexa.participation.catch-up.interval-millis:300000}") intervalMillis: Long = 300_000,
    @Value("\${central.nexa.participation.catch-up.lease-millis:30000}") leaseMillis: Long = 30_000,
    @Value("\${central.nexa.participation.catch-up.retry-millis:10000}") retryMillis: Long = 10_000,
    @Value("\${central.nexa.participation.catch-up.max-retry-count:3}") maxRetryCount: Int = 3,
    @Value("\${central.nexa.participation.catch-up.worker-id:central}") workerId: String = "central",
    private val clock: Clock = Clock.systemUTC(),
) : NiaCatchUpStateLifecycle {
    private val settings =
        NiaCatchUpSettings(
            enabled = enabled,
            consecutiveIgnoreThreshold = consecutiveIgnoreThreshold,
            interval = Duration.ofMillis(intervalMillis),
            leaseDuration = Duration.ofMillis(leaseMillis),
            retryDelay = Duration.ofMillis(retryMillis),
            maxRetryCount = maxRetryCount,
            workerId = workerId,
        )
    private val log = LoggerFactory.getLogger(NiaCatchUpCadence::class.java)

    /** 새 메시지를 ACTIVE Judge로 보낼지, CATCH_UP에 누적할지 원자적으로 결정한다. */
    @Transactional
    fun admit(message: NiaCatchUpMessage): NiaCatchUpAdmission {
        if (!settings.enabled) return NiaCatchUpAdmission.EVALUATE_NOW
        val existing = states.lock(message.scope) ?: return NiaCatchUpAdmission.EVALUATE_NOW
        if (existing.mode != NiaJudgeCadenceMode.CATCH_UP) return NiaCatchUpAdmission.EVALUATE_NOW

        val latest =
            existing.latestMessage
                ?.takeIf { it.messageId > message.messageId }
                ?: message

        return if (message.explicitlyAddressed) {
            states.save(
                existing.copy(
                    mode = NiaJudgeCadenceMode.ACTIVE,
                    consecutiveIgnoreCount = 0,
                    retryCount = 0,
                    latestMessage = latest,
                    nextCatchUpAt = null,
                    leaseOwner = null,
                    leaseToken = null,
                    leaseExpiresAt = null,
                ),
            )
            NiaCatchUpAdmission.WAKE_NOW
        } else {
            states.save(
                existing.copy(
                    latestMessage = latest,
                    retryCount = if (latest.messageId > (existing.latestMessage?.messageId ?: 0)) 0 else existing.retryCount,
                ),
            )
            NiaCatchUpAdmission.DEFERRED
        }
    }

    /** ACTIVE Judge가 실제로 처리한 결과만 연속 IGNORE 카운터에 반영한다. */
    @Transactional
    fun recordEvaluation(
        message: NiaCatchUpMessage,
        result: NiaCatchUpJudgeResult,
    ) {
        if (!settings.enabled || result == NiaCatchUpJudgeResult.UNPROCESSED) return
        val existing = states.lock(message.scope)
        if (existing?.latestMessage?.messageId?.let { it > message.messageId } == true) return

        val base =
            existing ?: NiaCatchUpState(
                scope = message.scope,
                latestMessage = message,
            )
        val lastJudged = maxOf(base.lastJudgedMessageId, message.messageId)
        val next =
            when (result) {
                NiaCatchUpJudgeResult.IGNORE -> {
                    val count = base.consecutiveIgnoreCount + 1
                    if (count >= settings.consecutiveIgnoreThreshold) {
                        base.copy(
                            mode = NiaJudgeCadenceMode.CATCH_UP,
                            consecutiveIgnoreCount = count,
                            retryCount = 0,
                            lastJudgedMessageId = lastJudged,
                            latestMessage = message,
                            nextCatchUpAt = clock.instant().plus(settings.interval),
                            leaseOwner = null,
                            leaseToken = null,
                            leaseExpiresAt = null,
                        )
                    } else {
                        base.copy(
                            mode = NiaJudgeCadenceMode.ACTIVE,
                            consecutiveIgnoreCount = count,
                            retryCount = 0,
                            lastJudgedMessageId = lastJudged,
                            latestMessage = message,
                            nextCatchUpAt = null,
                            leaseOwner = null,
                            leaseToken = null,
                            leaseExpiresAt = null,
                        )
                    }
                }
                NiaCatchUpJudgeResult.NON_IGNORE ->
                    base.copy(
                        mode = NiaJudgeCadenceMode.ACTIVE,
                        consecutiveIgnoreCount = 0,
                        retryCount = 0,
                        lastJudgedMessageId = lastJudged,
                        latestMessage = message,
                        nextCatchUpAt = null,
                        leaseOwner = null,
                        leaseToken = null,
                        leaseExpiresAt = null,
                    )
                NiaCatchUpJudgeResult.UNPROCESSED -> return
            }
        states.save(next)
    }

    /** due CATCH_UP 행 하나만 lease와 함께 가져온다. 긴 Judge 처리 중 미시작 batch의 lease가 만료되지 않게 한다. */
    @Transactional
    fun claimDue(): List<NiaCatchUpClaim> {
        if (!settings.enabled) return emptyList()
        val now = clock.instant()
        return states.claimDue(
            now = now,
            leaseOwner = settings.workerId,
            leaseExpiresAt = now.plus(settings.leaseDuration),
            limit = 1,
        )
    }

    /** CATCH_UP Judge 완료 뒤 상태를 전이한다. */
    @Transactional
    fun complete(
        claim: NiaCatchUpClaim,
        result: NiaCatchUpJudgeResult,
    ): Boolean {
        if (!settings.enabled) return false
        val existing = states.lockClaim(claim) ?: return false
        val targetId = claim.target?.messageId ?: existing.latestMessage?.messageId ?: existing.lastJudgedMessageId
        val lastJudged = maxOf(existing.lastJudgedMessageId, targetId)
        val now = clock.instant()
        val next =
            when (result) {
                NiaCatchUpJudgeResult.IGNORE ->
                    existing.copy(
                        mode = NiaJudgeCadenceMode.CATCH_UP,
                        retryCount = 0,
                        lastJudgedMessageId = lastJudged,
                        nextCatchUpAt = now.plus(settings.interval),
                        leaseOwner = null,
                        leaseToken = null,
                        leaseExpiresAt = null,
                    )
                NiaCatchUpJudgeResult.NON_IGNORE ->
                    existing.copy(
                        mode = NiaJudgeCadenceMode.ACTIVE,
                        consecutiveIgnoreCount = 0,
                        retryCount = 0,
                        lastJudgedMessageId = lastJudged,
                        nextCatchUpAt = null,
                        leaseOwner = null,
                        leaseToken = null,
                        leaseExpiresAt = null,
                    )
                NiaCatchUpJudgeResult.UNPROCESSED -> {
                    if (existing.retryCount >= settings.maxRetryCount) {
                        log.warn(
                            "NIA CATCH_UP 재시도 한도 초과(state={}, channel={}) — ACTIVE로 복귀",
                            existing.id,
                            existing.scope.channelId,
                        )
                        existing.copy(
                            mode = NiaJudgeCadenceMode.ACTIVE,
                            consecutiveIgnoreCount = 0,
                            retryCount = 0,
                            lastJudgedMessageId = lastJudged,
                            nextCatchUpAt = null,
                            leaseOwner = null,
                            leaseToken = null,
                            leaseExpiresAt = null,
                        )
                    } else {
                        existing.copy(
                            mode = NiaJudgeCadenceMode.CATCH_UP,
                            retryCount = existing.retryCount + 1,
                            nextCatchUpAt = now.plus(settings.retryDelay),
                            leaseOwner = null,
                            leaseToken = null,
                            leaseExpiresAt = null,
                        )
                    }
                }
            }
        states.save(next)
        return true
    }

    /** 편집·삭제처럼 원문 장면이 바뀌면 남은 CATCH_UP 커서를 버리고 ACTIVE부터 다시 시작한다. */
    @Transactional
    fun clearScope(scope: NiaCatchUpScope) {
        states.deleteScope(scope)
    }

    @Transactional
    override fun clearChannel(
        guildId: Long,
        channelId: Long,
    ) {
        states.deleteChannel(guildId, channelId)
    }

    @Transactional
    override fun clearGuild(guildId: Long) {
        states.deleteGuild(guildId)
    }
}
