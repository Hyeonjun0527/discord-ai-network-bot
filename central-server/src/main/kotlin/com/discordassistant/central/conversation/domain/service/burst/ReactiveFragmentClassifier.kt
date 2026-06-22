package com.discordassistant.central.conversation.domain.service.burst

import com.discordassistant.central.conversation.domain.model.burst.FragmentType
import com.discordassistant.central.conversation.domain.model.burst.MessageFragment
import com.discordassistant.central.conversation.domain.model.event.MessageContent

/**
 * emoji·스티커·attachment-only 조각 분류기(NEXA-P04-T012, 순수 함수). 텍스트가 없는 **반응성 메시지**(이모지만,
 * 스티커만, 첨부만)를 버스트의 일부로 볼지 독립 행동으로 볼지 분류한다 — 결정은 [ReactiveFragmentKind] 라는 값으로만
 * 돌려주고, 버스트 join/finalize 자체는 baseline 규칙([FixedGapBurstSegmenter])이 소유한다(결정 비강제).
 *
 * 순수성: conversation.domain 규칙(NexaArchitectureTest.nexaDomainsArePure)을 위해 Spring/JPA/JDA/adapter 타입을
 * 일절 참조하지 않는다.
 *
 * **acceptance(T012) — 원문 파일 비다운로드**: 분류는 조각이 이미 운반하는 [FragmentType]·content 가용 상태만 본다.
 * attachment 의 바이트나 URL 을 요구하지 않는다 — 애초에 도메인 조각([MessageFragment])에는 첨부 메타데이터조차 없고,
 * 이 분류기는 "텍스트가 관찰됐는가" 와 "type 이 가벼운 반응성인가" 만으로 판정한다. 따라서 원문 파일을 내려받을 경로가
 * 타입 수준에서 존재하지 않는다.
 */
object ReactiveFragmentClassifier {
    /** 텍스트가 실제로 관찰됐는가(Available 이며 공백 아님). 미허용/빈이면 false. */
    private fun hasObservedText(fragment: MessageFragment): Boolean =
        (fragment.content as? MessageContent.Available)?.text?.isNotBlank() == true

    /**
     * [fragment] 를 반응성 분류한다(원문 파일 비다운로드 — type·content 가용 상태만 본다).
     *
     * - 관찰된 텍스트가 있으면 [ReactiveFragmentKind.TEXTUAL] (반응성 아님 — 일반 발화 경로).
     * - 텍스트 없는 [FragmentType.EMOJI]/[FragmentType.SYSTEM] 은 [ReactiveFragmentKind.REACTIVE] (이모지/스티커/시스템
     *   성 가벼운 반응) — 같은 작성자의 직전 버스트에 가볍게 붙일 수 있는 후보다.
     * - 텍스트 없는 [FragmentType.NORMAL] (첨부만 있는 일반 메시지)은 [ReactiveFragmentKind.STANDALONE] — 독립 행동으로
     *   본다(텍스트 없는 첨부 공유는 별도 발화 단위).
     */
    fun classify(fragment: MessageFragment): ReactiveFragmentKind =
        when {
            hasObservedText(fragment) -> ReactiveFragmentKind.TEXTUAL
            fragment.type == FragmentType.NORMAL -> ReactiveFragmentKind.STANDALONE
            else -> ReactiveFragmentKind.REACTIVE
        }

    /**
     * 이 조각이 **다른 작성자의 OPEN 버스트를 종료시키지 않는** 가벼운 반응인가(T007 와 정합).
     * [ReactiveFragmentKind.REACTIVE] 만 가벼운 개입으로 본다 — [FragmentType.endsOtherAuthorBurst] 와 일치한다.
     */
    fun isLightTouch(fragment: MessageFragment): Boolean = classify(fragment) == ReactiveFragmentKind.REACTIVE
}

/**
 * 반응성 조각 분류 결과(순수 도메인 enum). segmenter 가 이 값을 참고하되 경계 결정은 baseline 규칙이 내린다(T012 비강제).
 */
enum class ReactiveFragmentKind {
    /** 관찰된 텍스트가 있는 일반 발화 조각(반응성 아님). */
    TEXTUAL,

    /** 텍스트 없는 이모지/스티커/시스템 성 가벼운 반응 — 직전 버스트에 붙일 수 있는 후보. */
    REACTIVE,

    /** 텍스트 없는 일반 첨부 공유 — 독립 발화 단위로 본다. */
    STANDALONE,
}
