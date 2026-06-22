package com.discordassistant.central.socialmemory.domain.model.relationship

/**
 * NEXA 와 한 사용자 사이 관계 상태의 **guild-scoped 가명 키**(NEXA-P06-T004, 순수 도메인 value type).
 *
 * **전역 사용자 프로필이 아니라 guild-scoped key 를 쓴다**(acceptance T004, ADR 0010 — user_affinity 의 전역
 * unique 와 달리 socialmemory 는 guild 안에서만 사람을 식별한다). snowflake 원문이 아니라 가명 토큰만 운반한다
 * (data-categories.md: 식별자는 가명으로만 보존, 외부 전송 금지, observable-state-policy 체크리스트 #4).
 *
 * 순수성: Spring/JPA/JDA·ainetwork 엔티티 미참조.
 */
data class MemberKey(
    /** guild 가명 토큰(원본 snowflake 아님). 같은 사용자라도 guild 가 다르면 다른 키 — cross-guild 연결 금지. */
    val guildPseudonym: String,
    /** guild 안의 사용자 가명 토큰(원본 snowflake 아님). */
    val memberPseudonym: String,
) {
    init {
        require(guildPseudonym.isNotBlank()) { "guildPseudonym 은 비어 있을 수 없다" }
        require(memberPseudonym.isNotBlank()) { "memberPseudonym 은 비어 있을 수 없다" }
    }
}
