package com.discordassistant.central.conversation.domain.service.addressee

import com.discordassistant.central.conversation.domain.model.addressee.AddresseeDistribution
import com.discordassistant.central.conversation.domain.model.addressee.AddresseeEvidence

/**
 * 그룹 전체 발화 판정(NEXA-P05-T008, 순수 함수). 공지·일반 질문·다중 mention 처럼 **특정인이 아니라 그룹
 * 전체에게** 향한 발화를 가려낸다. group-addressed 면 특정 사용자에 귀속하지 않고 none(그룹)으로 본다.
 *
 * **acceptance(T008) — 모호한 경우 특정 사용자에게 과도하게 귀속하지 않는다**:
 * - [isGroupAddressed] 가 참이면 [asGroupDistribution] 으로 **none = 1.0** 분포를 반환한다 — 어떤 특정
 *   후보에게도 확률을 주지 않는다(그룹 발화는 한 명에게 귀속될 수 없다).
 * - everyone/role broadcast([hasBroadcastMention]), 일반 질문([isGeneralQuestion]), 임계치 이상 다중
 *   mention([directMentionCount] ≥ [config].multiMentionThreshold) 중 하나라도 참이면 그룹으로 본다.
 *
 * 순수성: Spring/JPA/JDA 미참조. 상태 없음. 원문이 아니라 상류 feature 가 뽑은 boolean/count 만 받는다.
 */
object GroupAddressClassifier {
    const val VERSION: String = "group-v1"

    /**
     * 그룹 전체 발화인가 판정한다.
     *
     * @param hasBroadcastMention @everyone/@here 또는 role mention 존재(그룹 알림).
     * @param isGeneralQuestion 특정 대상 없는 일반 질문 형태(상류 feature 판정).
     * @param directMentionCount 본문 직접 mention 수 — 임계치 이상이면 그룹.
     */
    fun isGroupAddressed(
        hasBroadcastMention: Boolean,
        isGeneralQuestion: Boolean,
        directMentionCount: Int,
        config: GroupAddressConfig = GroupAddressConfig.DEFAULT,
    ): Boolean {
        require(directMentionCount >= 0) { "directMentionCount 는 음수일 수 없다" }
        return hasBroadcastMention ||
            isGeneralQuestion ||
            directMentionCount >= config.multiMentionThreshold
    }

    /**
     * 그룹 발화의 분포 — none = 1.0(특정인 귀속 없음), evidence [AddresseeEvidence.GROUP_ADDRESSED].
     * 모호함을 특정 사용자에게 떠넘기지 않는 안전한 기본형이다(acceptance).
     */
    fun asGroupDistribution(): AddresseeDistribution =
        AddresseeDistribution.none(
            resolverVersion = VERSION,
            evidence = setOf(AddresseeEvidence.GROUP_ADDRESSED),
        )
}

/** 그룹 전체 발화 판정 설정(주입). [multiMentionThreshold] 이상의 직접 mention 은 그룹으로 본다. */
data class GroupAddressConfig(
    /** 이 수 이상 직접 mention 하면 특정인이 아니라 그룹 호출로 본다. */
    val multiMentionThreshold: Int = 3,
) {
    init {
        require(multiMentionThreshold >= 2) { "multiMentionThreshold 는 최소 2 이상이어야 한다(1명은 직접 호출)" }
    }

    companion object {
        val DEFAULT: GroupAddressConfig = GroupAddressConfig()
    }
}
