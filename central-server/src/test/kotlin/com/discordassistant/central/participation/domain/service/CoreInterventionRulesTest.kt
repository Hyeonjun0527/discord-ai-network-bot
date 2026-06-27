package com.discordassistant.central.participation.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [CoreInterventionRules] 단위 테스트 — core nia_engine(hard_policy.py·rules.py) 케이스 1:1 매핑.
 *
 * 검증 대상(작업 요구): 니아 호명→SPEAK, 타인 지목 질문→SILENT, 두 사람 사적 핑퐁→SILENT, 끝난 흐름→SILENT,
 * 미완성 발화→WAIT, 봇/시스템→DROP(SILENT). + hard_policy reply/continuation/멘션/중복/빈 메시지 + Candidate 위임.
 */
class CoreInterventionRulesTest {
    private fun input(
        text: String,
        speakerLabel: String = "user_1",
        mentioned: Boolean = false,
        replyToNia: Boolean = false,
        niaRecentTokens: List<String> = emptyList(),
        withinContinuationTtl: Boolean = false,
        burstIncomplete: Boolean = false,
        duplicateOfPrevHuman: Boolean = false,
        priorHumanSpeakerLabels: List<String> = emptyList(),
        firstMessageText: String? = null,
        conversationMentionsNia: Boolean = false,
    ) = CoreInterventionRules.RuleInput(
        triggerText = text,
        speakerLabel = speakerLabel,
        mentioned = mentioned,
        replyToNia = replyToNia,
        niaRecentTokens = niaRecentTokens,
        withinContinuationTtl = withinContinuationTtl,
        burstIncomplete = burstIncomplete,
        duplicateOfPrevHuman = duplicateOfPrevHuman,
        priorHumanSpeakerLabels = priorHumanSpeakerLabels,
        firstMessageText = firstMessageText,
        conversationMentionsNia = conversationMentionsNia,
    )

    // ── SPEAK (core RESPOND_NOW / rules clear_speak) ────────────────────────────

    @Test
    fun `니아 직접 호명(호격)이면 SPEAK`() {
        val v = CoreInterventionRules.evaluate(input("니아야 이거 어때?"))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Speak::class.java)
        assertThat((v as CoreInterventionRules.Verdict.Speak).reasonCode).isEqualTo("RULE_NIA_ADDRESSED")
    }

    @Test
    fun `봇 직접 @멘션이면 SPEAK`() {
        val v = CoreInterventionRules.evaluate(input("이거 좀 봐줘", mentioned = true))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Speak::class.java)
    }

    @Test
    fun `인용문 안의 니아 호명은 SPEAK 아님(인용 제외)`() {
        // 인용 부호 안의 "니아야" 는 호명으로 보지 않는다 → Candidate 로 위임(애매).
        val v = CoreInterventionRules.evaluate(input("아까 \"니아야 와봐\" 라고 했었지"))
        assertThat(v).isEqualTo(CoreInterventionRules.Verdict.Candidate)
    }

    @Test
    fun `니아 발화에 대한 reply 면 SPEAK`() {
        val v = CoreInterventionRules.evaluate(input("그건 왜 그래", replyToNia = true))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Speak::class.java)
        assertThat((v as CoreInterventionRules.Verdict.Speak).reasonCode).isEqualTo("RULE_REPLY_TO_NIA")
    }

    @Test
    fun `니아 직전 발화 토큰과 겹치면(TTL 내) continuation SPEAK`() {
        val v =
            CoreInterventionRules.evaluate(
                input(
                    "그 영화 진짜 재밌더라",
                    niaRecentTokens = listOf("영화", "재밌"),
                    withinContinuationTtl = true,
                ),
            )
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Speak::class.java)
        assertThat((v as CoreInterventionRules.Verdict.Speak).reasonCode).isEqualTo("RULE_CONTINUATION")
    }

    @Test
    fun `continuation 토큰 겹쳐도 TTL 밖이면 SPEAK 아님`() {
        val v =
            CoreInterventionRules.evaluate(
                input("그 영화 재밌더라", niaRecentTokens = listOf("영화"), withinContinuationTtl = false),
            )
        assertThat(v).isEqualTo(CoreInterventionRules.Verdict.Candidate)
    }

    // ── SILENT (core DROP / rules clear_silent) ─────────────────────────────────

    @Test
    fun `특정 타인 지목 질문이면 SILENT`() {
        val v = CoreInterventionRules.evaluate(input("준호야 너 표 있어?"))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Silent::class.java)
        assertThat((v as CoreInterventionRules.Verdict.Silent).reasonCode).isEqualTo("RULE_QUESTION_TO_OTHER")
    }

    @Test
    fun `타인 @멘션이면 SILENT`() {
        val v = CoreInterventionRules.evaluate(input("@준호 이거 봐봐"))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Silent::class.java)
    }

    @Test
    fun `끝난 흐름(작별·감사)이면 SILENT`() {
        val v = CoreInterventionRules.evaluate(input("다들 고마워!"))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Silent::class.java)
        assertThat((v as CoreInterventionRules.Verdict.Silent).reasonCode).isEqualTo("RULE_ALREADY_RESOLVED")
    }

    @Test
    fun `두 사람만의 사적 핑퐁이면 SILENT`() {
        val v =
            CoreInterventionRules.evaluate(
                input(
                    "응 그래",
                    speakerLabel = "user_2",
                    priorHumanSpeakerLabels = listOf("user_1"),
                    firstMessageText = "준호야 너 어제 그거 봤어?",
                ),
            )
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Silent::class.java)
        assertThat((v as CoreInterventionRules.Verdict.Silent).reasonCode).isEqualTo("RULE_PRIVATE_PINGPONG")
    }

    @Test
    fun `사적 핑퐁이라도 대화 중 니아 호명되면 SILENT 아님`() {
        val v =
            CoreInterventionRules.evaluate(
                input(
                    "응 그래",
                    speakerLabel = "user_2",
                    priorHumanSpeakerLabels = listOf("user_1"),
                    firstMessageText = "준호야 봤어?",
                    conversationMentionsNia = true,
                ),
            )
        assertThat(v).isEqualTo(CoreInterventionRules.Verdict.Candidate)
    }

    @Test
    fun `봇 자기 메시지면 DROP(SILENT)`() {
        val v = CoreInterventionRules.evaluate(input("안녕하세요", speakerLabel = "니아"))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Silent::class.java)
        assertThat((v as CoreInterventionRules.Verdict.Silent).reasonCode).isEqualTo("RULE_SELF_MESSAGE")
    }

    @Test
    fun `시스템 화자면 DROP(SILENT)`() {
        val v = CoreInterventionRules.evaluate(input("공지입니다", speakerLabel = "system"))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Silent::class.java)
    }

    @Test
    fun `빈 메시지면 SILENT`() {
        val v = CoreInterventionRules.evaluate(input("   "))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Silent::class.java)
        assertThat((v as CoreInterventionRules.Verdict.Silent).reasonCode).isEqualTo("RULE_EMPTY")
    }

    @Test
    fun `직전 사람 메시지와 중복이면 SILENT`() {
        val v = CoreInterventionRules.evaluate(input("ㅋㅋㅋ", duplicateOfPrevHuman = true))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Silent::class.java)
        assertThat((v as CoreInterventionRules.Verdict.Silent).reasonCode).isEqualTo("RULE_DUPLICATE")
    }

    // ── WAIT (core clear_wait) ──────────────────────────────────────────────────

    @Test
    fun `발화 묶음 미완성이면 WAIT`() {
        val v = CoreInterventionRules.evaluate(input("그래서 내 생각엔", burstIncomplete = true))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Wait::class.java)
        assertThat((v as CoreInterventionRules.Verdict.Wait).reasonCode).isEqualTo("RULE_INCOMPLETE_BURST")
    }

    @Test
    fun `이어가는 연결어로 끝나면 WAIT`() {
        val v = CoreInterventionRules.evaluate(input("아니 그러니까"))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Wait::class.java)
        assertThat((v as CoreInterventionRules.Verdict.Wait).reasonCode).isEqualTo("RULE_TRAILING_CONNECTIVE")
    }

    // ── CANDIDATE (core forced_action=None → 정책 위임) ─────────────────────────

    @Test
    fun `명시 신호 없는 일상 잡담이면 Candidate(정책 위임)`() {
        val v = CoreInterventionRules.evaluate(input("오늘 점심 뭐 먹지"))
        assertThat(v).isEqualTo(CoreInterventionRules.Verdict.Candidate)
    }

    @Test
    fun `이어가는 연결어는 호명이어도 WAIT`() {
        val v = CoreInterventionRules.evaluate(input("니아야 그러니까", burstIncomplete = true))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Wait::class.java)
        assertThat((v as CoreInterventionRules.Verdict.Wait).reasonCode).isEqualTo("RULE_TRAILING_CONNECTIVE")
    }

    @Test
    fun `니아 직접 호명과 짧은 간격 burst 가 결합하면 반복 호출로 SPEAK`() {
        val v = CoreInterventionRules.evaluate(input("니아야", burstIncomplete = true))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Speak::class.java)
        assertThat((v as CoreInterventionRules.Verdict.Speak).reasonCode).isEqualTo("RULE_REPEATED_NIA_CALL")
    }

    @Test
    fun `니아 직접 호명과 중복이 결합하면 반복 호출로 SPEAK`() {
        val v = CoreInterventionRules.evaluate(input("니아야", duplicateOfPrevHuman = true))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Speak::class.java)
        assertThat((v as CoreInterventionRules.Verdict.Speak).reasonCode).isEqualTo("RULE_REPEATED_NIA_CALL")
    }

    @Test
    fun `니아에게 대답을 요구하는 중복도 반복 호출로 SPEAK`() {
        val v = CoreInterventionRules.evaluate(input("니아야 대답해", duplicateOfPrevHuman = true))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Speak::class.java)
        assertThat((v as CoreInterventionRules.Verdict.Speak).reasonCode).isEqualTo("RULE_REPEATED_NIA_CALL")
    }

    // ── SPEAK — policies.yaml direct_address_markers (DIRECT_ADDRESS_MARKERS) ──

    @Test
    fun `니아야(policies yaml 마커2)이면 SPEAK`() {
        // "니아야"는 DIRECT_ADDRESS_MARKERS 와 NIA_VOCATIVE 모두 해당 → SPEAK 확인.
        val v = CoreInterventionRules.evaluate(input("니아야 도와줘"))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Speak::class.java)
        assertThat((v as CoreInterventionRules.Verdict.Speak).reasonCode).isEqualTo("RULE_NIA_ADDRESSED")
    }

    @Test
    fun `니아 이거(policies yaml 마커3)이면 SPEAK`() {
        val v = CoreInterventionRules.evaluate(input("니아 이거 좀 봐줘"))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Speak::class.java)
        assertThat((v as CoreInterventionRules.Verdict.Speak).reasonCode).isEqualTo("RULE_NIA_ADDRESSED")
    }

    @Test
    fun `니아 이거 봐(policies yaml 마커4)이면 SPEAK`() {
        val v = CoreInterventionRules.evaluate(input("니아 이거 봐 어떻게 생각해?"))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Speak::class.java)
    }

    @Test
    fun `니아 봐봐(policies yaml 마커5)이면 SPEAK`() {
        val v = CoreInterventionRules.evaluate(input("니아 봐봐 이게 맞아?"))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Speak::class.java)
    }

    @Test
    fun `니아님(policies yaml 마커6)이면 SPEAK`() {
        val v = CoreInterventionRules.evaluate(input("니아님 질문 있어요"))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Speak::class.java)
    }

    @Test
    fun `니아 좀(policies yaml 마커7)이면 SPEAK`() {
        val v = CoreInterventionRules.evaluate(input("니아 좀 설명해줘"))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Speak::class.java)
    }

    @Test
    fun `니아 어때(policies yaml 마커8)이면 SPEAK`() {
        val v = CoreInterventionRules.evaluate(input("니아 어때 이 방법?"))
        assertThat(v).isInstanceOf(CoreInterventionRules.Verdict.Speak::class.java)
    }

    @Test
    fun `인용 안의 니아님 호명은 SPEAK 아님(인용 제외 — NIA_VOCATIVE·마커 일치)`() {
        // "니아님" 이 인용 부호 안에만 있으면 (마커 경로·NIA_VOCATIVE 경로 모두) 인용 제외로 SPEAK 아님 → Candidate.
        val v = CoreInterventionRules.evaluate(input("아까 \"니아님 와요\" 라고 적혀 있었어"))
        assertThat(v).isEqualTo(CoreInterventionRules.Verdict.Candidate)
    }
}
