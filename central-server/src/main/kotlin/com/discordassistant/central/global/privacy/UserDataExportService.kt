package com.discordassistant.central.global.privacy

/**
 * 사용자 데이터 export 유스케이스(NEXA-P17-T008, security·P02 권리 적용).
 *
 * 가명 사용자에 연결된 source events·memory·relation state·training eligibility 를 모아 export 한다. 다른
 * 사용자의 content 와 내부 비밀은 절대 포함하지 않는다(acceptance T008). data-lineage 의 sourceEventId 로 역추적해
 * "이 사용자에게서 비롯된" 데이터만 수집한다.
 *
 * 순수 application: 포트만 호출. Spring/JPA/JDA 미참조. 실제 조회는 어댑터가 채운다.
 */
class UserDataExportService(
    private val source: UserDataSourcePort,
) {
    /**
     * [subjectPseudonym](export 대상 가명 사용자)의 데이터를 [UserDataExport] 로 모은다. 각 항목은 포트에서
     * 가져오되, [assertOwnedBy] 로 **대상 사용자 소유** 인지 재검증한다(다른 사용자 content 혼입 방지·fail-closed).
     */
    fun export(subjectPseudonym: String): UserDataExport {
        require(subjectPseudonym.isNotBlank()) { "subjectPseudonym 은 비어 있을 수 없다" }

        val events = source.sourceEvents(subjectPseudonym).onEach { assertOwnedBy(it.subjectPseudonym, subjectPseudonym, "source_event") }
        val memories = source.memories(subjectPseudonym).onEach { assertOwnedBy(it.subjectPseudonym, subjectPseudonym, "memory") }
        val relations =
            source
                .relationStates(
                    subjectPseudonym,
                ).onEach { assertOwnedBy(it.subjectPseudonym, subjectPseudonym, "relation_state") }
        val eligibility = source.trainingEligibility(subjectPseudonym)
        if (eligibility != null) assertOwnedBy(eligibility.subjectPseudonym, subjectPseudonym, "training_eligibility")

        return UserDataExport(
            subjectPseudonym = subjectPseudonym,
            sourceEvents = events,
            memories = memories,
            relationStates = relations,
            trainingEligibility = eligibility,
        )
    }

    /** 항목 소유자 가명이 export 대상과 다르면 거부한다(다른 사용자 content·내부 비밀 누출 차단·fail-closed). */
    private fun assertOwnedBy(
        ownerPseudonym: String,
        subject: String,
        kind: String,
    ) {
        if (ownerPseudonym != subject) {
            throw CrossSubjectLeakException(kind = kind, owner = ownerPseudonym, subject = subject)
        }
    }
}

/** export 결과 묶음(가명 데이터만 — 원문·내부 비밀 비포함). */
data class UserDataExport(
    val subjectPseudonym: String,
    val sourceEvents: List<ExportedSourceEvent>,
    val memories: List<ExportedMemory>,
    val relationStates: List<ExportedRelationState>,
    val trainingEligibility: ExportedTrainingEligibility?,
)

/** export 항목 공통 — 소유 가명(소유 검증용). */
sealed interface OwnedExportItem {
    val subjectPseudonym: String
}

/** 사용자에게서 비롯된 source event 의 export 표현(원문 없이 가명 id·시각·종류만). */
data class ExportedSourceEvent(
    override val subjectPseudonym: String,
    val sourceEventId: String,
    val eventKind: String,
    val eventTimeMs: Long,
) : OwnedExportItem

/** 사용자 관련 사회 기억의 export 표현(짧은 주장만 — 원문 로그 아님). */
data class ExportedMemory(
    override val subjectPseudonym: String,
    val memoryId: String,
    val claim: String,
    val provenance: String,
) : OwnedExportItem

/** 사용자 관계 상태의 export 표현(가명·등급만). */
data class ExportedRelationState(
    override val subjectPseudonym: String,
    val counterpartPseudonym: String,
    val rapportLabel: String,
) : OwnedExportItem

/** 사용자 학습 적격성의 export 표현(eligible 여부·근거 코드). */
data class ExportedTrainingEligibility(
    override val subjectPseudonym: String,
    val eligible: Boolean,
    val reasonCode: String,
) : OwnedExportItem

/**
 * export 데이터 소스 아웃바운드 포트(application). 어댑터가 가명 사용자 기준으로 소유 데이터만 조회한다.
 * 각 항목은 [OwnedExportItem.subjectPseudonym] 로 소유를 표기해 서비스가 재검증할 수 있게 한다.
 */
interface UserDataSourcePort {
    fun sourceEvents(subjectPseudonym: String): List<ExportedSourceEvent>

    fun memories(subjectPseudonym: String): List<ExportedMemory>

    fun relationStates(subjectPseudonym: String): List<ExportedRelationState>

    fun trainingEligibility(subjectPseudonym: String): ExportedTrainingEligibility?
}

/** export 중 다른 사용자 데이터가 혼입됐다 — fail-closed 거부. 원문 비포함(가명·종류만). */
class CrossSubjectLeakException(
    val kind: String,
    val owner: String,
    val subject: String,
) : RuntimeException("export 격리 위반: $kind 항목 소유자($owner)가 대상($subject)과 다르다")
