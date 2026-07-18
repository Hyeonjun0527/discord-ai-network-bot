package com.discordassistant.central.actionruntime.domain.model

/**
 * 예약 행동의 전송 대상(NEXA-P13-T001, 순수 도메인 value object·불변).
 *
 * 어느 길드/채널/thread 로 행동을 보낼지의 **식별 참조만** 담는다 — 원문 메시지/스레드 제목 같은 content 는
 * 담지 않는다(boundary: 원문 비저장, T003 acceptance 와 일관). 취소 정책(T012~T014)이 "같은 thread 에 인간이
 * 답했는가", "focus thread 가 바뀌었는가" 를 판단할 때 이 식별자로 장면 evidence 와 대조한다.
 *
 * 순수성: Spring/JPA/JDA 미참조.
 */
data class ActionTarget(
    /** 대상 길드 가명(원본 snowflake 아님 — 가명 토큰). */
    val guildPseudonym: String,
    /** 대상 채널 가명. 취소·quota·장면 대조용이며 Discord snowflake 원문이 아니다. */
    val channelId: String,
    /** 대상 thread/focus 식별자(주제 전환·focus 변경 취소 판단의 키 — T013). */
    val threadId: String,
    /**
     * 이 예약 행동의 동의 주체/대상 사용자 가명. null 은 legacy/비사용자 범위 action 이다.
     * user opt-out/동의 철회는 이 값을 가진 새 예약만 사용자 단위로 즉시 제거한다.
     */
    val subjectPseudonym: String? = null,
    /** 첫 SPEAK 버블이 답장할 Discord 메시지 snowflake. thread/focus 식별자와 의미가 다르므로 별도로 보관한다. */
    val replyToMessageId: String? = null,
    /** REACT 또는 reply가 대상으로 삼는 Discord 메시지 식별자. 영속 시 반드시 암호화한다. */
    val targetMessageId: String? = null,
    /** 실제 Discord 전송에 필요한 원시 guild/channel/user 라우팅 값. 영속 어댑터는 필드 암호화를 강제한다. */
    val routingGuildId: String? = null,
    val routingChannelId: String? = null,
    val routingUserId: String? = null,
    /** 예약 당시 영속 conversation projection 버전. 재시작 후에도 stale 행동을 판별하는 비민감 보조값이다. */
    val sceneContextVersion: Long? = null,
) {
    init {
        require(guildPseudonym.isNotBlank()) { "guildPseudonym 은 비어 있을 수 없다" }
        require(channelId.isNotBlank()) { "channelId 는 비어 있을 수 없다" }
        require(threadId.isNotBlank()) { "threadId 는 비어 있을 수 없다" }
        require(threadId.length <= MAX_THREAD_ID_LENGTH) {
            "threadId 는 최대 ${MAX_THREAD_ID_LENGTH}자여야 한다: actual=${threadId.length}"
        }
        require(subjectPseudonym == null || subjectPseudonym.isNotBlank()) { "subjectPseudonym 은 빈 문자열일 수 없다" }
        require(
            replyToMessageId == null ||
                (
                    replyToMessageId.isNotEmpty() &&
                        replyToMessageId.length <= MAX_REPLY_TO_MESSAGE_ID_LENGTH &&
                        replyToMessageId.all { it in '0'..'9' }
                ),
        ) {
            "replyToMessageId 는 최대 ${MAX_REPLY_TO_MESSAGE_ID_LENGTH}자의 Discord 숫자 ID여야 한다"
        }
        require(targetMessageId == null || targetMessageId.isNotBlank()) { "targetMessageId 는 빈 문자열일 수 없다" }
        require(routingGuildId == null || routingGuildId.isDiscordSnowflake()) { "routingGuildId 형식이 잘못됐다" }
        require(routingChannelId == null || routingChannelId.isDiscordSnowflake()) { "routingChannelId 형식이 잘못됐다" }
        require(routingUserId == null || routingUserId.isDiscordSnowflake()) { "routingUserId 형식이 잘못됐다" }
        require(sceneContextVersion == null || sceneContextVersion >= 0) { "sceneContextVersion 은 음수일 수 없다" }
    }

    fun discordChannelId(): String? = routingChannelId

    private fun String.isDiscordSnowflake(): Boolean = toLongOrNull()?.let { it > 0 } == true

    companion object {
        const val MAX_THREAD_ID_LENGTH = 256
        const val MAX_REPLY_TO_MESSAGE_ID_LENGTH = 20
    }
}
