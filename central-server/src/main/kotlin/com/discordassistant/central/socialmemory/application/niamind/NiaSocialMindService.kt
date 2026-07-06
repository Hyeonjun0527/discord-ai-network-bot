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
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
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
 *
 * **동시성(live)**: load→compute→save 는 read-modify-write 다. 같은 scope 로 동시 메시지가 오면 갱신을 잃을 수
 * 있어, live 저장은 **짧은 트랜잭션 안에서 fresh 재로드→순수 엔진 재적용→저장**하고 엔티티 `@Version` 낙관적 락으로
 * 충돌을 감지해 재시도한다([MAX_PERSIST_ATTEMPTS]). **GLM(Appraiser) 호출은 트랜잭션 밖**이다(연결·락을 LLM
 * 지연 동안 붙잡지 않는다) — appraisal 은 한 번만 계산하고, 재시도는 값싼 순수 엔진만 다시 돌린다. 첫 (scope,person)
 * 행 중복 INSERT 는 유니크 제약이 막고 재시도가 흡수한다. 트랜잭션 매니저가 없으면(단위테스트) 락 없이 직접 실행한다.
 */
@Service
class NiaSocialMindService(
    private val appraiser: AppraiserProvider,
    private val statePort: NiaSocialStatePort,
    transactionManager: PlatformTransactionManager? = null,
) {
    /** live 저장을 감싸는 짧은 트랜잭션(낙관적 락 충돌 감지 경계). null 이면(단위테스트) 트랜잭션 없이 직접 실행. */
    private val txTemplate: TransactionTemplate? = transactionManager?.let { TransactionTemplate(it) }

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

        // 1) 관계 4축(B4 읽기) → 해석 렌즈로 Appraiser(B5). ★ GLM 호출은 트랜잭션 밖(연결·락 미보유).
        val relBefore = statePort.loadRelationship(speakerPersonId, scope) ?: RelationshipState(speakerPersonId)
        val lens =
            com.discordassistant.central.socialmemory.domain.model.appraisal.RelationshipLens.fromAxes(
                relBefore.familiarity,
                relBefore.affinity,
                relBefore.trust,
                relBefore.comfort,
            )
        val appraisal = SocialAppraiser.appraise(appraiser, messages, speakerPersonId, lens, candidatePersonIds)

        // 2) shadow(persist=false): 저장 없이 relBefore/emoBefore 기준으로 예측만 계산한다(동시성 무관).
        if (!persist) {
            val emoBefore = statePort.loadEmotion(scope) ?: EmotionEngine.baseline(scope)
            return computeOutcome(scope, appraisal, relBefore, emoBefore, now, wasToneActive, lens.label, persisted = false)
        }

        // 3) live: 짧은 트랜잭션 안에서 fresh 재로드→순수 엔진 재적용→저장(@Version 낙관적 락). 충돌 시 재시도
        //    (appraisal 고정 — GLM 재호출 없음, 값싼 순수 엔진만 재실행). 첫 행 중복 INSERT 는 유니크 제약이 막고 흡수.
        return persistWithRetry(scope, speakerPersonId, appraisal, now, wasToneActive, lens.label)
    }

    /**
     * live 저장 경로 — 낙관적 락 충돌([OptimisticLockingFailureException]) 또는 첫 행 동시 INSERT 경합
     * ([DataIntegrityViolationException]) 시 [MAX_PERSIST_ATTEMPTS] 까지 재시도한다. 재시도마다 fresh 상태를
     * 다시 읽어 순수 엔진을 재적용하므로 마지막 커밋이 최신 상태 위에 얹힌다(갱신 유실 방지).
     */
    private fun persistWithRetry(
        scope: String,
        speakerPersonId: String,
        appraisal: Appraisal,
        now: Instant,
        wasToneActive: Boolean,
        lensLabel: String,
    ): NiaMindOutcome {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return inTransaction {
                    val relFresh = statePort.loadRelationship(speakerPersonId, scope) ?: RelationshipState(speakerPersonId)
                    val emoFresh = statePort.loadEmotion(scope) ?: EmotionEngine.baseline(scope)
                    computeOutcome(scope, appraisal, relFresh, emoFresh, now, wasToneActive, lensLabel, persisted = true)
                }
            } catch (e: OptimisticLockingFailureException) {
                if (attempt >= MAX_PERSIST_ATTEMPTS) throw e // 소진: 상위(runCatching)가 톤 ""로 graceful 하강.
            } catch (e: DataIntegrityViolationException) {
                if (attempt >= MAX_PERSIST_ATTEMPTS) throw e // 첫 행 유니크 경합: 재시도하면 기존 행을 찾아 갱신.
            }
        }
    }

    /** [block] 을 짧은 트랜잭션 안에서 실행한다. txTemplate 이 없으면(단위테스트) 트랜잭션 없이 직접 실행. */
    private fun <T> inTransaction(block: () -> T): T {
        val tx = txTemplate ?: return block()
        return tx.execute { block() }!!
    }

    /**
     * 등급(appraisal) → 관계 갱신(B6) → 감정(D1) → 톤(D2) 순수 계산. [persisted]=true 면 갱신을 저장한다
     * (트랜잭션 안에서 호출되어 @Version 낙관적 락이 커밋 시 충돌을 감지). 정체성·길이 불변(I11).
     */
    private fun computeOutcome(
        scope: String,
        appraisal: Appraisal,
        relInput: RelationshipState,
        emoInput: com.discordassistant.central.socialmemory.domain.model.emotion.EmotionState,
        now: Instant,
        wasToneActive: Boolean,
        lensLabel: String,
        persisted: Boolean,
    ): NiaMindOutcome {
        // 관계 갱신(B6). targetIsNia=false·LOW 는 엔진이 보수 처리.
        val relResult = RelationshipEngine.updateRelationship(relInput, appraisal, now)
        // 감정(D1). 니아 대상 사건만 감정에 반영(대상 아님이면 감쇠만).
        val emoAfter =
            if (appraisal.targetIsNia) {
                val (grade, sign, affectsMood) = toEmotionEvent(appraisal)
                EmotionEngine.applyEvent(emoInput, now, grade, sign, affectsMood = affectsMood)
            } else {
                EmotionEngine.readDecayed(emoInput, now) // 대상 아님: 감쇠만(사건 영향 0).
            }
        // 톤 힌트(D2) — 임계·히스테리시스.
        val tone = EmotionRenderer.renderToneHint(emoAfter, lensLabel, wasToneActive)
        if (persisted) {
            if (relResult.changed) statePort.saveRelationship(scope, relResult.after)
            statePort.saveEmotion(emoAfter)
        }
        return NiaMindOutcome(appraisal, relResult.after, emoAfter, tone, persisted = persisted)
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

    companion object {
        /** live 저장 낙관적 락/유니크 경합 재시도 상한(총 시도 횟수). 경합은 드물어 작게. */
        const val MAX_PERSIST_ATTEMPTS: Int = 3
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
