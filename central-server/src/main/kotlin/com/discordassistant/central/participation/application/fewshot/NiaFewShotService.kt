package com.discordassistant.central.participation.application.fewshot

import com.discordassistant.central.participation.application.port.out.NiaFewShotStorePort
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotLookupScope
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotScope
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotSet
import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotVersion
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class NiaFewShotService(
    private val store: NiaFewShotStorePort,
) {
    @Transactional(readOnly = true)
    fun listSets(limit: Int = 100): List<NiaFewShotSet> = store.listSets(limit.coerceIn(1, 200))

    @Transactional(readOnly = true)
    fun set(setId: Long): NiaFewShotSet = store.findSet(setId) ?: throw IllegalArgumentException("fewshot_set_not_found")

    @Transactional(readOnly = true)
    fun version(
        setId: Long,
        version: Int,
    ): NiaFewShotVersion = store.findVersion(setId, version) ?: throw IllegalArgumentException("fewshot_version_not_found")

    @Transactional(readOnly = true)
    fun exactScope(scope: NiaFewShotScope): NiaFewShotSet? = store.findByScope(scope)

    @Transactional(readOnly = true)
    fun activeFor(scope: NiaFewShotLookupScope): NiaFewShotSet? = store.findActive(scope)

    @Transactional
    fun createDraft(command: CreateNiaFewShotDraftCommand): NiaFewShotVersion {
        validateExamples(command.examples)
        return store.createDraft(command.scope, command.examples, command.actorUserId)
    }

    @Transactional
    fun createDraftForSet(command: CreateNiaFewShotDraftForSetCommand): NiaFewShotVersion {
        validateExamples(command.examples)
        val set = set(command.setId)
        return store.createDraft(set.scope, command.examples, command.actorUserId)
    }

    @Transactional
    fun replaceDraft(command: ReplaceNiaFewShotDraftCommand): NiaFewShotVersion {
        validateExamples(command.examples)
        return store.replaceDraftExamples(command.setId, command.version, command.examples)
    }

    @Transactional
    fun publish(command: PublishNiaFewShotVersionCommand): NiaFewShotSet =
        store.publish(command.setId, command.version, command.reviewerUserId)

    @Transactional
    fun rollback(command: RollbackNiaFewShotVersionCommand): NiaFewShotSet =
        store.rollback(command.setId, command.targetVersion, command.reviewerUserId)

    @Transactional
    fun archive(command: ArchiveNiaFewShotVersionCommand): NiaFewShotVersion = store.archive(command.setId, command.version)

    private fun validateExamples(examples: List<NiaFewShotExample>) {
        require(examples.isNotEmpty()) { "few-shot examples 는 비어 있을 수 없다" }
        require(examples.size <= MAX_EXAMPLES_PER_VERSION) { "few-shot examples 가 너무 많다" }
    }

    companion object {
        const val MAX_EXAMPLES_PER_VERSION = 80
    }
}

data class CreateNiaFewShotDraftCommand(
    val scope: NiaFewShotScope,
    val examples: List<NiaFewShotExample>,
    val actorUserId: Long?,
)

data class CreateNiaFewShotDraftForSetCommand(
    val setId: Long,
    val examples: List<NiaFewShotExample>,
    val actorUserId: Long?,
) {
    init {
        require(setId > 0) { "setId 는 양수여야 한다" }
    }
}

data class ReplaceNiaFewShotDraftCommand(
    val setId: Long,
    val version: Int,
    val examples: List<NiaFewShotExample>,
) {
    init {
        require(setId > 0) { "setId 는 양수여야 한다" }
        require(version >= 1) { "version 은 1 이상이어야 한다" }
    }
}

data class PublishNiaFewShotVersionCommand(
    val setId: Long,
    val version: Int,
    val reviewerUserId: Long?,
) {
    init {
        require(setId > 0) { "setId 는 양수여야 한다" }
        require(version >= 1) { "version 은 1 이상이어야 한다" }
    }
}

data class RollbackNiaFewShotVersionCommand(
    val setId: Long,
    val targetVersion: Int,
    val reviewerUserId: Long?,
) {
    init {
        require(setId > 0) { "setId 는 양수여야 한다" }
        require(targetVersion >= 1) { "targetVersion 은 1 이상이어야 한다" }
    }
}

data class ArchiveNiaFewShotVersionCommand(
    val setId: Long,
    val version: Int,
) {
    init {
        require(setId > 0) { "setId 는 양수여야 한다" }
        require(version >= 1) { "version 은 1 이상이어야 한다" }
    }
}
