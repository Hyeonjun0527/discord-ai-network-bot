package com.discordassistant.central.participation.domain.model.state

/**
 * current interest(현재 관심) 상태(NEXA-P06-T013, 순수 도메인 값 객체·불변).
 *
 * NEXA identity kernel 의 **관심 tag** 와 **현재 scene 주제 적합도**를 결합해 지금 이 장면에 NEXA 가 얼마나 관심을
 * 둘지를 [0,1] 로 요약한다. 사람을 추론하지 않는 NEXA 자기 상태이며, 행동을 결정하지 않는다(정책 입력).
 *
 * **acceptance(T013) — GLM 의 자유 텍스트 자기설명을 상태로 저장하지 않는다**:
 * 입력은 명시적 관심 tag([identityInterestTags]: 닫힌 라벨 집합)와 현재 scene tag([sceneTopicTags]) + 적합도
 * 가중치뿐이다. GLM 이 만든 자유 텍스트("나는 ~에 관심 있다" 같은 서술)를 필드로 담지 않는다([freeTextSelfDescription]
 * 항상 false 가드). 라벨 매칭으로만 계산한다(결정론·설명 가능).
 *
 * 순수성: Spring/JPA/JDA·GLM 타입 미참조.
 */
data class TopicInterest(
    /** identity kernel 이 가진 NEXA 의 관심 tag 라벨 집합(정적 — ainetwork/identity 소유, 여기선 읽기 복제 아님 입력). */
    val identityInterestTags: Set<String>,
    /** 현재 scene 의 명시적 주제 tag 집합(conversation 이 부여한 라벨). */
    val sceneTopicTags: Set<String>,
) {
    init {
        require(identityInterestTags.all { it.isNotBlank() }) { "관심 tag 라벨은 비어 있을 수 없다" }
        require(sceneTopicTags.all { it.isNotBlank() }) { "scene 주제 tag 라벨은 비어 있을 수 없다" }
        require(!freeTextSelfDescription) {
            "TopicInterest 는 GLM 자유 텍스트 자기설명을 상태로 담지 않는다(acceptance T013)"
        }
    }

    /**
     * 자유 텍스트 자기설명 포함 여부 — **항상 false**. 닫힌 라벨 매칭만 한다는 불변식의 가드다(acceptance T013).
     */
    val freeTextSelfDescription: Boolean
        get() = false

    /** 관심 tag 와 scene tag 의 겹침(공통 라벨). 관심도의 근거(설명 가능). */
    val matchedTags: Set<String>
        get() = identityInterestTags intersect sceneTopicTags

    /**
     * 현재 관심도 [0,1]. scene tag 중 NEXA 관심 tag 와 겹치는 비율(Jaccard 와 달리 scene 기준 — "지금 장면이 내
     * 관심과 얼마나 맞나"). scene tag 가 없으면 0(관심 둘 주제 없음).
     */
    val interest: Double
        get() = if (sceneTopicTags.isEmpty()) 0.0 else matchedTags.size.toDouble() / sceneTopicTags.size.toDouble()

    companion object {
        /** scene tag 없는 빈 상태(관심도 0). */
        fun idle(identityInterestTags: Set<String>): TopicInterest =
            TopicInterest(identityInterestTags = identityInterestTags, sceneTopicTags = emptySet())
    }
}
