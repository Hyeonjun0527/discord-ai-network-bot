package com.discordassistant.central.participation.adapter.inbound.web

import com.discordassistant.central.participation.domain.model.state.ChannelCultureState
import com.discordassistant.central.participation.domain.service.state.SpeakingSaturationCalculator

/**
 * 관리자용 사회 상태 **설명 read-only DTO**(NEXA-P06-T022). 채널 문화([ChannelCultureState])와 NEXA 발화 포화도를
 * **원문·민감 추론 없이** 사람이 읽을 수 있는 설명으로 환원한다(observable-state-policy 체크리스트 #7 — 관리자에게
 * 설명 가능, 금지 추론 부재).
 *
 * **acceptance(T022) — 숫자의 의미와 표본 부족 상태가 UI 에 드러난다**: 각 지표는 값([value])뿐 아니라 의미 설명
 * ([meaning])과 표본 충분 여부([hasSufficientSample])를 함께 담는다. 표본이 부족하면 UI 가 "통계 불충분"을 드러내
 * 과신을 막는다([sampleNotice]).
 *
 * read-only: 이 DTO 는 응답 전용이며 상태를 변경하지 않는다. 원문/심리 라벨/금지 추론(기분·성격·정치 등)을 담지 않는다.
 */
data class SocialStateDescriptionDto(
    /** guild 가명(원문 snowflake 아님). cross-guild 연결 금지. */
    val guildPseudonym: String,
    /** channel 가명(원문 snowflake 아님). */
    val channelPseudonym: String,
    /** 표본 충분 여부(사람 burst 가 1개 이상). false 면 아래 지표를 신뢰하지 말 것. */
    val hasSufficientSample: Boolean,
    /** 표본 부족 안내 문구(부족할 때만 채움). UI 에 "통계 불충분"을 드러낸다. */
    val sampleNotice: String?,
    /** 채널 문화 설명 지표 목록(각각 의미·표본 상태 포함). */
    val cultureMetrics: List<DescribedMetric>,
    /** NEXA 발화 포화도 설명 지표(채널을 과점하는지). */
    val nexaSaturation: DescribedMetric,
) {
    /**
     * 설명이 붙은 지표 한 건(NEXA-P06-T022). 값([value])과 그 **의미 설명**([meaning]), 표본 충분 여부
     * ([hasSufficientSample])를 함께 담아 숫자가 무엇을 뜻하는지 UI 에 드러낸다.
     */
    data class DescribedMetric(
        /** 지표 이름(예: 분당 사람 burst). */
        val label: String,
        /** 지표 값(정규화/비율/카운트). */
        val value: Double,
        /** 이 값이 무엇을 관찰한 것인지 사람이 읽는 설명(금지 추론 없음 — 관찰 사실로 환원). */
        val meaning: String,
        /** 이 지표를 신뢰할 표본이 있는가. false 면 UI 가 "표본 부족"을 표시. */
        val hasSufficientSample: Boolean,
    )

    companion object {
        /**
         * 채널 문화 + NEXA 발화 포화도를 설명 DTO 로 환원한다(순수 매핑·무상태). [nexaBurstCount] 는 같은 관측 창의
         * NEXA burst 수다(포화도 입력). 표본 부족 시 안내 문구를 채운다.
         */
        fun of(
            guildPseudonym: String,
            channelPseudonym: String,
            culture: ChannelCultureState,
            nexaBurstCount: Int,
        ): SocialStateDescriptionDto {
            val sufficient = culture.hasSample
            val saturation = SpeakingSaturationCalculator.saturation(nexaBurstCount, culture.humanBurstCount)
            return SocialStateDescriptionDto(
                guildPseudonym = guildPseudonym,
                channelPseudonym = channelPseudonym,
                hasSufficientSample = sufficient,
                sampleNotice =
                    if (sufficient) null else "표본 부족 — 사람 burst 가 없어 채널 문화 통계를 신뢰할 수 없습니다.",
                cultureMetrics =
                    listOf(
                        DescribedMetric(
                            label = "분당 사람 burst",
                            value = culture.humanBurstsPerMinute,
                            meaning = "사람(비봇·비옵트아웃)이 1분당 만든 발화 묶음 수 — 채널이 얼마나 활발한지의 관찰 빈도.",
                            hasSufficientSample = sufficient,
                        ),
                        DescribedMetric(
                            label = "평균 burst 크기",
                            value = culture.averageBurstSize,
                            meaning = "사람 발화 묶음당 메시지 조각 수(원문 아님 — 개수만).",
                            hasSufficientSample = sufficient,
                        ),
                        DescribedMetric(
                            label = "reaction 비율",
                            value = culture.reactionRatio,
                            meaning = "발화 대비 reaction 이 붙은 비율 — 채널이 reaction 으로 소통하는 정도.",
                            hasSufficientSample = sufficient,
                        ),
                        DescribedMetric(
                            label = "mention 응답 비율",
                            value = culture.mentionResponseRatio,
                            meaning = "mention/멘션 후 응답이 온 비율 — 응답 기대의 채널 baseline.",
                            hasSufficientSample = sufficient,
                        ),
                    ),
                nexaSaturation =
                    DescribedMetric(
                        label = "NEXA 발화 포화도",
                        value = saturation,
                        meaning = "최근 창에서 NEXA 의 발화 점유율 — 높으면 NEXA 가 채널을 과점한다는 관찰 신호(행동 결정 아님).",
                        // 포화도는 NEXA·사람 burst 가 모두 0 이면 표본 부족.
                        hasSufficientSample = nexaBurstCount + culture.humanBurstCount > 0,
                    ),
            )
        }
    }
}
