package com.discordassistant.central.participation.domain.model.state

/**
 * 사회 상태의 guild·channel 스코프 키(NEXA-P06, 순수 도메인 value type).
 *
 * 모든 사회 상태는 **guild-scoped** 다 — cross-guild 로 사람이나 채널을 식별·연결하지 않는다(ADR 0010,
 * observable-state-policy 체크리스트 #4). snowflake 원문이 아니라 **가명 토큰**([guildPseudonym]/[channelPseudonym])
 * 만 운반한다(data-categories.md: 식별자는 가명으로만 보존, 외부 전송 금지).
 *
 * 순수성: Spring/JPA/JDA/adapter 타입을 일절 참조하지 않는다.
 */
data class ChannelScope(
    /** guild 가명 토큰(원본 snowflake 아님). guild 경계를 넘는 연결 금지의 1차 키. */
    val guildPseudonym: String,
    /** channel 가명 토큰(원본 snowflake 아님). guild 안에서 채널을 구분한다. */
    val channelPseudonym: String,
) {
    init {
        require(guildPseudonym.isNotBlank()) { "guildPseudonym 은 비어 있을 수 없다" }
        require(channelPseudonym.isNotBlank()) { "channelPseudonym 은 비어 있을 수 없다" }
    }
}
