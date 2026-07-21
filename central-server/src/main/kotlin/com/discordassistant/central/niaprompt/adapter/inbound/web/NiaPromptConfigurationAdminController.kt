package com.discordassistant.central.niaprompt.adapter.inbound.web

import com.discordassistant.central.global.security.DashboardActor
import com.discordassistant.central.niaprompt.application.NiaPromptConfigurationService
import com.discordassistant.central.niaprompt.application.NiaPromptConfigurationView
import com.discordassistant.central.shared.NiaPromptDefaults
import com.discordassistant.central.shared.NiaPromptKey
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/admin/nia/prompts")
class NiaPromptConfigurationAdminController(
    private val service: NiaPromptConfigurationService,
) {
    @GetMapping
    fun get(httpRequest: HttpServletRequest): NiaPromptConfigurationDto {
        DashboardActor.from(httpRequest)
        return service.load().toDto()
    }

    @PutMapping
    fun save(
        @RequestBody request: NiaPromptSaveRequest,
        httpRequest: HttpServletRequest,
    ): NiaPromptConfigurationDto {
        val actor = DashboardActor.from(httpRequest)
        return service.saveDraft(request.documents, actor.userId).toDto()
    }

    @PostMapping("/apply")
    fun apply(httpRequest: HttpServletRequest): NiaPromptConfigurationDto {
        val actor = DashboardActor.from(httpRequest)
        return service.applyDraft(actor.userId).toDto()
    }

    @PostMapping("/reset-draft")
    fun resetDraft(httpRequest: HttpServletRequest): NiaPromptConfigurationDto {
        val actor = DashboardActor.from(httpRequest)
        return service.resetDraft(actor.userId).toDto()
    }
}

data class NiaPromptSaveRequest(
    val documents: Map<String, String>,
)

data class NiaPromptDocumentDto(
    val key: String,
    val group: String,
    val title: String,
    val description: String,
    val requiredPlaceholders: List<String>,
    val defaultContent: String,
    val activeContent: String,
    val draftContent: String,
)

data class NiaPromptConfigurationDto(
    val activeVersion: Int,
    val source: String,
    val dirty: Boolean,
    val updatedAt: Instant?,
    val appliedAt: Instant?,
    val documents: List<NiaPromptDocumentDto>,
)

private fun NiaPromptConfigurationView.toDto() =
    NiaPromptConfigurationDto(
        activeVersion = activeVersion,
        source = source,
        dirty = dirty,
        updatedAt = updatedAt,
        appliedAt = appliedAt,
        documents =
            NiaPromptKey.entries.map { key ->
                NiaPromptDocumentDto(
                    key = key.wireName,
                    group = key.group,
                    title = key.title,
                    description = key.description,
                    requiredPlaceholders = key.requiredPlaceholders.sorted(),
                    defaultContent = NiaPromptDefaults.text(key),
                    activeContent = activeDocuments.getValue(key),
                    draftContent = draftDocuments.getValue(key),
                )
            },
    )
