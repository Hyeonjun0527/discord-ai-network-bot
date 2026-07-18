package com.discordassistant.central.conversation.adapter.outbound.persistence.scene

import com.discordassistant.central.conversation.application.port.out.SceneSnapshotRecord
import com.discordassistant.central.conversation.application.scene.ConversationObservation
import com.discordassistant.central.conversation.application.scene.ConversationSceneIngress
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/** 기존 conversation scene snapshot/history를 단일 버전 SSOT로 원자 갱신한다. */
@Repository
class JpaConversationSceneIngress(
    private val snapshots: NexaSceneSnapshotRepository,
    private val versions: NexaSceneVersionRepository,
) : ConversationSceneIngress {
    @Transactional
    override fun observe(observation: ConversationObservation): SceneSnapshotRecord? {
        val storedChannelId = ScenePersistenceIdentity.channel(observation.channelId)
        val storedGuildId = ScenePersistenceIdentity.guild(observation.guildId)
        val storedObservationRef = ScenePersistenceIdentity.observation(observation.observationRef)
        versions.findByObservationRef(storedObservationRef)?.let {
            return snapshots.findByChannelId(storedChannelId)?.toRecord()
        }
        val snapshot =
            snapshots.findByChannelIdForUpdate(storedChannelId)
                ?: NexaSceneSnapshotEntity(channelId = storedChannelId, guildId = storedGuildId)
        snapshot.guildId = storedGuildId
        snapshot.sceneSeq += 1
        snapshot.contextVersion += 1
        snapshots.saveAndFlush(snapshot)
        versions.save(
            NexaSceneVersionEntity(
                channelId = storedChannelId,
                sceneSeq = snapshot.sceneSeq,
                contextVersion = snapshot.contextVersion,
                observationRef = storedObservationRef,
            ),
        )
        return snapshot.toRecord()
    }

    @Transactional(readOnly = true)
    override fun current(channelId: Long): SceneSnapshotRecord? =
        snapshots.findByChannelId(ScenePersistenceIdentity.channel(channelId))?.toRecord()

    private fun NexaSceneSnapshotEntity.toRecord(): SceneSnapshotRecord =
        SceneSnapshotRecord(
            guildId = guildId,
            channelId = channelId,
            sceneSeq = sceneSeq,
            contextVersion = contextVersion,
            recentBurstCount = recentBurstCount,
            activeThreadCount = activeThreadCount,
            participantCount = participantCount,
        )
}
