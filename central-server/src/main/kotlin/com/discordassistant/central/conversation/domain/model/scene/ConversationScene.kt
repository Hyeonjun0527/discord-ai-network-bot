package com.discordassistant.central.conversation.domain.model.scene

import com.discordassistant.central.conversation.domain.event.SceneChangeType
import com.discordassistant.central.conversation.domain.event.SceneUpdated
import com.discordassistant.central.conversation.domain.model.burst.BurstId
import com.discordassistant.central.conversation.domain.model.event.ChannelId
import com.discordassistant.central.conversation.domain.model.event.GuildId
import com.discordassistant.central.conversation.domain.model.thread.ConversationThreadId
import com.discordassistant.central.conversation.domain.service.scene.ConversationFocus

/**
 * 대화 장면 aggregate(NEXA-P05-T017, 순수 도메인 읽기 모델·불변). 한 채널의 "지금 무슨 대화가 오가는가" 를
 * 요약한 읽기 모델이다 — 최근 burst 요약, reply/thread graph 참조, 참여자([SceneParticipant]), 템포
 * ([ConversationTempo]), 초점([ConversationFocus]), 그리고 [contextVersion] 을 가진다.
 *
 * conversation-context.md 불변식 3: 장면 projection 은 **순수 읽기 모델** 이며 외부 호출(모델·네트워크) 없이
 * 계산된다 — 이 aggregate 는 입력만으로 만들어지고 부작용이 없다.
 *
 * **acceptance(T017) — 원문 전체 비복제, event/burst ID 로 provenance 유지**:
 * - [recentBurstIds]/[activeThreadIds] 는 **식별자만** — 원문 텍스트나 combined text 를 담지 않는다.
 * - 참여자도 [SceneParticipant](content-derived feature 미포함)만 담는다.
 * - 따라서 장면은 원문을 복제하지 않고 식별자 참조로 출처(provenance)를 유지한다(원문은 event store 에만).
 *
 * 상태 전이([advance])는 항상 **새 인스턴스** 를 돌려준다(불변; replay 안전). [contextVersion] 증가는
 * [ContextVersion.apply] 의 결정론적 규칙(T018)을 따른다.
 *
 * 순수성: Spring/JPA/JDA/adapter 타입을 일절 참조하지 않는다.
 */
data class ConversationScene(
    val guildId: GuildId,
    val channelId: ChannelId,
    /** 장면 순번(채널 내 단조 증가) — SceneUpdated 멱등키 `channelId + sceneSeq` 의 sceneSeq(domain-events.md). */
    val sceneSeq: Long,
    /** 최근 burst 식별자(시간순, 원문 비포함). 장면이 참조하는 발화 묶음의 provenance. */
    val recentBurstIds: List<BurstId>,
    /** 활성 논리 스레드 식별자(graph ref). reply/thread graph 의 어느 흐름이 살아있는지. */
    val activeThreadIds: Set<ConversationThreadId>,
    /** 참여자 요약(content-derived feature 미포함, T014). */
    val participants: List<SceneParticipant>,
    /** 대화 템포(밀도·gap·speaker·overlap, T015). */
    val tempo: ConversationTempo,
    /** 현재 초점 분포(활성 스레드 없음=정상, T016). */
    val focus: ConversationFocus,
    /** 정책 판단 무효화 추적 버전(T018). */
    val contextVersion: ContextVersion,
) {
    init {
        require(sceneSeq >= 0) { "sceneSeq 는 음수일 수 없다" }
        require(participants.map { it.authorId }.toSet().size == participants.size) {
            "참여자는 중복될 수 없다"
        }
    }

    /**
     * 새 입력으로 장면을 갱신한 **다음 장면** 을 돌려준다(불변). [change] 가 정책을 무효화하면 [contextVersion] 이
     * 오르고(T018), 매 갱신마다 [sceneSeq] 는 +1 한다(멱등키 sceneSeq 단조 증가). 모든 갱신은 식별자/계산값만 받는다
     * (원문 비복제, T017).
     */
    fun advance(
        change: SceneChange,
        recentBurstIds: List<BurstId> = this.recentBurstIds,
        activeThreadIds: Set<ConversationThreadId> = this.activeThreadIds,
        participants: List<SceneParticipant> = this.participants,
        tempo: ConversationTempo = this.tempo,
        focus: ConversationFocus = this.focus,
    ): ConversationScene =
        copy(
            sceneSeq = sceneSeq + 1,
            recentBurstIds = recentBurstIds,
            activeThreadIds = activeThreadIds,
            participants = participants,
            tempo = tempo,
            focus = focus,
            contextVersion = contextVersion.apply(change),
        )

    /**
     * 직전 장면([previous])과 [change] 로부터 이 장면 갱신의 [SceneUpdated] 이벤트를 만든다(T019). 변경 유형,
     * 이전/새 contextVersion, 영향 thread 를 싣는다. 같은 입력 replay 면 같은 이벤트가 나온다(결정론).
     */
    fun toSceneUpdated(
        previous: ConversationScene,
        change: SceneChange,
    ): SceneUpdated =
        SceneUpdated(
            guildId = guildId,
            channelId = channelId,
            sceneSeq = sceneSeq,
            changeType = SceneChangeType.of(change),
            previousContextVersion = previous.contextVersion.value,
            newContextVersion = contextVersion.value,
            affectedThreadIds = affectedThreadIds(previous),
        )

    /** 직전 장면 대비 새로 생기거나 사라진 스레드(영향 thread) — 대칭 차집합(식별자만). */
    private fun affectedThreadIds(previous: ConversationScene): Set<ConversationThreadId> =
        (activeThreadIds - previous.activeThreadIds) + (previous.activeThreadIds - activeThreadIds)

    companion object {
        /**
         * 빈 초기 장면(아직 burst 없음) — sceneSeq 0, INITIAL 버전, IDLE 템포, idle 초점. 채널 첫 관찰 전 seed.
         */
        fun initial(
            guildId: GuildId,
            channelId: ChannelId,
            ruleVersion: String,
        ): ConversationScene =
            ConversationScene(
                guildId = guildId,
                channelId = channelId,
                sceneSeq = 0,
                recentBurstIds = emptyList(),
                activeThreadIds = emptySet(),
                participants = emptyList(),
                tempo = ConversationTempo.IDLE,
                focus = ConversationFocus.idle(ruleVersion),
                contextVersion = ContextVersion.INITIAL,
            )
    }
}
