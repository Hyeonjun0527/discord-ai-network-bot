package com.discordassistant.central.socialmemory.domain.service.appraisal

import com.discordassistant.central.socialmemory.domain.model.appraisal.Appraisal
import com.discordassistant.central.socialmemory.domain.model.appraisal.AppraisalCertainty
import com.discordassistant.central.socialmemory.domain.model.appraisal.EventIntensity
import com.discordassistant.central.socialmemory.domain.model.appraisal.RelationshipLens
import com.discordassistant.central.socialmemory.domain.model.appraisal.SocialEventKind
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * 사회 사건 판단 — core `nia_engine/social_appraiser.py`(Stage B B5, docx 6) 이식. **순수 도메인(오프라인).**
 *
 * 메시지를 받아 사회적 의미를 **등급으로만** 판정한다(GLM 실호출은 [AppraiserProvider] 어댑터 — wiring 단계).
 *
 * 핵심 불변식:
 * - **I2** GLM 은 등급만(kind/intensity/certainty/targetIsNia). 숫자(관계 델타) 절대 출력 안 함 —
 *   [Appraisal] 에 숫자 필드가 없어 구조적으로 보장. 숫자 매핑은 B6 수학.
 * - **I3** 현재 기분을 입력에 넣지 않는다 — [buildUserPrompt]/[appraise] 시그니처에 기분 인자 없음
 *   (기분나쁨→부정해석→더나쁨 자기증폭 구조적 차단).
 * - **관계가 해석 렌즈(§0.1 1번)** — [RelationshipLens] 를 프롬프트에 주입해 같은 문장도 관계에 따라
 *   kind/intensity 가 달라지게 한다("나쁜여자야" @친함=PLAYFUL vs @처음=INSULT).
 * - **보수성** — 근거 약하면 certainty=LOW, 대상 불명확하면 targetIsNia=false 로 보수([conservativeDefault]).
 */
object SocialAppraiser {
    private val mapper = jacksonObjectMapper()

    val SYSTEM_PROMPT: String =
        """
        너는 한국어 그룹채팅에서 '니아'에게 일어난 사회적 사건의 의미를 해석하는 센서다. 메시지가 사회적으로 무슨 사건인지 **등급으로만** 판정한다.

        ★절대 규칙:
        - 너는 등급(범주·서열)만 출력한다. 관계 점수·숫자·델타를 절대 만들지 마라. 숫자는 코드가 정한다.
        - 현재 니아의 기분은 너에게 주어지지 않는다. 기분으로 해석을 바꾸지 마라(자기증폭 금지).
        - **관계가 해석을 바꾼다.** 같은 문장도 '친한 사이'면 장난, '처음 보는 사이'면 모욕일 수 있다. 주어진 관계 라벨을 반드시 해석 렌즈로 써라.
        - 근거가 약하거나 애매하면 certainty 를 LOW 로 둬라. 억지로 확정하지 마라.
        - 니아를 향한 말인지 불명확하면 target_is_nia 를 false 로 보수적으로 둬라.

        판정 항목(enum):
        - target_is_nia: true/false
        - kind: PRAISE / PLAYFUL / INSULT / APOLOGY / COLLAB / QUESTION / SMALLTALK
        - intensity: MICRO / MILD / CLEAR / STRONG
        - certainty: CLEAR / LOW
        - target_person_id: 대상이 니아가 아닌 특정 다른 사람이면 그 표기, 아니면 null

        반드시 JSON 객체 하나만 출력한다. 스키마:
        {"target_is_nia":true/false,"kind":"<enum>","intensity":"<enum>","certainty":"<enum>","target_person_id":null 또는 "문자열","rationale":"한 줄 근거"}
        """.trimIndent()

    /**
     * 판정용 user 프롬프트 — 관계 렌즈를 명시 주입. **현재 기분은 넣지 않는다(I3).**
     * 시그니처에 기분 인자가 없어 구조적으로 자기증폭을 차단한다.
     */
    fun buildUserPrompt(
        messages: List<String>,
        speakerPersonId: String,
        lens: RelationshipLens,
        candidatePersonIds: List<String>? = null,
    ): String {
        val convo = messages.joinToString("\n") { "- $it" }
        val cands =
            if (candidatePersonIds.isNullOrEmpty()) "없음(니아 또는 불명확)" else candidatePersonIds.joinToString(", ")
        return "[관계 렌즈 — 이 기준으로 해석하라]\n${lens.describe()}\n\n" +
            "[발화자] $speakerPersonId\n[대상 후보(니아 외)] $cands\n\n" +
            "[최근 메시지]\n$convo\n\n" +
            "위 메시지가 니아에게 사회적으로 어떤 사건인지 등급으로만 판정해 JSON 하나만 출력하라. " +
            "관계 라벨을 반드시 해석 렌즈로 쓰고, 애매하면 certainty=LOW·target_is_nia=false 로 보수적으로."
    }

    /**
     * GLM 응답(JSON 문자열) → [Appraisal](등급만). enum 밖 값·누락은 보수 폴백으로 정규화(억지 확정 금지).
     * 숫자 델타 키가 응답에 있어도 무시한다(I2 — Appraisal 에 숫자 필드 없음).
     */
    fun parse(content: String?): Appraisal {
        val text = stripFences(content ?: "").ifBlank { return Appraisal.conservativeDefault(error = "empty") }
        val json = extractJsonObject(text) ?: return Appraisal.conservativeDefault(error = "no-json")
        val node =
            try {
                mapper.readTree(json)
            } catch (e: Exception) {
                return Appraisal.conservativeDefault(error = "parse: ${e.message}")
            }

        val kind = enumOrNull<SocialEventKind>(node.get("kind")?.asText()) ?: SocialEventKind.SMALLTALK
        val intensity = enumOrNull<EventIntensity>(node.get("intensity")?.asText()) ?: EventIntensity.MICRO
        // enum 밖이면 가장 보수적인 LOW 로(억지 확정 금지).
        val certainty = enumOrNull<AppraisalCertainty>(node.get("certainty")?.asText()) ?: AppraisalCertainty.LOW
        val targetIsNia = node.get("target_is_nia")?.asBoolean(false) ?: false
        val rawTarget = node.get("target_person_id")?.asText()
        val targetPid = if (rawTarget.isNullOrBlank() || rawTarget in setOf("null", "None")) null else rawTarget
        val rationale = (node.get("rationale")?.asText() ?: "").take(200)

        return Appraisal(targetIsNia, kind, intensity, certainty, targetPid, rationale)
    }

    /** 발화자 ID + 관계 렌즈로 사회 사건을 등급 판정한다(진입점). 메시지/발화자 비면 예외 — I3(기분 인자 없음). */
    fun appraise(
        provider: AppraiserProvider,
        messages: List<String>,
        speakerPersonId: String,
        lens: RelationshipLens,
        candidatePersonIds: List<String>? = null,
    ): Appraisal {
        require(messages.isNotEmpty()) { "messages 가 비어 있다 — 판정할 사건이 없다" }
        require(speakerPersonId.isNotBlank()) { "speakerPersonId 가 비어 있다 — 발화자 식별 필요(I7)" }
        return provider.appraise(messages, speakerPersonId, lens, candidatePersonIds)
    }

    private val FENCE = Regex("^```(?:json)?\\s*|\\s*```$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    private fun stripFences(s: String): String = FENCE.replace(s.trim(), "").trim()

    private fun extractJsonObject(s: String): String? {
        if (s.startsWith("{") && s.endsWith("}")) return s
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        return if (start in 0 until end) s.substring(start, end + 1) else null
    }

    private inline fun <reified T : Enum<T>> enumOrNull(value: String?): T? {
        val v = value?.trim()?.uppercase() ?: return null
        return enumValues<T>().firstOrNull { it.name == v }
    }
}

/**
 * 단일 Appraiser 호출 추상화 — GLM(CloudLlm) → mock 교체 가능(단위테스트). 실제 GLM 호출 어댑터는
 * wiring 단계에서 `CloudLlm.generate` 로 구현한다(SYSTEM_PROMPT + buildUserPrompt → parse).
 */
interface AppraiserProvider {
    fun appraise(
        messages: List<String>,
        speakerPersonId: String,
        lens: RelationshipLens,
        candidatePersonIds: List<String>? = null,
    ): Appraisal
}
