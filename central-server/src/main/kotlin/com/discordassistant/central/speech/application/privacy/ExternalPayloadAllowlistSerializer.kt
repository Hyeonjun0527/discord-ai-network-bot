package com.discordassistant.central.speech.application.privacy

import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/**
 * GLM payload allowlist serializer(NEXA-P17-T004, security·application/privacy).
 *
 * 외부(GLM) 로 나가는 payload 를 **명시 허용 필드만** 직렬화한다(deny-by-default). 객체 전체 reflection/`toString`
 * 직렬화를 금지하고, [ALLOWED_FIELDS] 에 명시된 키만 한 개씩 골라 담는다. 패킷에 새 필드가 추가돼도 **명시적
 * 승인(allowlist 등록) 없이는 payload 에 자동으로 들어가지 않는다**(T004 acceptance). 직렬화 후에는
 * [ExternalPayloadMinimizer] 가 마지막 방어선으로 잔여 snowflake·시크릿을 스크럽한다(P14 강화).
 *
 * **acceptance(T004) — 새 필드는 명시적 승인 없이 payload 에 자동 추가되지 않는다**:
 *  - [serialize] 는 [ALLOWED_FIELDS] 를 순회하며 각 키별 추출기로만 값을 담는다(객체 전체 직렬화 경로 없음).
 *  - 허용 목록 밖 데이터(예: identity 시스템 프롬프트, raw target id)는 어떤 경로로도 payload 에 등장하지 않는다.
 *  - [allowedFields] 가 SSOT 를 노출해 테스트가 "payload 에 등장한 필드 ⊆ allowlist" 를 검증한다.
 *
 * 순수성 경계: application 레이어. Spring/JPA/JDA·JSON 라이브러리(reflection) 미사용 — 명시 필드 조립만.
 */
class ExternalPayloadAllowlistSerializer(
    private val minimizer: ExternalPayloadMinimizer = ExternalPayloadMinimizer(),
) {
    /**
     * 패킷을 허용 필드만 담은 안정 순서의 key=value 라인으로 직렬화한다. 각 라인 값은 minimizer 로 스크럽된다.
     * 허용 목록 밖 필드는 어떤 경우에도 포함되지 않는다(deny-by-default).
     */
    fun serialize(packet: SpeechScenePacket): String =
        ALLOWED_FIELDS
            .mapNotNull { field ->
                field.extract(packet)?.let { raw -> "${field.key}=${minimizer.scrub(raw)}" }
            }.joinToString(separator = "\n")

    /** allowlist SSOT(테스트 검증용) — payload 에 등장 가능한 필드 키 집합. */
    fun allowedFields(): Set<String> = ALLOWED_FIELDS.map { it.key }.toSet()

    /** 허용 필드 1개의 정의: 키 + 패킷에서 그 필드만 뽑는 추출기(객체 전체가 아니라 단일 값). */
    private data class AllowedField(
        val key: String,
        val extract: (SpeechScenePacket) -> String?,
    )

    companion object {
        /**
         * 외부 payload 에 담을 수 있는 **유일한** 필드 목록(deny-by-default). 여기에 명시되지 않은 패킷 필드
         * (identity 시스템 프롬프트, raw snowflake, 내부 confidence 등)는 절대 직렬화되지 않는다. 새 필드를
         * 내보내려면 이 목록에 명시적으로 추가해야 하고, 그 변경은 테스트가 감지한다(ExternalPayloadAllowlistSerializerTest).
         */
        private val ALLOWED_FIELDS: List<AllowedField> =
            listOf(
                AllowedField("focus_thread") { it.focusThreadKey },
                AllowedField("target") { it.target.pseudonymKey },
                AllowedField("social_act") { it.socialAct.name },
                AllowedField("recent_turns") { packet ->
                    packet.recentTurns
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(separator = " | ") { "${it.speakerLabel}: ${it.text}" }
                },
                AllowedField("memory_refs") { packet ->
                    packet.memoryRefs
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(separator = " | ") { it.claim }
                },
            )
    }
}
