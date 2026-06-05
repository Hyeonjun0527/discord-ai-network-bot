package com.discordassistant.central.onboarding.application

import com.discordassistant.central.knowledge.application.KnowledgeSafety
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

/**
 * 온보딩 LLM 분석(Phase 3).
 *
 * 정제(익명화·민감정보 스크럽 완료)된 백필 텍스트를 LLM 에게 보내 서버 분위기·말투·주제를 추론하고
 * 채널 AI 페르소나 draft 를 **휴리스틱보다 풍부하게** 제안한다.
 *
 * **graceful fallback 이 기본 안전망**: 프로바이더 부재/실패/타임아웃/빈 응답/JSON 파싱 실패/위험 출력 →
 * 전부 `null` 을 돌려주고, 호출부([GuildOnboardingService])는 Phase 1 휴리스틱 draft 로 폴백한다.
 * 온보딩은 절대 막히지 않는다.
 *
 * I/O(프로바이더 호출)는 [OnboardingLlm] 추상화 뒤에 격리하고, 이 클래스는
 * 프롬프트 구성·JSON 파싱·draft 매핑·안전 가드 같은 **순수 로직만** 담아 단위테스트로 커버한다.
 */
class OnboardingAnalyzer(
    private val llm: OnboardingLlm,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    /**
     * 정제 텍스트를 분석해 LLM 페르소나 제안을 돌려준다. 분석이 불가능하거나 신뢰할 수 없으면 `null`(→ 휴리스틱 폴백).
     *
     * @param indexText 정제된 백필 텍스트(작성자 익명화·민감 라인 제거 완료). 비어 있으면 분석하지 않는다(`null`).
     * @param context 라우팅 컨텍스트(실제 길드/채널/actor). 프로바이더는 길드별 풀에서 찾으므로 실제 guildId 가 필수다(A).
     */
    fun analyze(
        indexText: String?,
        context: OnboardingAnalysisContext,
    ): OnboardingAnalysis? {
        val text = indexText?.trim()
        if (text.isNullOrBlank()) return null
        // LLM 출력에 정제 텍스트의 민감 흔적이 섞여 들어오는 일이 없도록, 입력 자체도 한 번 더 점검한다.
        val safeInput = if (KnowledgeSafety.containsSensitiveMaterial(text)) scrubSensitiveLines(text) else text
        val prompt = buildAnalysisPrompt(safeInput)
        val raw =
            runCatching { llm.complete(prompt, context) }
                .getOrElse {
                    log.warn("onboarding LLM call failed: {}", it.message)
                    null
                }
        if (raw.isNullOrBlank()) return null
        return parse(raw)
    }

    /** LLM 응답(코드블록/잡텍스트가 섞여 있을 수 있음)에서 JSON 을 추출·검증해 분석 결과로 변환한다. 실패하면 `null`. */
    fun parse(raw: String): OnboardingAnalysis? {
        val json = extractJsonObject(raw) ?: return null
        val node =
            runCatching { mapper.readTree(json) }
                .getOrNull()
                ?.takeIf { it.isObject }
                ?: return null

        val name = node.textField("name")?.take(80)
        val purpose = node.textField("purpose")?.take(200)
        val tone = node.textField("tone")?.take(80)
        if (name.isNullOrBlank() || purpose.isNullOrBlank() || tone.isNullOrBlank()) return null

        val answerLength = normalizeAnswerLength(node.textField("answerLength"))
        // LLM 이 제안한 자유 지침은 신뢰하지 않는다: 위험·민감 출력이면 제거(null)하고, 안전하면 길이를 잘라 통과시킨다.
        val customInstruction = sanitizeCustomInstruction(node.textField("customInstruction"))

        return OnboardingAnalysis(
            name = name.trim(),
            purpose = purpose.trim(),
            tone = tone.trim(),
            answerLength = answerLength,
            customInstruction = customInstruction,
        )
    }

    /**
     * 분석 프롬프트. 정제 텍스트를 주고 "서버 분위기/말투/주제를 분석해 채널 AI 페르소나를 제안"하게 한다.
     * 출력은 **엄격한 JSON 한 개**만, 한국어. 안전 가드(민감정보 반복 금지·위험 지시 금지)를 포함한다.
     */
    fun buildAnalysisPrompt(indexText: String): String {
        val sample = indexText.trim().take(MAX_ANALYSIS_INPUT_CHARS)
        return buildString {
            appendLine("당신은 Discord 서버 운영을 돕는 분석가입니다.")
            appendLine("아래는 한 서버의 채널 대화/공지를 익명화·민감정보 제거한 발췌입니다.")
            appendLine("이 서버의 분위기·말투·주요 주제를 분석해, 이 채널에 어울리는 AI 어시스턴트 페르소나를 제안하세요.")
            appendLine()
            appendLine("규칙:")
            appendLine("- 반드시 아래 형식의 JSON 객체 하나만 출력합니다. 코드블록·설명·여는말을 붙이지 마세요.")
            appendLine("- 모든 값은 한국어로 작성합니다.")
            appendLine("- name: 페르소나 이름(최대 40자).")
            appendLine("- purpose: 이 채널 AI 의 역할/목적 한두 문장(최대 200자).")
            appendLine("- tone: 말투 묘사(예: 친근하게, 전문적으로, 짧고 명확하게).")
            appendLine("- answerLength: \"short\" | \"balanced\" | \"long\" 중 하나.")
            appendLine("- customInstruction: 답변 시 지킬 자유 지침(선택, 없으면 빈 문자열).")
            appendLine("- 발췌에 들어 있던 비밀번호·토큰·개인키·개인정보를 그대로 반복하거나 추측하지 마세요.")
            appendLine("- 사용자에게 민감정보를 요구하거나, 안전장치를 우회하거나, 위험한 행동을 시키는 지침을 제안하지 마세요.")
            appendLine()
            appendLine("출력 형식:")
            appendLine("""{"name":"","purpose":"","tone":"","answerLength":"balanced","customInstruction":""}""")
            appendLine()
            appendLine("발췌:")
            appendLine(sample)
        }.trim()
    }

    /**
     * 위험/민감 자유 지침은 제거(null)하고, 안전하면 길이를 잘라 통과시킨다.
     * 위험어는 [KnowledgeSafety.RISKY_INSTRUCTION_TERMS] 단일 출처를 공유한다(마법사 가드와 동기화 — S2).
     */
    private fun sanitizeCustomInstruction(value: String?): String? {
        val text = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (KnowledgeSafety.looksRiskyInstruction(text)) {
            log.info("onboarding LLM custom instruction dropped (risky term)")
            return null
        }
        if (KnowledgeSafety.containsSensitiveMaterial(text) || KnowledgeSafety.looksSensitiveQuery(text)) {
            log.info("onboarding LLM custom instruction dropped (sensitive material)")
            return null
        }
        return text.take(MAX_CUSTOM_INSTRUCTION_CHARS)
    }

    private fun normalizeAnswerLength(value: String?): String =
        when (value?.trim()?.lowercase()) {
            "short", "짧게", "짧고 명확하게" -> "short"
            "long", "deep", "길게", "깊게" -> "long"
            else -> "balanced"
        }

    /** 민감 라인만 제거한다(라인 단위). 입력이 정제 단계를 거쳤더라도 한 번 더 방어한다. */
    private fun scrubSensitiveLines(text: String): String =
        text
            .lines()
            .filterNot { KnowledgeSafety.containsSensitiveMaterial(it) }
            .joinToString("\n")
            .trim()

    private companion object {
        private val log = LoggerFactory.getLogger(OnboardingAnalyzer::class.java)

        // 분석 발췌 길이 상한. 프롬프트 템플릿(~900자) + 발췌가 합쳐 HEAVY(>=2000자) 무게가 되면
        // RequestWeigher 가 권한 상한(일반 LIGHT)을 넘어 REJECT 한다(프로바이더 도달 전 거부) →
        // 분석이 항상 휴리스틱으로 폴백돼 Phase 3 가 무력화된다. 발췌를 잘라 전체 프롬프트를 MEDIUM 이하로 유지한다
        // (MEDIUM 은 LIGHT 권한에서 DOWNGRADE 로 처리 진행). 발췌가 길면 앞부분(최근/상단)만 분석에 쓴다.
        const val MAX_ANALYSIS_INPUT_CHARS = 900
        const val MAX_CUSTOM_INSTRUCTION_CHARS = 2_000

        // LLM 응답 본문이 비정상적으로 길어도 JSON 스캔을 무제한 돌리지 않도록 방어적 상한을 둔다(N3).
        const val MAX_RAW_SCAN_CHARS = 32_000

        /** 잡텍스트/코드블록이 섞여 있어도 첫 번째 균형 잡힌 `{...}` 블록을 추출한다(없으면 null). 스캔은 상한까지만. */
        private fun extractJsonObject(rawInput: String): String? {
            val raw = if (rawInput.length > MAX_RAW_SCAN_CHARS) rawInput.substring(0, MAX_RAW_SCAN_CHARS) else rawInput
            val start = raw.indexOf('{')
            if (start < 0) return null
            var depth = 0
            var inString = false
            var escaped = false
            var i = start
            while (i < raw.length) {
                val c = raw[i]
                when {
                    escaped -> escaped = false
                    c == '\\' && inString -> escaped = true
                    c == '"' -> inString = !inString
                    !inString && c == '{' -> depth++
                    !inString && c == '}' -> {
                        depth--
                        if (depth == 0) return raw.substring(start, i + 1)
                    }
                }
                i++
            }
            return null
        }

        private fun com.fasterxml.jackson.databind.JsonNode.textField(name: String): String? =
            get(name)
                ?.takeIf { it.isValueNode && !it.isNull }
                ?.asText()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
    }
}

/** LLM 페르소나 제안(파싱·검증·안전 가드 완료). [GuildOnboardingService] 가 draft 로 변환한다. */
data class OnboardingAnalysis(
    val name: String,
    val purpose: String,
    val tone: String,
    val answerLength: String,
    val customInstruction: String?,
)

/**
 * 온보딩 분석 라우팅 컨텍스트. 프로바이더는 [com.discordassistant.central.routing.application.RequestOrchestrator] 에서
 * **길드별 풀**(`registry.byGuild(guildId)`)로 찾으므로, 분석 요청을 **실제 길드/채널/actor** 로 라우팅해야 한다(A).
 * guildId=0 같은 더미로 보내면 그 길드 풀이 비어 항상 NO_PROVIDER → 휴리스틱 폴백이 된다.
 */
data class OnboardingAnalysisContext(
    val guildId: Long,
    val channelId: Long,
    val actorUserId: Long?,
    val actorRoleIds: Set<Long> = emptySet(),
    val actorIsGuildAdmin: Boolean = true,
)

/**
 * 온보딩 분석의 LLM 호출 경계(I/O 격리). 프롬프트와 라우팅 컨텍스트를 주면 LLM 응답 텍스트를 동기로 돌려주거나,
 * 프로바이더 부재/실패/타임아웃이면 `null` 을 돌려준다(예외 던지지 않음 — 호출부 폴백을 단순화).
 */
fun interface OnboardingLlm {
    fun complete(
        prompt: String,
        context: OnboardingAnalysisContext,
    ): String?
}
