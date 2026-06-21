package com.discordassistant.central.conversation.domain.model.burst

import com.discordassistant.central.conversation.domain.model.event.AuthorId
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.MessageContent
import com.discordassistant.central.conversation.domain.model.event.MessageId
import java.time.Instant

/**
 * 버스트 분할의 입력 단위(NEXA-P04-T001, 순수 도메인). 한 메시지 관찰 사실을 버스트 segmenter 가 소비하기 쉬운
 * 최소 불변 모델로 추린 것이다 — segmenter 가 경계를 판정하는 데 필요한 신호(작성자·시각·reply·thread·type)만 담는다.
 *
 * [com.discordassistant.central.conversation.domain.model.event.MessageCreated] 봉투 전체가 아니라 **버스트 입력에
 * 필요한 부분집합**을 운반한다(KISS). 봉투는 멱등성/계보/PII 등급까지 갖지만 segmenter 는 그 일부만 본다 — 결합을
 * 줄이려고 별도 입력 모델로 둔다(segmenter 가 봉투 전체 모양에 묶이지 않는다).
 *
 * 순수성: conversation.domain 규칙(NexaArchitectureTest.nexaDomainsArePure)을 위해 Spring/JPA/JDA/adapter 타입을
 * 일절 참조하지 않는다. 식별자는 순수 도메인 value type([MessageId]/[AuthorId]/[ChannelId])으로만 운반한다.
 *
 * **acceptance(T001) — 동등성·정렬**: 같은 필드면 [equals]/[hashCode] 가 동일(데이터 클래스). 정렬은 미래 이벤트를
 * 참조하지 않고 결정론적이어야 하므로 [chronology] Comparator 가 `sourceSequence → occurredAt → messageId` 우선순위로
 * **전순서**를 제공한다 — 동일 sourceSequence·동일 occurredAt 라도 messageId 로 안정 타이브레이크해 정렬이 흔들리지 않는다.
 */
data class MessageFragment(
    /** 이 조각의 메시지 식별자(reply/edit/delete 가 가리키는 대상 키, 정렬 최종 타이브레이크). */
    val messageId: MessageId,
    /** 작성자(버스트는 같은 작성자의 연속 조각을 묶는다 — author 가 1차 그룹 키). */
    val authorId: AuthorId,
    /** 이 조각이 속한 채널(thread 직속이면 [threadId] 가 별도로 채워진다). */
    val channelId: ChannelId,
    /** 같은 채널 내 결정론적 순서 키(어댑터 수신 순번). 정렬 1차 키. */
    val sourceSequence: Long,
    /** Discord 에서 실제로 일어난 시각(원천 타임스탬프). gap 계산·정렬 2차 키. */
    val occurredAt: Instant,
    /** 메시지 텍스트의 가용 상태(미허용/빈/사용 가능 명시 구분; 단일 null 금지). */
    val content: MessageContent,
    /** 답글 대상 메시지(없으면 null). reply target 변경 경계(T008)의 근거. */
    val replyTo: MessageId?,
    /** 이 조각이 속한 스레드(채널 직속이면 null). thread 경계(T009)의 근거. */
    val threadId: ChannelId?,
    /** 조각 종류(일반 메시지/시스템 메시지 등). 개입 종료 규칙(T007)의 type 분기 근거. */
    val type: FragmentType,
) {
    /**
     * 이 조각이 흐른 위치 키 — 같은 채널이라도 스레드가 다르면 다른 위치다(thread 경계 T009).
     * 채널 직속이면 [channelId], 스레드 안이면 [threadId] 로 위치를 결정한다.
     */
    val locationKey: BurstLocationKey
        get() = BurstLocationKey(channelId = channelId, threadId = threadId)

    companion object {
        /**
         * 조각의 결정론적 전순서 비교자(미래 이벤트 비참조).
         *
         * 1차 [sourceSequence](수신 순번, 단조 증가) → 2차 [occurredAt](발생 시각) → 3차 [messageId](최종 타이브레이크).
         * 3단계라 어떤 두 조각도 비교 결과가 0 이 아니며(messageId 유니크 전제) 정렬이 결정론적이다.
         */
        val chronology: Comparator<MessageFragment> =
            compareBy<MessageFragment> { it.sourceSequence }
                .thenBy { it.occurredAt }
                .thenBy { it.messageId.value }
    }
}

/**
 * 조각 종류(순수 도메인 enum). 개입 종료 규칙(T007)이 type 에 따라 OPEN 버스트 종료 여부를 다르게 적용한다.
 *
 * 이모지/시스템 메시지는 "실제 발화" 가 아닐 수 있어 다른 작성자 개입으로 칠지 말지가 갈린다([endsOtherAuthorBurst]).
 */
enum class FragmentType(
    /** 다른 작성자가 이 type 의 메시지를 보냈을 때 기존 작성자의 OPEN 버스트를 종료시키는가(T007 기본 규칙). */
    val endsOtherAuthorBurst: Boolean,
) {
    /** 일반 텍스트 메시지 — 다른 작성자가 보내면 기존 OPEN 버스트를 종료한다. */
    NORMAL(endsOtherAuthorBurst = true),

    /** 이모지/리액션성 짧은 메시지 — 가벼운 개입이라 기본적으로 기존 버스트를 종료하지 않는다. */
    EMOJI(endsOtherAuthorBurst = false),

    /** 시스템 메시지(입장/핀 등) — 발화가 아니므로 기존 버스트를 종료하지 않는다. */
    SYSTEM(endsOtherAuthorBurst = false),
}

/**
 * 버스트가 흐르는 **위치** 식별 키(순수 도메인 value object). 같은 작성자라도 위치가 다르면 다른 버스트다(T003/T009).
 *
 * Discord 에서 스레드 메시지와 부모 채널 메시지는 같은 `channelId` 근처여도 별개 대화 흐름이다 — [threadId] 가 다르면
 * (한쪽은 null) 위치가 다르다. 데이터 클래스라 동등성으로 위치 일치를 결정론적으로 비교한다.
 */
data class BurstLocationKey(
    val channelId: ChannelId,
    /** 스레드 안이면 스레드 식별자, 부모 채널 직속이면 null. null 과 비-null 은 다른 위치다. */
    val threadId: ChannelId?,
)
