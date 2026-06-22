package com.discordassistant.central.conversation.application.burst

import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.burst.BurstStatus
import com.discordassistant.central.conversation.domain.model.burst.MessageFragment
import com.discordassistant.central.conversation.domain.model.burst.UtteranceBurst
import com.discordassistant.central.conversation.domain.model.event.MessageContent
import com.discordassistant.central.conversation.domain.model.event.MessageDeleted
import com.discordassistant.central.conversation.domain.model.event.MessageId
import com.discordassistant.central.conversation.domain.model.event.MessageUpdated

/**
 * 버스트 교정 정책(NEXA-P04-T014/T015, application). finalize **전** 수정/삭제는 OPEN 버스트의 fragment 를 갱신하고,
 * finalize **후** 수정/삭제는 과거 버스트 ID 를 바꾸지 않은 채 CORRECTED projection 으로 전이한다.
 *
 * application 레이어 소속 — 순수 도메인 타입([UtteranceBurst]/[MessageUpdated]/[MessageDeleted])만 보고 Spring/JPA/JDA
 * 를 참조하지 않는다. 부수효과(저장·발행)는 호출자가 결과([BurstCorrection])를 적용해 수행한다(도메인 결정과 분리).
 *
 * **acceptance(T014) — 과거 burst ID 불변·revision provenance 보존**: finalize 후 수정은 [UtteranceBurst.correct]
 * (FINALIZED→CORRECTED)로 같은 [BurstId] 를 유지하고, [BurstCorrection.Corrected] 가 revision provenance(어느 메시지의
 * 몇 번째 편집이 교정을 유발했는지)를 함께 운반한다 — burstId 가 절대 재계산되지 않는다.
 *
 * **acceptance(T015) — 삭제 content 비잔존·lineage 삭제**: 삭제는 fragment content 를 [MessageContent.Unavailable.Empty]
 * 로 치환해 combined text 에 원문이 남지 않게 하고, 그 메시지가 버스트의 마지막 남은 fragment 였으면 빈 버스트가 되어
 * [BurstCorrection.Emptied] 로 lineage 삭제(이미 학습 export 된 경우 CORRECTED + 삭제)를 신호한다.
 */
class BurstCorrectionPolicy {
    /**
     * 한 메시지 [edit] 이 [burst] 에 미치는 교정 효과를 결정한다(T014). [edit] 대상 메시지가 버스트에 없으면
     * [BurstCorrection.NotApplicable].
     *
     * - OPEN 버스트면 해당 fragment 의 content 를 새 편집 내용으로 갱신([BurstCorrection.FragmentUpdated]).
     * - FINALIZED 버스트면 burstId 를 유지한 채 CORRECTED 로 전이하고 revision provenance 를 운반
     *   ([BurstCorrection.Corrected]).
     * - 이미 CORRECTED 면 멱등 — 더 큰 revision 만 provenance 를 갱신([BurstCorrection.Corrected]), 같거나 과거
     *   revision 은 [BurstCorrection.NotApplicable](stale 무시).
     */
    fun applyEdit(
        burst: UtteranceBurst,
        edit: MessageUpdated,
    ): BurstCorrection {
        val target = edit.messageId
        if (burst.messageIds.none { it == target }) return BurstCorrection.NotApplicable

        return when (burst.status) {
            BurstStatus.OPEN ->
                BurstCorrection.FragmentUpdated(
                    burst = burst.replaceFragmentContent(target, edit.content),
                )
            BurstStatus.FINALIZED ->
                BurstCorrection.Corrected(
                    burst = burst.correct(),
                    targetMessageId = target,
                    revision = edit.revision,
                )
            BurstStatus.CORRECTED ->
                // 이미 정정됨 — 같은 burstId 유지, 더 최신 revision provenance 만 갱신(멱등).
                BurstCorrection.Corrected(
                    burst = burst,
                    targetMessageId = target,
                    revision = edit.revision,
                )
        }
    }

    /**
     * 한 메시지 [deletion] 이 [burst] 에 미치는 효과를 결정한다(T015). 대상이 버스트에 없으면 [BurstCorrection.NotApplicable].
     *
     * - 삭제 대상 fragment 의 content 를 [MessageContent.Unavailable.Empty] 로 치환해 combined text 에서 원문을 제거한다.
     * - 치환 후에도 다른 텍스트 fragment 가 남으면: OPEN 은 [BurstCorrection.FragmentUpdated], FINALIZED/CORRECTED 는
     *   burstId 유지 [BurstCorrection.Corrected].
     * - 모든 fragment 의 텍스트가 사라지면(빈/부분 → 전부 비텍스트): [BurstCorrection.Emptied] — lineage 삭제 신호
     *   (이미 학습 export 된 경우 CORRECTED 상태로 표시 후 삭제).
     */
    fun applyDeletion(
        burst: UtteranceBurst,
        deletion: MessageDeleted,
    ): BurstCorrection {
        val target = deletion.messageId
        if (burst.messageIds.none { it == target }) return BurstCorrection.NotApplicable

        val scrubbed = burst.replaceFragmentContent(target, MessageContent.Unavailable.Empty)
        val hasRemainingText =
            scrubbed.fragments.any { (it.content as? MessageContent.Available)?.text?.isNotBlank() == true }

        if (!hasRemainingText) {
            // 남은 텍스트가 없다 — 빈/소실 버스트. burstId 는 유지(provenance), lineage 삭제를 신호한다.
            val markedBurst = if (burst.status == BurstStatus.FINALIZED) burst.correct() else burst
            return BurstCorrection.Emptied(burst = markedBurst.withScrubbedContent(scrubbed.fragments), deletedMessageId = target)
        }

        return when (burst.status) {
            BurstStatus.OPEN -> BurstCorrection.FragmentUpdated(burst = scrubbed)
            BurstStatus.FINALIZED ->
                BurstCorrection.Corrected(burst = scrubbed.correct(), targetMessageId = target, revision = 0L)
            BurstStatus.CORRECTED -> BurstCorrection.Corrected(burst = scrubbed, targetMessageId = target, revision = 0L)
        }
    }
}

/** [burst] 의 [target] fragment content 를 [content] 로 교체한 새 버스트(불변; 다른 필드·순서 보존). */
private fun UtteranceBurst.replaceFragmentContent(
    target: MessageId,
    content: MessageContent,
): UtteranceBurst = withScrubbedContent(fragments.map { if (it.messageId == target) it.copy(content = content) else it })

/** fragment 리스트만 교체한 새 버스트(상태·식별자 유지). content 치환 결과를 담는 내부 헬퍼. */
private fun UtteranceBurst.withScrubbedContent(newFragments: List<MessageFragment>): UtteranceBurst = copy(fragments = newFragments)

/**
 * 교정 결정 결과(sealed). 호출자(application/persistence)가 이 결정을 read model·projection 에 적용한다 —
 * 도메인 결정과 부수효과를 분리한다. 어느 경우에도 [BurstId] 를 재계산하지 않는다(과거 ID 불변, T014).
 */
sealed interface BurstCorrection {
    /** 이 버스트에는 영향 없음(대상 메시지 부재 또는 stale 편집). */
    data object NotApplicable : BurstCorrection

    /** finalize 전(OPEN) 교정 — fragment content 만 갱신된 버스트. */
    data class FragmentUpdated(
        val burst: UtteranceBurst,
    ) : BurstCorrection

    /**
     * finalize 후 교정 — burstId 를 유지한 채 CORRECTED 로 전이. CORRECTED projection event 의 입력이며
     * revision provenance([targetMessageId]+[revision])를 운반한다(T014 acceptance).
     */
    data class Corrected(
        val burst: UtteranceBurst,
        val targetMessageId: MessageId,
        val revision: Long,
    ) : BurstCorrection

    /**
     * 삭제로 모든 텍스트가 사라진 버스트 — lineage 삭제 신호(T015). [burst] 는 content 가 비워진 상태이며
     * (combined text 에 원문 비잔존), 호출자는 이 burst 의 projection·학습 export 를 삭제/무효화한다.
     */
    data class Emptied(
        val burst: UtteranceBurst,
        val deletedMessageId: MessageId,
    ) : BurstCorrection
}
