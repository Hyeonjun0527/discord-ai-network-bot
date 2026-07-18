package com.discordassistant.central.socialpolicy.application.port.out

import com.discordassistant.central.socialpolicy.domain.model.RecentInteractionOutcomeBelief
import com.discordassistant.central.socialpolicy.domain.model.RecentNiaActionBelief
import com.discordassistant.central.socialpolicy.domain.model.SceneBeliefDelta
import com.discordassistant.central.socialpolicy.domain.model.SceneBeliefState
import java.time.Instant

/** 장면 믿음 projection의 원자 갱신 경계다. */
interface SceneBeliefStatePort {
    fun observe(observation: SceneObservation): SceneBeliefState

    fun find(focusThreadKey: String): SceneBeliefState?

    fun applyDelta(
        focusThreadKey: String,
        expectedContextVersion: Long,
        delta: SceneBeliefDelta,
    ): SceneBeliefState?

    fun recordAction(
        focusThreadKey: String,
        action: RecentNiaActionBelief,
    ): SceneBeliefState?

    fun recordOutcome(
        focusThreadKey: String,
        outcome: RecentInteractionOutcomeBelief,
    ): SceneBeliefState?
}

data class SceneObservation(
    val guildPseudonym: String,
    val channelId: String,
    val focusThreadKey: String,
    val sceneSeq: Long,
    val contextVersion: Long,
    val evidenceRef: String,
    val observedAt: Instant,
) {
    init {
        require(guildPseudonym.isNotBlank()) { "guildPseudonym 은 비어 있을 수 없다" }
        require(channelId.isNotBlank()) { "channelId 는 비어 있을 수 없다" }
        require(focusThreadKey.isNotBlank()) { "focusThreadKey 는 비어 있을 수 없다" }
        require(sceneSeq >= 0 && contextVersion >= 0) { "sceneSeq/contextVersion은 음수일 수 없다" }
        require(evidenceRef.matches(Regex("[A-Za-z0-9_:.=-]{1,160}"))) { "evidenceRef 형식이 잘못됐다" }
    }
}
