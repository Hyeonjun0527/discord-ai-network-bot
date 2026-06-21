package com.discordassistant.central.conversation.domain.event

import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.scene.SceneChange
import com.discordassistant.central.conversation.domain.model.thread.ConversationThreadId

/**
 * 장면 갱신 도메인 이벤트(NEXA-P05-T019). conversation 이 [com.discordassistant.central.conversation.domain.model.scene.ConversationScene]
 * 를 갱신했을 때 발행해 다음 소비자(participation·speech 읽기)에 전달한다. domain-events.md 카탈로그 계약을 따른다:
 * **발행자=conversation, 소비자=participation·speech(읽기), 멱등성 키=[channelId] + [sceneSeq], PII=medium**.
 *
 * 변경 유형([changeType]), 이전/새 contextVersion([previousContextVersion]/[newContextVersion]), 영향 thread
 * ([affectedThreadIds])를 전달한다 — 소비자는 contextVersion 이 올랐는지로 직전 판단 재사용 여부를 정한다.
 *
 * 순수성: conversation.domain 규칙(NexaArchitectureTest.nexaDomainsArePure)을 위해 Spring/JPA/JDA/adapter 타입을
 * 일절 참조하지 않는다. 원문 텍스트를 운반하지 않는다(PII medium — 식별자·결정 메타만; domain-events.md 규칙 3).
 *
 * **acceptance(T019) — 동일 입력 재생에서 이벤트 순서·version 동일**: 멱등성 키는 [channelId] + [sceneSeq] 다
 * ([idempotencyKey]). [sceneSeq] 가 단조 증가하고 contextVersion 증가가 결정론적([ContextVersion.apply])이라,
 * 같은 입력 시퀀스를 재생하면 같은 순서·같은 version 의 이벤트가 나온다.
 */
data class SceneUpdated(
    val guildId: GuildId,
    /** 멱등성 키의 위치 부분(domain-events.md `channelId + sceneSeq`). */
    val channelId: ChannelId,
    /** 멱등성 키의 순번 부분 — 채널 내 단조 증가 장면 순번. */
    val sceneSeq: Long,
    /** 무엇이 장면을 바꿨는지(소비자 분기 근거). */
    val changeType: SceneChangeType,
    /** 갱신 전 정책 무효화 버전 — 소비자가 직전 판단 재사용 여부를 비교한다(T018). */
    val previousContextVersion: Long,
    /** 갱신 후 정책 무효화 버전 — [previousContextVersion] 과 다르면 직전 판단 무효. */
    val newContextVersion: Long,
    /** 이 갱신으로 새로 생기거나 사라진 영향 thread 식별자(원문 비포함). */
    val affectedThreadIds: Set<ConversationThreadId>,
) {
    init {
        require(sceneSeq >= 0) { "sceneSeq 는 음수일 수 없다" }
        require(previousContextVersion >= 0) { "previousContextVersion 은 음수일 수 없다" }
        require(newContextVersion >= previousContextVersion) {
            "newContextVersion 은 previous 보다 작을 수 없다(version 은 단조 비감소): $previousContextVersion -> $newContextVersion"
        }
    }

    /** 멱등성 키(domain-events.md `channelId + sceneSeq`). 소비자는 이 키로 중복 수신을 무시한다(at-least-once). */
    fun idempotencyKey(): String = "scene:${channelId.value}:$sceneSeq"

    /** 이 갱신이 정책 판단을 무효화했는가 — contextVersion 이 올랐으면 true(소비자가 재판단해야 한다). */
    val invalidatedPolicy: Boolean
        get() = newContextVersion > previousContextVersion
}

/**
 * 장면 변경 유형(NEXA-P05-T019, 순수 도메인 enum). [SceneUpdated] 에 실려 소비자가 분기한다. 도메인 모델
 * [SceneChange](버전 증가 규칙 소유, T018)와 1:1 대응하지만, 이벤트 계약은 모델 enum 에 직접 묶이지 않게 별도로 둔다
 * (이벤트 wire 안정성 — 모델 enum 이 바뀌어도 [of] 매핑만 갱신).
 */
enum class SceneChangeType {
    HUMAN_REPLIED,
    TOPIC_SWITCHED,
    MESSAGE_DELETED,
    THREAD_RESTRUCTURED,
    METRICS_UPDATED,
    PARTICIPANT_METADATA_TOUCHED,
    ;

    companion object {
        /** 도메인 [SceneChange] 를 이벤트 [SceneChangeType] 으로 매핑한다(결정론적 1:1). */
        fun of(change: SceneChange): SceneChangeType =
            when (change) {
                SceneChange.HUMAN_REPLIED -> HUMAN_REPLIED
                SceneChange.TOPIC_SWITCHED -> TOPIC_SWITCHED
                SceneChange.MESSAGE_DELETED -> MESSAGE_DELETED
                SceneChange.THREAD_RESTRUCTURED -> THREAD_RESTRUCTURED
                SceneChange.METRICS_UPDATED -> METRICS_UPDATED
                SceneChange.PARTICIPANT_METADATA_TOUCHED -> PARTICIPANT_METADATA_TOUCHED
            }
    }
}
