package com.discordassistant.central.participation.application

import com.discordassistant.central.conversation.domain.event.SceneUpdated

/**
 * SceneUpdated → participation 평가 트리거 연결(NEXA-P15-T005, application 레이어).
 *
 * conversation 이 발행하는 [SceneUpdated](버스트 finalize·장면 갱신 후)를 받아 participation 정책 평가를 **언제
 * 호출할지** 게이팅한다. 실제 평가(feature 추출·정책 호출·sampling)는 주입된 [evaluate] 콜백(participation 평가
 * 유스케이스)이 한다 — 이 연결자는 **트리거 규칙만** 소유한다(SRP·테스트 가능성).
 *
 * **acceptance(T005) — 모든 MessageCreated 마다 즉시 정책 호출하지 않고 finalize/reevaluate 규칙을 따른다**:
 *  - 입력은 [SceneUpdated](장면 갱신 이벤트)지 raw MessageCreated 가 아니다 — conversation 의 burst finalize/
 *    scene projection 이 **버스트를 모아 장면을 갱신한 뒤에만** 이 이벤트가 온다(메시지마다 1:1 아님).
 *  - 그중에서도 [SceneUpdated.invalidatedPolicy](contextVersion 증가)일 때만 [evaluate] 를 호출한다 — 장면이
 *    실제로 바뀌지 않은(version 그대로) 갱신은 직전 판단을 재사용하고 정책을 다시 호출하지 않는다(reevaluate 규칙).
 *  - 멱등은 [SceneUpdated.idempotencyKey](channelId+sceneSeq)로 호출자가 보장(at-least-once 중복 흡수).
 *
 * 순수성 경계: application — conversation **도메인 이벤트** + 콜백만 본다. Spring/JPA/JDA·routing/GLM 미참조.
 * (conversation.domain → participation.application 방향 의존은 허용 — module-dag.md: 하류가 상류 이벤트를 소비.)
 */
class SceneParticipationConnector(
    /** 정책 평가 유스케이스(트리거된 장면에 대해 결정을 만든다). 호출 여부는 이 연결자가 게이팅한다. */
    private val evaluate: (SceneUpdated) -> Unit,
) {
    /**
     * [event] 가 정책을 무효화한 장면 갱신이면 평가를 트리거한다. 아니면 **아무 것도 하지 않는다**(직전 판단 재사용).
     * 트리거 여부를 돌려준다(테스트·관찰).
     */
    fun onSceneUpdated(event: SceneUpdated): TriggerOutcome {
        if (!event.invalidatedPolicy) {
            return TriggerOutcome.SKIPPED_NO_INVALIDATION // contextVersion 그대로 → 재평가 불필요
        }
        evaluate(event)
        return TriggerOutcome.EVALUATED
    }
}

/** [SceneParticipationConnector.onSceneUpdated] 결과 — 평가 트리거/스킵 명시(acceptance 증명·관찰). */
enum class TriggerOutcome {
    /** 정책 무효화 장면 → participation 평가 트리거됨. */
    EVALUATED,

    /** contextVersion 미증가 → 평가 안 함(직전 판단 재사용 — 메시지마다 호출 금지 규칙). */
    SKIPPED_NO_INVALIDATION,
}
