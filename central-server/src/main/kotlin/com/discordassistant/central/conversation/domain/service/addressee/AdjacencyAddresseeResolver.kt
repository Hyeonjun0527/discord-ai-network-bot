package com.discordassistant.central.conversation.domain.service.addressee

import com.discordassistant.central.conversation.domain.model.addressee.AddresseeCandidate
import com.discordassistant.central.conversation.domain.model.addressee.AddresseeDistribution
import com.discordassistant.central.conversation.domain.model.addressee.AddresseeEvidence
import com.discordassistant.central.conversation.domain.model.event.AuthorId

/**
 * 인접 턴 대상 resolver(NEXA-P05-T007, 순수 함수). reply/mention 같은 명시 신호가 없을 때, **최근 화자·질문
 * 형태·교대 패턴** 으로 addressee 분포를 추정한다 — 약한 신호뿐이라 결과는 항상 분포다.
 *
 * **acceptance(T007) — 단일 정답 대신 none 포함 확률분포**:
 * - 직전 화자([recentSpeaker])가 후보가 되되, 자유 발화는 모호하므로 base 확률만 준다([config].recentSpeakerBase).
 * - 질문 형태([isQuestion])면 "누군가에게 묻는" 것이라 직전 화자 가중을 [config].questionBoost 만큼 올린다.
 * - 화자 교대([alternating], A↔B turn-taking)면 대화 상대가 분명해져 가중을 [config].alternationBoost 만큼 올린다.
 * - 어떤 경우에도 [AddresseeDistribution.noneProbability] 가 남는다 — 단일 정답으로 단정하지 않는다.
 * - 후보가 없거나(직전 화자 == 발화자) 신호가 약하면 none 비중이 커진다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 상태 없음.
 */
object AdjacencyAddresseeResolver {
    const val VERSION: String = "adjacency-v1"

    /**
     * 인접 턴 신호로부터 addressee 분포를 만든다.
     *
     * @param speaker 현재 발화자.
     * @param recentSpeaker 직전 화자(없으면 null — 대화 첫 발화 등).
     * @param isQuestion 발화가 질문 형태인가(상류 feature 가 판정; 도메인은 boolean 만 받는다).
     * @param alternating 직전까지 화자 교대(turn-taking) 패턴이었는가.
     */
    fun resolve(
        speaker: AuthorId,
        recentSpeaker: AuthorId?,
        isQuestion: Boolean,
        alternating: Boolean,
        config: AdjacencyResolverConfig = AdjacencyResolverConfig.DEFAULT,
    ): AddresseeDistribution {
        // 직전 화자가 없거나 자기 자신이면 후보 없음 — none 분포(혼잣말/대화 시작).
        if (recentSpeaker == null || recentSpeaker == speaker) {
            return AddresseeDistribution.none(resolverVersion = VERSION)
        }

        val evidence = mutableSetOf(AddresseeEvidence.RECENT_SPEAKER)
        var probability = config.recentSpeakerBase
        if (isQuestion) {
            probability += config.questionBoost
            evidence += AddresseeEvidence.QUESTION_FORM
        }
        if (alternating) {
            probability += config.alternationBoost
            evidence += AddresseeEvidence.ALTERNATION
        }
        // 항상 none 여지를 남긴다 — 단일 정답 단정 금지(acceptance). cap < 1.0.
        probability = probability.coerceAtMost(config.maxProbability)

        return AddresseeDistribution(
            candidates = listOf(AddresseeCandidate(member = recentSpeaker, probability = probability)),
            noneProbability = 1.0 - probability,
            resolverVersion = VERSION,
            evidence = evidence,
        )
    }
}

/** 인접 턴 resolver 설정(주입). 모든 신호를 합쳐도 [maxProbability] 를 못 넘어 none 여지가 남는다. */
data class AdjacencyResolverConfig(
    /** 직전 화자에 부여하는 base 확률. */
    val recentSpeakerBase: Double = 0.3,
    /** 질문 형태일 때 추가 가중. */
    val questionBoost: Double = 0.2,
    /** 화자 교대(turn-taking)일 때 추가 가중. */
    val alternationBoost: Double = 0.2,
    /** 후보 확률 상한 — 1.0 미만이라 어떤 경우에도 none 여지가 남는다(단일 정답 금지). */
    val maxProbability: Double = 0.7,
) {
    init {
        require(recentSpeakerBase in 0.0..1.0) { "recentSpeakerBase 는 [0,1] 범위여야 한다" }
        require(questionBoost in 0.0..1.0) { "questionBoost 는 [0,1] 범위여야 한다" }
        require(alternationBoost in 0.0..1.0) { "alternationBoost 는 [0,1] 범위여야 한다" }
        require(maxProbability in 0.0..1.0 && maxProbability < 1.0) {
            "maxProbability 는 [0,1) 범위여야 none 여지가 남는다: $maxProbability"
        }
    }

    companion object {
        val DEFAULT: AdjacencyResolverConfig = AdjacencyResolverConfig()
    }
}
