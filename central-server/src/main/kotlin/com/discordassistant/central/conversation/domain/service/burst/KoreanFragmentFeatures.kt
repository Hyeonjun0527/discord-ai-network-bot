package com.discordassistant.central.conversation.domain.service.burst

import com.discordassistant.central.conversation.domain.model.burst.MessageFragment
import com.discordassistant.central.conversation.domain.model.event.MessageContent

/**
 * 한국어 짧은 조각 언어 feature 추출기(NEXA-P04-T011, 순수 함수). 종결어미 부재·자음 축약·ㅃㄹ(빠른 재촉)·ㅋㅋ(웃음)·
 * 문장부호 같은 한국어 채팅 신호를 **feature 값**으로만 뽑는다. 이 값은 segmenter 가 참고할 수 있는 보조 신호일 뿐,
 * 버스트 경계의 **최종 결정을 단독으로 강제하지 않는다**(acceptance T011) — 결정은 baseline 규칙([FixedGapBurstSegmenter])이
 * 소유한다.
 *
 * 순수성: conversation.domain 규칙(NexaArchitectureTest.nexaDomainsArePure)을 위해 Spring/JPA/JDA/adapter 타입을
 * 일절 참조하지 않는다. 표준 String 산술만 쓰고 객체 상태가 없다(모든 입력을 인자로 받는다).
 *
 * **acceptance(T011) — 값만 검증·결정 비강제**: 모든 메서드는 boolean/Int 같은 **feature 값**만 돌려주고 경계를
 * 정하지 않는다. content 가 [MessageContent.Available] 가 아니면(미허용/빈) 텍스트 신호는 모두 부재로 본다 —
 * 권한 없음과 "한국어 종결어미 없음" 을 뭉개지 않는다(원문 미관찰이면 언어 규칙을 적용할 근거가 없다).
 */
object KoreanFragmentFeatures {
    /** 문장 종결로 흔히 쓰는 어미 꼬리(없으면 짧은 미완결 조각일 가능성 — 다음 조각으로 이어질 신호). */
    private val SENTENCE_FINAL_ENDINGS =
        listOf("다", "요", "까", "죠", "네", "지", "음", "함", "임", "걸", "군", "냐", "니", "래", "려")

    /** 텍스트가 관찰 가능한(Available) 경우의 trim 된 본문, 아니면 null(미허용/빈 — 언어 신호 부재). */
    private fun textOf(fragment: MessageFragment): String? =
        (fragment.content as? MessageContent.Available)?.text?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 종결어미가 **부재**한가(미완결 짧은 조각 신호). 한국어 종결 꼬리·종결 문장부호(. ! ? ~) 어느 것으로도 끝나지
     * 않으면 true. 원문 미관찰(Available 아님)이면 판단 근거가 없어 false(신호 부재로 취급).
     */
    fun lacksSentenceEnding(fragment: MessageFragment): Boolean {
        val text = textOf(fragment) ?: return false
        if (text.last() in ".!?~。！？") return false
        return SENTENCE_FINAL_ENDINGS.none { text.endsWith(it) }
    }

    /**
     * 종성 단독 자음 축약(예: "ㅇㅇ", "ㄴㄴ", "ㅇㅋ")이 포함됐는가. 호환 자모 영역(U+3131..U+314E)의 단독 자음을
     * 1개 이상 포함하면 true(축약·리액션성 짧은 조각 신호). 원문 미관찰이면 false.
     */
    fun hasConsonantCluster(fragment: MessageFragment): Boolean {
        val text = textOf(fragment) ?: return false
        return text.any { it in 'ㄱ'..'ㅎ' }
    }

    /**
     * "ㅃㄹ"(빨리 재촉) 신호를 포함하는가. 압축 자음 축약형 'ㅃㄹ' 또는 음절형 '빨리'/'빠르게' 를 담으면 true
     * (상대 발화 재촉 — 후속 조각이 빠르게 이어질 가능성 신호). 원문 미관찰이면 false.
     */
    fun urgesHurry(fragment: MessageFragment): Boolean {
        val text = textOf(fragment) ?: return false
        return text.contains("ㅃㄹ") || text.contains("빨리") || text.contains("빠르게")
    }

    /**
     * 'ㅋ'(웃음) 반복 길이 — 연속한 'ㅋ' 의 최대 run 길이(웃음 강도 신호). 'ㅋ' 가 없으면 0. 원문 미관찰이면 0.
     * 평균/부동소수 없이 정수 run 만 세어 replay 결정론적이다.
     */
    fun laughterRunLength(fragment: MessageFragment): Int {
        val text = textOf(fragment) ?: return 0
        var best = 0
        var current = 0
        for (ch in text) {
            if (ch == 'ㅋ') {
                current += 1
                if (current > best) best = current
            } else {
                current = 0
            }
        }
        return best
    }

    /** 종결성 문장부호(. ! ? ~ 및 전각)로 끝나는가(완결 신호). 원문 미관찰이면 false. */
    fun endsWithPunctuation(fragment: MessageFragment): Boolean {
        val text = textOf(fragment) ?: return false
        return text.last() in ".!?~。！？"
    }
}
