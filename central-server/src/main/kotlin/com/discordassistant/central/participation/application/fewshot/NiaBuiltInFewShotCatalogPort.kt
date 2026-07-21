package com.discordassistant.central.participation.application.fewshot

import com.discordassistant.central.participation.domain.model.fewshot.NiaFewShotExample

interface NiaBuiltInFewShotCatalogPort {
    fun catalog(): NiaBuiltInFewShotCatalog
}

data class NiaBuiltInFewShotCatalog(
    val judgeSetId: Long,
    val judgeVersion: Int,
    val judgeExamples: List<NiaFewShotExample>,
    val speechExamples: List<NiaBuiltInSpeechExample>,
    val editableExamples: List<NiaFewShotExample>,
)
