package com.discordassistant.central.speech.application.privacy

import com.discordassistant.central.speech.domain.model.SpeechScenePacket

/**
 * 외부(GLM) payload minimizer(NEXA-P14-T005, security·application/privacy).
 *
 * GLM 으로 보내는 user 맥락 문자열을 **생성에 꼭 필요한 최소 맥락**으로 줄이고, 옵트아웃/비대상 thread/민감
 * 필드를 제거하며 pseudonym replacement 를 보증한다(observable-state-policy). 패킷은 이미 selector(T007/T008)와
 * 계약 상한(T004)으로 최소화돼 있으나, minimizer 는 **마지막 방어선**으로 잔여 snowflake·API key·시크릿 패턴을
 * 스크럽한다.
 *
 * **acceptance(T005) — golden payload 에 Discord snowflake·API key·제외 content 가 없다**:
 * [minimizeUserPayload] 결과는 [SNOWFLAKE_RE]·[secret 패턴] 매칭이 0 이어야 한다([assertClean] 가 self-check).
 * 가명 키만 통과하고 17~20자리 raw id, sk-/Bearer/AIza 류 토큰은 가명/마스킹으로 치환된다.
 */
class ExternalPayloadMinimizer {
    /**
     * 패킷에서 외부로 보낼 **최소 user 맥락**을 만든다. focus thread turn·소수 memory ref·target 가명 키만 담고,
     * 모든 문자열에 [scrub] 를 적용해 잔여 snowflake·시크릿을 제거한다.
     */
    fun minimizeUserPayload(packet: SpeechScenePacket): String =
        buildString {
            appendLine("[장면]")
            appendLine("focus_thread: ${scrub(packet.focusThreadKey)}")
            val target =
                packet.target.pseudonymKey?.let { scrub(it) } ?: "none"
            appendLine("target: $target")
            appendLine()
            appendLine("[최근 대화]")
            if (packet.recentTurns.isEmpty()) {
                appendLine("(없음)")
            } else {
                packet.recentTurns.forEach { turn ->
                    appendLine("${scrub(turn.speakerLabel)}: ${scrub(turn.text)}")
                }
            }
            if (packet.memoryRefs.isNotEmpty()) {
                appendLine()
                appendLine("[참고 기억]")
                packet.memoryRefs.forEach { ref ->
                    appendLine("- ${scrub(ref.claim)} (근거: ${scrub(ref.provenance)})")
                }
            }
        }.trim()

    /**
     * 한 문자열에서 외부로 새면 안 되는 패턴을 마스킹한다: Discord snowflake(17~20자리 숫자), 흔한 API key/토큰
     * 패턴(sk-…, AIza…, Bearer …). 가명 라벨(user_3 등)은 숫자가 짧아 영향받지 않는다.
     */
    fun scrub(text: String): String =
        text
            .replace(BEARER_RE, "[redacted-token]")
            .replace(API_KEY_RE, "[redacted-key]")
            .replace(SNOWFLAKE_RE, "[redacted-id]")

    /**
     * self-check(acceptance T005): 최소화된 payload 에 snowflake·시크릿이 없는지 검증한다. 발견 시 메시지에 패턴을
     * 노출하지 않고 boolean 만 돌려준다(테스트가 golden fixture 로 0 매칭을 보증).
     */
    fun isClean(payload: String): Boolean =
        !SNOWFLAKE_RE.containsMatchIn(payload) &&
            !API_KEY_RE.containsMatchIn(payload) &&
            !BEARER_RE.containsMatchIn(payload)

    companion object {
        /** Discord snowflake: 17~20자리 연속 숫자(가명 user_3 등은 미해당). */
        val SNOWFLAKE_RE = Regex("""\b\d{17,20}\b""")

        /** 흔한 API key 토큰: sk-…(OpenAI 류), AIza…(Google 류). */
        val API_KEY_RE = Regex("""\b(?:sk-[A-Za-z0-9]{16,}|AIza[A-Za-z0-9_\-]{16,})\b""")

        /** Authorization Bearer 토큰. */
        val BEARER_RE = Regex("""(?i)Bearer\s+[A-Za-z0-9._\-]{8,}""")
    }
}
