package com.discordassistant.central.participation.application.port.out

import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotLookupScope
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotScope
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotSet
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersion

interface NiaFewShotStorePort {
    fun findActive(lookup: NiaFewShotLookupScope): NiaFewShotSet?

    fun findByScope(scope: NiaFewShotScope): NiaFewShotSet?

    fun findVersion(
        setId: Long,
        version: Int,
    ): NiaFewShotVersion?

    fun createDraft(
        scope: NiaFewShotScope,
        examples: List<NiaFewShotExample>,
        actorUserId: Long?,
    ): NiaFewShotVersion

    fun replaceDraftExamples(
        setId: Long,
        version: Int,
        examples: List<NiaFewShotExample>,
    ): NiaFewShotVersion

    fun publish(
        setId: Long,
        version: Int,
        reviewerUserId: Long?,
    ): NiaFewShotSet

    fun rollback(
        setId: Long,
        targetVersion: Int,
        reviewerUserId: Long?,
    ): NiaFewShotSet

    fun archive(
        setId: Long,
        version: Int,
    ): NiaFewShotVersion
}
