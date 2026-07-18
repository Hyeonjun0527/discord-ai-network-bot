package com.discordassistant.central.actionruntime.adapter.outbound.multiresponse

import com.discordassistant.central.actionruntime.application.content.SpeechBurstContentCodec
import com.discordassistant.central.actionruntime.domain.model.Bubble
import com.discordassistant.central.actionruntime.domain.model.BurstPlan
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 기존 multiresponse(의사-스트리밍) ↔ 새 [BurstPlan] 매핑 어댑터(NEXA-P13-T019, actionruntime adapter).
 *
 * 기존 pseudo-streaming 은 **한 메시지를 제자리 편집**하는 단일 전송(action 1개)이다 — 이를 버스트 버블 N개로
 * 펼치면 전송 action 수가 늘어 정책(P04 burst 수)을 깨뜨린다. 따라서 이 어댑터는 의사-스트림 결과를 **버블 1개**
 * (최종 본문)로 매핑한다.
 *
 * **acceptance(T019) — 기존 pseudo-streaming API 가 정책 action 수를 늘리지 않는다**:
 * - [fromPseudoStream]: 편집 스냅샷이 몇 개든 [BurstPlan.bubbleCount] == 1.
 * - 의도적 멀티 버블(정책이 명시한 burst)은 [fromBubbles] 로 별도 구성한다 — 이때만 action 수가 버블 수와 같다.
 *
 * actionruntime adapter 레이어 — 도메인 [BurstPlan] 만 만든다(다른 도메인 구현·JDA 미참조, ArchUnit 경계 준수).
 */
@Component
class MultiResponseBurstAdapter {
    /**
     * 기존 의사-스트리밍 결과를 단일 버블 [BurstPlan] 으로 매핑한다(action 1개 — 정책 action 수 불변, T019).
     * [speechPlanRef] 는 speech 가 만든 최종 본문 참조(편집 스냅샷이 아니라 최종 1건만 전송).
     */
    fun fromPseudoStream(speechPlanRef: String): BurstPlan = BurstPlan.single(speechPlanRef)

    /**
     * 정책이 명시한 멀티 버블 burst 를 [BurstPlan] 으로 구성한다([refs] 순서대로 버블, 마지막을 뺀 각 버블 뒤에
     * [gap] 간격). action 수 = 버블 수(정책이 의도한 수). 단일 [refs] 면 [fromPseudoStream] 과 동일(버블 1개).
     */
    fun fromBubbles(
        refs: List<String>,
        gap: Duration = DEFAULT_BURST_GAP,
    ): BurstPlan {
        require(refs.isNotEmpty()) { "refs 는 최소 1개여야 한다" }
        val bubbles =
            refs.mapIndexed { index, ref ->
                Bubble(
                    index = index,
                    speechPlanRef = ref,
                    gapAfter = if (index == refs.lastIndex) Duration.ZERO else gap,
                )
            }
        return BurstPlan(bubbles)
    }

    /** 저장된 speech content의 버블 수만큼 같은 본문 참조를 인덱스별 전송 계획으로 펼친다. */
    fun fromPersistedSpeech(
        speechPlanRef: String,
        storedContent: String?,
    ): BurstPlan {
        val count = storedContent?.let(SpeechBurstContentCodec::decode)?.size ?: 1
        if (count <= 1) return fromPseudoStream(speechPlanRef)
        return fromBubbles(List(count) { speechPlanRef })
    }

    companion object {
        /** 기본 버블 간 간격(P12 간격 모델의 보수적 기본값 — 너무 빠른 연속 전송 방지). */
        val DEFAULT_BURST_GAP: Duration = Duration.ofMillis(1_200)
    }
}
