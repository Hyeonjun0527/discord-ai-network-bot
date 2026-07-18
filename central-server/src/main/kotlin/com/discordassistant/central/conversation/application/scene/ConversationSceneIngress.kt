package com.discordassistant.central.conversation.application.scene

import com.discordassistant.central.conversation.application.port.out.SceneSnapshotRecord
import java.time.Instant

/**
 * 한 Discord 관찰을 conversation 장면 projection에 동기 반영하고, 저장이 끝난 snapshot을 돌려주는 read-after-write 경계다.
 * participation은 이 반환값의 sceneSeq/contextVersion만 사용하며 별도 사회 상태 저장소에서 버전을 만들지 않는다.
 */
interface ConversationSceneIngress {
    fun observe(observation: ConversationObservation): SceneSnapshotRecord?

    fun current(channelId: Long): SceneSnapshotRecord?
}

/** Spring 밖의 순수 단위 테스트용 구현. production은 JPA 구현을 주입한다. */
class InMemoryConversationSceneIngress : ConversationSceneIngress {
    private data class State(
        var snapshot: SceneSnapshotRecord,
        val observations: MutableSet<String> = linkedSetOf(),
    )

    private val states = mutableMapOf<Long, State>()

    @Synchronized
    override fun observe(observation: ConversationObservation): SceneSnapshotRecord {
        val state =
            states.getOrPut(observation.channelId) {
                State(SceneSnapshotRecord(observation.guildId, observation.channelId, 0, 0, 0, 0, 0))
            }
        if (state.observations.add(observation.observationRef)) {
            state.snapshot =
                state.snapshot.copy(
                    guildId = observation.guildId,
                    sceneSeq = state.snapshot.sceneSeq + 1,
                    contextVersion = state.snapshot.contextVersion + 1,
                )
        }
        return state.snapshot
    }

    @Synchronized
    override fun current(channelId: Long): SceneSnapshotRecord? = states[channelId]?.snapshot
}

data class ConversationObservation(
    val guildId: Long,
    val channelId: Long,
    val observationRef: String,
    val observedAt: Instant,
) {
    init {
        require(guildId > 0 && channelId > 0) { "guildId/channelId는 양수여야 한다" }
        require(observationRef.matches(Regex("[A-Za-z0-9_:.=-]{1,160}"))) { "observationRef 형식이 잘못됐다" }
    }
}
