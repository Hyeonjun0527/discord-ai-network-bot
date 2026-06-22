package com.discordassistant.central.participation.domain.model.state

import java.time.Duration

/**
 * 채널 문화 상태(NEXA-P06-T003, 순수 도메인 값 객체·불변).
 *
 * 한 채널의 **사람들이 보이는 관찰 가능한 행동 통계**를 요약한다 — 분당 human burst rate, 평균 burst 크기,
 * 평균 reply delay, reaction 비율, mention 응답 비율. 특정 개인을 프로파일링하지 않고 채널 단위 집계만 한다
 * (observable-state-policy 허용: 상호작용 빈도·템포·reciprocity 관찰).
 *
 * **acceptance(T003) — 봇 발화와 옵트아웃 데이터가 통계에서 제외된다**:
 * 이 모델은 이미 **필터링된 입력**으로 만들어진다는 계약을 [from] 의 시그니처와 KDoc 으로 강제한다. 집계 전에
 * 호출자(application)가 봇/옵트아웃 burst 를 제외하고, [humanBurstCount] 는 "사람·비옵트아웃" burst 수만 의미한다.
 * [includesBotOrOptOut] 가 항상 false 임을 [init] 에서 가드해 회귀를 즉시 드러낸다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 java.time 만 쓴다.
 */
data class ChannelCultureState(
    val scope: ChannelScope,
    /** 관측 창 동안 사람(비봇·비옵트아웃)이 만든 burst 수. 0 이면 통계 불충분(아래 비율은 0/null). */
    val humanBurstCount: Int = 0,
    /** 사람 burst 의 분당 발생률(밀도). 0 이면 조용한 채널. */
    val humanBurstsPerMinute: Double = 0.0,
    /** 사람 burst 의 평균 크기(burst 당 메시지 조각 수). 원문 아님 — 개수만. */
    val averageBurstSize: Double = 0.0,
    /** mention/reply 후 사람이 응답하기까지의 평균 지연. null 이면 표본 없음. */
    val averageReplyDelay: Duration? = null,
    /** burst 대비 reaction 이 붙은 비율 [0,1]. 채널이 reaction 으로 소통하는 정도. */
    val reactionRatio: Double = 0.0,
    /** NEXA·사람이 mention 받은 뒤 응답이 온 비율 [0,1]. 응답 기대(T008) 의 채널 baseline. */
    val mentionResponseRatio: Double = 0.0,
) {
    init {
        require(humanBurstCount >= 0) { "humanBurstCount 는 음수일 수 없다" }
        require(humanBurstsPerMinute >= 0.0) { "humanBurstsPerMinute 는 음수일 수 없다" }
        require(averageBurstSize >= 0.0) { "averageBurstSize 는 음수일 수 없다" }
        require(reactionRatio in 0.0..1.0) { "reactionRatio 는 [0,1] 범위여야 한다" }
        require(mentionResponseRatio in 0.0..1.0) { "mentionResponseRatio 는 [0,1] 범위여야 한다" }
        require(!includesBotOrOptOut) {
            "ChannelCultureState 는 봇/옵트아웃 데이터를 포함하지 않는다(acceptance T003)"
        }
    }

    /**
     * 봇/옵트아웃 데이터 포함 여부 — 이 모델은 **항상 false**. 입력은 호출자가 필터링한 사람·비옵트아웃 burst 여야
     * 한다는 불변식의 가드다(acceptance T003).
     */
    val includesBotOrOptOut: Boolean
        get() = false

    /** 통계로 쓰기에 충분한 표본이 있는가(사람 burst 가 1개 이상). */
    val hasSample: Boolean
        get() = humanBurstCount > 0

    companion object {
        /** 표본 없는 초기 상태(주어진 스코프). */
        fun empty(scope: ChannelScope): ChannelCultureState = ChannelCultureState(scope = scope)
    }
}
