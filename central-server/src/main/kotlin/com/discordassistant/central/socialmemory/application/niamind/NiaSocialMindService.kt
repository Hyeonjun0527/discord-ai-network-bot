package com.discordassistant.central.socialmemory.application.niamind

import com.discordassistant.central.socialmemory.domain.model.appraisal.Appraisal
import com.discordassistant.central.socialmemory.domain.model.appraisal.EventIntensity
import com.discordassistant.central.socialmemory.domain.model.appraisal.SocialEventKind
import com.discordassistant.central.socialmemory.domain.model.emotion.EmotionEventGrade
import com.discordassistant.central.socialmemory.domain.model.emotion.EmotionEventSign
import com.discordassistant.central.socialmemory.domain.model.emotion.ToneHint
import com.discordassistant.central.socialmemory.domain.model.niarelationship.RelationshipState
import com.discordassistant.central.socialmemory.domain.service.appraisal.AppraiserProvider
import com.discordassistant.central.socialmemory.domain.service.appraisal.SocialAppraiser
import com.discordassistant.central.socialmemory.domain.service.emotion.EmotionEngine
import com.discordassistant.central.socialmemory.domain.service.emotion.EmotionRenderer
import com.discordassistant.central.socialmemory.domain.service.niarelationship.RelationshipEngine
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * 니아 사회 마음 — 한 메시지를 받아 **Appraiser(B5) → 관계 갱신(B6) → 감정(D1) → 톤(D2)** 으로 잇는 오케스트레이션.
 * core 의 파이프라인(appraise→update→emotion→render)을 NEXA application 으로 이식한 통합 진입점.
 *
 * 불변식은 도메인이 보장한다: I1 랜덤0·I2 등급만·I3 기분미입력·I4 이중제한·I9 읽기전용·I11 정체성고정.
 * 본 서비스는 도메인 순수 함수만 호출하고, 상태 영속은 [NiaSocialStatePort] 에 위임한다.
 *
 * **[persist]=false(shadow)**: 상태를 계산하되 **저장하지 않는다**(예측만 — ShadowMode.SHADOW_PREDICT).
 * **[persist]=true(live/canary)**: 갱신 상태를 저장한다. 어느 경우든 발화 톤 힌트를 반환한다(호출자가 shadow 면 무시).
 */
@Service
class NiaSocialMindService(
    private val appraiser: AppraiserProvider,
    private val statePort: NiaSocialStatePort,
) {
    /**
     * 한 메시지 관찰 → 사회 상태 갱신 + 발화 톤 힌트.
     *
     * @param scope guild(+channel) 격리 키(I7). @param speakerPersonId 발화자(`discord:<id>`).
     * @param now 현재 시각(주입 — 랜덤·시스템시각 직접 접근 없음, I1).
     * @param persist false 면 상태 미저장(shadow 예측). @param wasToneActive 직전 렌더 활성 여부(히스테리시스 I12).
     */
    fun observe(
        scope: String,
        speakerPersonId: String,
        messages: List<String>,
        now: Instant,
        persist: Boolean,
        wasToneActive: Boolean = false,
        candidatePersonIds: List<String>? = null,
    ): NiaMindOutcome {
        require(messages.isNotEmpty()) { "messages 가 비어 있다 — 관찰할 사건이 없다" }
        require(speakerPersonId.isNotBlank()) { "speakerPersonId 가 비어 있다(I7)" }

        // 1) 관계 4축(B4 읽기) → 해석 렌즈로 Appraiser(B5).
        val relBefore = statePort.loadRelationship(speakerPersonId, scope) ?: RelationshipState(speakerPersonId)
        val lens =
            com.discordassistant.central.socialmemory.domain.model.appraisal.RelationshipLens.fromAxes(
                relBefore.familiarity,
                relBefore.affinity,
                relBefore.trust,
                relBefore.comfort,
            )
        val appraisal = SocialAppraiser.appraise(appraiser, messages, speakerPersonId, lens, candidatePersonIds)

        // 2) 등급 → 관계 갱신(B6). targetIsNia=false·LOW 는 엔진이 보수 처리.
        val relResult = RelationshipEngine.updateRelationship(relBefore, appraisal, now)

        // 3) 등급 → 감정(D1). 니아 대상 사건만 감정에 반영(대상 아님이면 감쇠만).
        val emoBefore = statePort.loadEmotion(scope) ?: EmotionEngine.baseline(scope)
        val emoAfter =
            if (appraisal.targetIsNia) {
                val (grade, sign, affectsMood) = toEmotionEvent(appraisal)
                EmotionEngine.applyEvent(emoBefore, now, grade, sign, affectsMood = affectsMood)
            } else {
                EmotionEngine.readDecayed(emoBefore, now) // 대상 아님: 감쇠만(사건 영향 0).
            }

        // 4) 톤 힌트(D2) — 임계·히스테리시스. 정체성·길이 불변(I11).
        val tone = EmotionRenderer.renderToneHint(emoAfter, lens.label, wasToneActive)

        // 5) 영속(live/canary). shadow 면 저장하지 않는다(예측만).
        if (persist) {
            if (relResult.changed) statePort.saveRelationship(scope, relResult.after)
            statePort.saveEmotion(emoAfter)
        }

        return NiaMindOutcome(appraisal, relResult.after, emoAfter, tone, persisted = persist)
    }

    /** Appraisal 등급 → 감정 사건(grade·sign·affectsMood). 긍정 kind=+1, 부정=−1, 중립(질문·잡담)=mood 미반영. */
    private fun toEmotionEvent(a: Appraisal): Triple<EmotionEventGrade, EmotionEventSign, Boolean> {
        val grade = EmotionEventGrade.valueOf(intensityName(a.intensity))
        return when (a.kind) {
            SocialEventKind.PRAISE, SocialEventKind.PLAYFUL, SocialEventKind.COLLAB, SocialEventKind.APOLOGY ->
                Triple(grade, EmotionEventSign.POSITIVE, true)
            SocialEventKind.INSULT ->
                Triple(grade, EmotionEventSign.NEGATIVE, true)
            // 질문·잡담: 순간 미세 반응만, 느린 기분(mood)엔 반영 안 함(보수).
            SocialEventKind.QUESTION, SocialEventKind.SMALLTALK ->
                Triple(EmotionEventGrade.MICRO, EmotionEventSign.POSITIVE, false)
        }
    }

    private fun intensityName(i: EventIntensity): String =
        when (i) {
            EventIntensity.MICRO -> "MICRO"
            EventIntensity.MILD -> "MILD"
            EventIntensity.CLEAR -> "CLEAR"
            EventIntensity.STRONG -> "STRONG"
        }
}

/** 한 관찰의 결과 — 갱신된 관계·감정·발화 톤(설명 가능성). [persisted]=false 면 shadow 예측. */
data class NiaMindOutcome(
    val appraisal: Appraisal,
    val relationship: RelationshipState,
    val emotion: com.discordassistant.central.socialmemory.domain.model.emotion.EmotionState,
    val tone: ToneHint,
    val persisted: Boolean,
)
