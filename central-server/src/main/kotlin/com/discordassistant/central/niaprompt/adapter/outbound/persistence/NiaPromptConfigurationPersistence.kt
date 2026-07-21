package com.discordassistant.central.niaprompt.adapter.outbound.persistence

import com.discordassistant.central.global.crypto.EncryptedStringConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

@Entity
@Table(name = "nia_prompt_configuration")
class NiaPromptConfigurationEntity(
    @Id var id: Long = SINGLETON_ID,
    @Column(name = "active_version") var activeVersion: Int = 0,
    @Convert(converter = EncryptedStringConverter::class)
    @Column(name = "active_documents_json")
    var activeDocumentsJson: String? = null,
    @Convert(converter = EncryptedStringConverter::class)
    @Column(name = "draft_documents_json")
    var draftDocumentsJson: String? = null,
    @Column(name = "updated_by") var updatedBy: Long? = null,
    @Column(name = "updated_at") var updatedAt: Instant = Instant.EPOCH,
    @Column(name = "applied_at") var appliedAt: Instant? = null,
) {
    companion object {
        const val SINGLETON_ID = 1L
    }
}

interface NiaPromptConfigurationRepository : JpaRepository<NiaPromptConfigurationEntity, Long>
