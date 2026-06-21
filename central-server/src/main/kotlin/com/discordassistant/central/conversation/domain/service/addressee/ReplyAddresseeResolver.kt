package com.discordassistant.central.conversation.domain.service.addressee

import com.discordassistant.central.conversation.domain.model.addressee.AddresseeCandidate
import com.discordassistant.central.conversation.domain.model.addressee.AddresseeDistribution
import com.discordassistant.central.conversation.domain.model.addressee.AddresseeEvidence
import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.MessageId

/**
 * 직접 reply 대상 resolver(NEXA-P05-T005, 순수 함수). Discord reply 가 있으면 그 대상 member/message 에
 * 높은 확률을 준다 — reply 는 "누구에게" 의 가장 강한 명시 신호다.
 *
 * **acceptance(T005) — self-reply·삭제 target fallback 명시**:
 * - 일반 reply: 대상 member 에 [config].replyConfidence, 나머지는 none.
 * - self-reply(작성자 == 대상 작성자): 자기 자신에게 답하는 건 보통 "이어 말하기" 라 addressee 신호가 약하다 —
 *   확률을 [config].selfReplyConfidence 로 낮추고 [AddresseeEvidence.SELF_REPLY] 를 남긴다.
 * - 삭제된 target(target 작성자 미상 = null): 대상을 특정할 수 없으므로 후보를 만들지 않고 none 으로 fallback,
 *   [AddresseeEvidence.DELETED_REPLY_TARGET] 를 남긴다(연결 사실은 evidence 로 보존).
 *
 * 순수성: Spring/JPA/JDA 미참조. 상태 없음(모든 입력을 인자로 받는다).
 */
object ReplyAddresseeResolver {
    const val VERSION: String = "reply-v1"

    /**
     * reply 신호로부터 addressee 분포를 만든다.
     *
     * @param speaker 발화한 사람(self-reply 판정 기준).
     * @param replyToMessage reply 가 가리킨 대상 메시지 키.
     * @param replyTargetAuthor 대상 메시지 작성자 — 삭제되어 알 수 없으면 null(fallback).
     */
    fun resolve(
        speaker: AuthorId,
        replyToMessage: MessageId,
        replyTargetAuthor: AuthorId?,
        config: ReplyResolverConfig = ReplyResolverConfig.DEFAULT,
    ): AddresseeDistribution {
        // 삭제된 target — 대상 특정 불가, none fallback(연결은 evidence 로 보존).
        if (replyTargetAuthor == null) {
            return AddresseeDistribution.none(
                resolverVersion = VERSION,
                evidence = setOf(AddresseeEvidence.DIRECT_REPLY, AddresseeEvidence.DELETED_REPLY_TARGET),
            )
        }

        val isSelfReply = replyTargetAuthor == speaker
        val confidence = if (isSelfReply) config.selfReplyConfidence else config.replyConfidence
        val evidence =
            if (isSelfReply) {
                setOf(AddresseeEvidence.DIRECT_REPLY, AddresseeEvidence.SELF_REPLY)
            } else {
                setOf(AddresseeEvidence.DIRECT_REPLY)
            }

        return AddresseeDistribution(
            candidates =
                listOf(
                    AddresseeCandidate(
                        member = replyTargetAuthor,
                        probability = confidence,
                        message = replyToMessage,
                    ),
                ),
            noneProbability = 1.0 - confidence,
            resolverVersion = VERSION,
            evidence = evidence,
        )
    }
}

/** 직접 reply resolver 설정(주입). */
data class ReplyResolverConfig(
    /** 일반 reply 대상에 부여하는 확신도. */
    val replyConfidence: Double = 0.9,
    /** self-reply 시 낮춘 확신도("이어 말하기" 라 addressee 신호 약함). */
    val selfReplyConfidence: Double = 0.3,
) {
    init {
        require(replyConfidence in 0.0..1.0) { "replyConfidence 는 [0,1] 범위여야 한다" }
        require(selfReplyConfidence in 0.0..1.0) { "selfReplyConfidence 는 [0,1] 범위여야 한다" }
    }

    companion object {
        val DEFAULT: ReplyResolverConfig = ReplyResolverConfig()
    }
}
