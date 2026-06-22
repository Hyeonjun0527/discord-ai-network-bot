package com.discordassistant.central.channelai.application

import com.discordassistant.central.channelai.domain.model.NexaSettingsHistory
import com.discordassistant.central.channelai.domain.model.NexaSettingsSnapshot
import java.time.Clock
import java.time.Instant

/**
 * 길드 NEXA 설정 rollback 유스케이스(NEXA-P18-T016, application 레이어).
 *
 * 길드 설정 version history 를 SSOT 에서 읽어, 이전 version 값으로 **새 version 을 append** 하는 전방 복구를 한다
 * (deliverable T016 — version history 와 이전값 복구). 결정 코어는 순수 [NexaSettingsHistory] 가 맡고, 이 서비스는
 * SSOT 접근 람다와 [Clock] 만 와이어한다.
 *
 * **acceptance(T016)**: 동의 철회 상태(history 최신 snapshot 의 consentRevoked=true)에서는 [NexaSettingsHistory]
 * 가 [com.discordassistant.central.channelai.domain.model.ConsentLockedException] 으로 거부한다 — 동의 철회는 단순
 * 설정 rollback 으로 되돌릴 수 없다(명시 재동의 경로만).
 *
 * 순수성 경계: application — 결정 코어·SSOT 람다·[Clock] 만. Spring/JPA/JDA 미참조(어댑터가 와이어).
 */
class NexaSettingsRollbackService(
    /** 길드의 설정 version history(version 오름차순)를 SSOT 에서 읽는다. */
    private val loadHistory: (guildPseudonym: String) -> List<NexaSettingsSnapshot>,
    /** 새 version snapshot 을 append 한다(append-only — 과거 version 은 보존). */
    private val appendSnapshot: (guildPseudonym: String, snapshot: NexaSettingsSnapshot) -> Unit,
    private val clock: Clock,
) {
    /**
     * [guildPseudonym] 의 설정을 [targetVersion] 값으로 rollback 한다 — 새 version snapshot 을 append 하고 그
     * snapshot 을 돌려준다. 동의 철회 상태면 ConsentLockedException, 대상 version 부재면 SettingsRollbackException.
     */
    fun rollBackTo(
        guildPseudonym: String,
        targetVersion: Int,
        actor: String,
    ): NexaSettingsSnapshot {
        val history = loadHistory(guildPseudonym)
        val rolledBack =
            NexaSettingsHistory.rollBackTo(
                history = history,
                targetVersion = targetVersion,
                actor = actor,
                at = Instant.now(clock),
            )
        appendSnapshot(guildPseudonym, rolledBack)
        return rolledBack
    }
}
