package com.discordassistant.central.socialmemory.application.extraction

import com.discordassistant.central.socialmemory.domain.model.VisibilityScope

/**
 * finalized scene 에서 **어떤 기억 후보를 추출할지** 묘사하는 요청 모델(NEXA-P07-T014, application 레이어).
 *
 * speech(응답 생성) 경로와 **분리**된다 — 이 요청은 Discord 응답을 만드는 동기 경로에 끼어들지 않고, 비동기로
 * 제출돼 나중에 consolidation(T016/T017)에서 처리된다. 따라서 추출이 느리거나 실패해도 Discord 응답 시간을
 * 막지 않는다(acceptance T014). 요청은 원문이 아니라 scene 식별자·가시성·참여 가명·동의 스냅샷만 운반한다
 * (data-categories.md: 원문 비영속). 실제 GLM 호출은 어댑터(T015)가 routing CloudLlm 포트로만 수행한다.
 *
 * 순수 application: 도메인 타입과 표준 타입만 본다 — Spring/JPA/JDA·glm/Z.AI 타입 미참조.
 */
data class MemoryExtractionRequest(
    /** 추출 근거가 된 finalized scene 의 식별자(원문 아님 — provenance ID). */
    val sceneId: String,
    /** 이 scene 이 속한 guild 가명 + 가시성 스코프(추출 후보의 기본 스코프). */
    val visibility: VisibilityScope,
    /** scene 참여자들의 guild-scoped 가명 토큰(원본 snowflake 아님). */
    val participants: Set<String>,
    /** 이 scene 추출에 쓰일 추출 규칙 버전(재추출 비교 기준). */
    val extractionVersion: Long,
    /** scene 시점 옵트인(동의) 스냅샷. false 면 추출 자체를 건너뛴다(옵트아웃 존중). */
    val consentGranted: Boolean,
    /**
     * 추출기에 전달할 **구조화 관찰 단서**(원문 아님). 닉네임 변경·언어 언급 같은 burst 파생 신호의 짧은 라벨만.
     * 비어 있으면 추출기는 scene 식별자 provenance 만으로 후보를 시도한다.
     */
    val observedCues: List<String> = emptyList(),
) {
    init {
        require(sceneId.isNotBlank()) { "sceneId 는 비어 있을 수 없다" }
        require(participants.none { it.isBlank() }) { "participant 가명은 비어 있을 수 없다" }
        require(extractionVersion >= 0) { "extractionVersion 은 음수일 수 없다" }
    }

    /** 동의가 없으면 추출 대상이 아니다(옵트아웃 사용자 제외, observable-state-policy 체크리스트 5). */
    val isExtractable: Boolean
        get() = consentGranted
}
