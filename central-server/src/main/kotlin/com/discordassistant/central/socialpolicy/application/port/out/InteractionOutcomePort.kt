package com.discordassistant.central.socialpolicy.application.port.out

import com.discordassistant.central.socialpolicy.domain.model.ObservedInteractionOutcome
import com.discordassistant.central.socialpolicy.domain.model.UnresolvedInteraction
import java.time.Instant

/** 실행 의도와 다음 사람 반응을 같은 focus에서 귀속하는 저장 경계다. */
interface InteractionOutcomePort {
    fun open(interaction: UnresolvedInteraction): Boolean

    fun observeLatest(
        focusThreadKey: String,
        code: com.discordassistant.central.socialpolicy.domain.model.ObservedOutcomeCode,
        evidenceRef: String,
        replyToMessageRef: String?,
        observedAt: Instant,
        explicitActionId: String? = null,
    ): ObservedInteractionOutcome?

    fun invalidateByEvidence(evidenceRef: String): Int
}
