package com.discordassistant.central.participation.domain.service

/**
 * "똑똑하게 끼어드는" 결정론적 규칙 게이트(core nia_engine 이식, 순수 도메인 서비스·무상태).
 *
 * **이식 범위 및 원본 파일 매핑**:
 * - `hard_policy.py` → DROP/RESPOND_NOW/CANDIDATE 즉결 (항목 1-27). direct_address_markers 8개 전부 리터럴 반영
 *   ("@니아"는 Discord `mentioned` 플래그, 나머지 7개는 [DIRECT_ADDRESS_MARKERS]). version=[HARD_POLICY_VERSION].
 * - `rules.py`       → clear_wait / clear_speak / clear_silent (항목 28-84). "니아님" 은 NIA_VOCATIVE 경로에도 포함.
 * - `attention_gate.py` → **타이밍 로직 본체까지 이식**: 상수는 [AttentionGateConstants](version=
 *   [AttentionGateConstants.ATTENTION_VERSION]), per-channel 타이밍 결정(pingpong wake·dynamic_idle·min_gap debounce·
 *   typing grace·NO_WAKE-on-nia·WAKE_AFTER_IDLE)은 [ChannelAttentionGate] 로 분리 이식했다. emit 경로가 실제로
 *   consume 한다(NexaParticipationEmitBridge). idle 은 능동 타이머 대신 디바운스 등가로 처리 — 자세한 방식은
 *   [ChannelAttentionGate] KDoc 참조.
 * - `social_context.py` → 미이식 — 관계·기억 레이어로 별도 관심사(participation 결정 규칙 아님)
 * - `config/policies.yaml` → 상수 인라인 (항목 14-24)
 * - `config/attention.yaml` → [AttentionGateConstants] (항목 89-93)
 *
 * **결합 순서**(core 와 동일한 보수성 + LIVE 운영 보정):
 * 1. SILENT(DROP): 봇/시스템 화자 · 빈 메시지
 * 2. WAIT : 이어가는 연결어
 * 3. SPEAK(RESPOND_NOW): 직접 호명(hard_policy 마커 + rules 호격) · 반복된 직접 호출 · reply · continuation
 * 4. WAIT/SILENT: burst 미완성(incomplete/media_pending) · 직전 중복
 * 5. SILENT: 타인 지목 질문 · 타인 @멘션 · 끝난 흐름 · 사적 핑퐁
 * 6. CANDIDATE: 모호 → 기존 정책 분포로 위임
 *
 * 순수성: Spring/JPA/JDA/adapter 미참조(participation.domain 규칙). 표준 타입만.
 */
object CoreInterventionRules {
    // ── Verdict ─────────────────────────────────────────────────────────────────

    /**
     * 결정론 규칙 즉결 결과. [Candidate] 는 "모호 → 기존 정책 분포 위임" 을 뜻한다.
     */
    sealed interface Verdict {
        val reasonCode: String

        /** 즉시 발화 — RESPOND_NOW / rules clear_speak. */
        data class Speak(
            override val reasonCode: String,
        ) : Verdict

        /** 즉시 침묵 — hard_policy DROP / rules clear_silent. */
        data class Silent(
            override val reasonCode: String,
        ) : Verdict

        /** 대기 — rules clear_wait (burst 미완 · 연결어). */
        data class Wait(
            override val reasonCode: String,
        ) : Verdict

        /** 모호 — CANDIDATE / forced_action=None → 기존 정책 분포 위임. */
        data object Candidate : Verdict {
            override val reasonCode: String get() = "RULE_AMBIGUOUS"
        }
    }

    // ── RuleInput ────────────────────────────────────────────────────────────────

    /**
     * 규칙 평가 입력. core `InputMessage`(트리거) + `ConversationState` 를 평탄화한 것.
     *
     * @property triggerText        트리거(마지막) 메시지 본문.
     * @property speakerLabel       트리거 화자 가명 라벨(봇/시스템 판정용). 기본 ""(보수적).
     * @property mentioned          Discord 네이티브 @니아 멘션 여부(hard_policy "@니아" 마커 Discord 형태).
     * @property replyToNia         트리거가 니아 발화에 대한 reply 인가(hard_policy RESPOND_NOW reply).
     * @property niaRecentTokens    니아 직전 발화 토큰(continuation — 없으면 시도 안 함).
     * @property withinContinuationTtl TTL(90s) 내인가(continuation — false 면 시도 안 함).
     * @property burstIncomplete    발화 묶음 미완성(burst_status "incomplete" OR "media_pending").
     * @property duplicateOfPrevHuman 직전 사람 메시지와 완전 중복(hard_policy DROP 중복).
     * @property priorHumanSpeakerLabels 이전 사람 화자 라벨 목록(사적 핑퐁 2-인 판정).
     * @property firstMessageText   대화 첫 메시지 본문(사적 핑퐁 호격 시작 판정).
     * @property conversationMentionsNia 대화 어디서든 니아 호명됐는가(사적 핑퐁 예외).
     */
    data class RuleInput(
        val triggerText: String,
        val speakerLabel: String = "",
        val mentioned: Boolean = false,
        val replyToNia: Boolean = false,
        val niaRecentTokens: List<String> = emptyList(),
        val withinContinuationTtl: Boolean = false,
        val burstIncomplete: Boolean = false,
        val duplicateOfPrevHuman: Boolean = false,
        val priorHumanSpeakerLabels: List<String> = emptyList(),
        val firstMessageText: String? = null,
        val conversationMentionsNia: Boolean = false,
    )

    // ── evaluate ────────────────────────────────────────────────────────────────

    /**
     * 트리거 메시지에 결정론 규칙을 적용해 즉결 또는 위임을 낸다(순수 함수).
     * core 결합 순서(보수적 — 애매하면 Candidate)에서 LIVE 운영 보정 1개를 적용한다:
     * 직접 호명/reply/continuation 같은 명시 호출은 burst/중복 같은 약한 시간 신호보다 강하다.
     * 특히 burst/duplicate 가 직접 호명과 결합하면 "소음" 이 아니라 "응답 실패 후 재호출" 로 분리해 관측한다.
     */
    fun evaluate(input: RuleInput): Verdict {
        val text = input.triggerText
        val trimmed = text.trim()

        // 1-a) 빈 메시지 → SILENT (hard_policy DROP).
        if (trimmed.isEmpty()) {
            return Verdict.Silent("RULE_EMPTY")
        }
        // 1-b) 봇/시스템/니아 자기 메시지 → SILENT (hard_policy DROP + rules RULE_SELF_MESSAGE).
        if (input.speakerLabel.trim().lowercase() in BOT_SPEAKERS) {
            return Verdict.Silent("RULE_SELF_MESSAGE")
        }

        // 2) 이어가는 연결어(rules _has_trailing_connective) → WAIT.
        if (hasTrailingConnective(text)) {
            return Verdict.Wait("RULE_TRAILING_CONNECTIVE")
        }

        // 3-a) Discord @멘션 → SPEAK (hard_policy direct_address "@니아").
        if (input.mentioned) {
            return Verdict.Speak(niaAddressReasonCode(input))
        }
        // 3-b) hard_policy direct_address_markers (policies.yaml — @니아 제외 7개, 인용 미제거로 단순 포함 매칭).
        if (matchesDirectAddressMarker(text)) {
            return Verdict.Speak(niaAddressReasonCode(input))
        }
        // 3-c) rules _mentions_nia (인용문 제외, NIA_VOCATIVE) → SPEAK.
        if (mentionsNia(text)) {
            return Verdict.Speak(niaAddressReasonCode(input))
        }
        // 3-d) 니아 발화에 대한 reply → SPEAK (hard_policy RESPOND_NOW reply).
        if (input.replyToNia) {
            return Verdict.Speak("RULE_REPLY_TO_NIA")
        }
        // 3-e) continuation 토큰 겹침(TTL 내) → SPEAK (hard_policy RESPOND_NOW continuation).
        if (input.niaRecentTokens.isNotEmpty() &&
            input.withinContinuationTtl &&
            continuationOverlaps(input.niaRecentTokens, text)
        ) {
            return Verdict.Speak("RULE_CONTINUATION")
        }

        // 4-a) burst 미완성(incomplete/media_pending) → WAIT.
        if (input.burstIncomplete) {
            return Verdict.Wait("RULE_INCOMPLETE_BURST")
        }
        // 4-b) 직전 사람 메시지와 완전 중복 → SILENT (hard_policy DROP 중복).
        if (input.duplicateOfPrevHuman) {
            return Verdict.Silent("RULE_DUPLICATE")
        }

        // 5-a) 특정 타인 지목 질문 → SILENT (rules clear_silent).
        if (isDirectedQuestionToOther(text)) {
            return Verdict.Silent("RULE_QUESTION_TO_OTHER")
        }
        // 5-b) 특정 타인 @멘션(니아 아님) → SILENT (rules clear_silent).
        if (mentionsOtherUser(text)) {
            return Verdict.Silent("RULE_QUESTION_TO_OTHER")
        }
        // 5-c) 끝난 흐름(작별·해결·감사) → SILENT (rules clear_silent).
        if (endsWithAny(text, RESOLVED_MARKERS)) {
            return Verdict.Silent("RULE_ALREADY_RESOLVED")
        }
        // 5-d) 두 사람만의 사적 핑퐁 → SILENT (rules clear_silent).
        if (isPrivatePingpong(input)) {
            return Verdict.Silent("RULE_PRIVATE_PINGPONG")
        }

        // 6) 모호 — 기존 정책 분포 위임.
        return Verdict.Candidate
    }

    // ── 상수 — policies.yaml hard_policy section (항목 14-21) ───────────────────

    /**
     * hard_policy.py `direct_address_markers`(policies.yaml 의 8개 마커 중 "@니아" 제외 7개).
     * "@니아" 는 Discord 의 `mentioned` 플래그로 이미 처리된다.
     * hard_policy 는 인용 제외 없이 **단순 포함(case-insensitive)** 으로 매칭한다 — rules 의
     * NIA_VOCATIVE(인용 제외 후 검사)와 다른 경로다.
     */
    private val DIRECT_ADDRESS_MARKERS =
        listOf(
            "니아야", // policies.yaml item 2 (rules NIA_VOCATIVE 와 중복이지만 원본 유지)
            "니아 이거", // policies.yaml item 3
            "니아 이거 봐", // policies.yaml item 4
            "니아 봐봐", // policies.yaml item 5
            "니아님", // policies.yaml item 6
            "니아 좀", // policies.yaml item 7
            "니아 어때", // policies.yaml item 8
        )

    // ── 상수 — rules.py (항목 28-84) ────────────────────────────────────────────

    /**
     * 봇/시스템/니아 자신 화자 이름(rules.py `_BOT_SPEAKERS` + policies.yaml `bot_speakers`).
     * 소문자 비교.
     */
    private val BOT_SPEAKERS = setOf("니아", "nia", "봇", "bot", "system", "시스템")

    /**
     * 발화 묶음 미완성 신호(rules.py `_TRAILING_CONNECTIVES`).
     * 마지막 발화가 이걸로 끝나면 WAIT. `!.~ ` 제거 후 매칭.
     */
    private val TRAILING_CONNECTIVES =
        listOf(
            "내 말은", // item 34
            "내말은", // item 35
            "그러니까", // item 36
            "그게 아니라", // item 37
            "일단", // item 38
            "결론은", // item 39
            "근데 그게", // item 40
            "하고 싶은 말은", // item 41
        )

    /**
     * 니아 직접 호격 신호(rules.py `_NIA_VOCATIVE`, 인용문 밖).
     * strip_quotes 후 포함 매칭.
     */
    private val NIA_VOCATIVE =
        listOf(
            "니아야", // item 42
            "니아는", // item 43
            "니아 ", // item 44 (trailing space — "니아 어때" 등 선두 매칭 포함)
            "니아가", // item 45
            "니아도", // item 46
            "니아 어떻게", // item 47
            "니아 생각", // item 48
            // policies.yaml direct_address_markers item 6 "니아님" 을 rules NIA_VOCATIVE 경로에도 반영(인용 제외 매칭).
            // hard_policy DIRECT_ADDRESS_MARKERS 단순 포함과 별개로, @멘션 없는 "니아님 …" 도 인용 밖이면 SPEAK 로 잡는다.
            "니아님",
        )

    /**
     * 흐름 종결 마커(rules.py `_RESOLVED_MARKERS`).
     * `!.~ ㅋㅎ` 제거 후 끝 매칭.
     */
    private val RESOLVED_MARKERS =
        listOf(
            "고마워", // item 49
            "감사", // item 50
            "굿나잇", // item 51
            "잘 자", // item 52
            "잘자", // item 53
            "내일 봐", // item 54
            "내일봐", // item 55
            "수고", // item 56
            "됐다", // item 57
            "해결됐", // item 58
            "알겠어", // item 59
            "오케이", // item 60
            "오케", // item 61
        )

    /**
     * 인용 구간 정규식(rules.py `_strip_quotes`). 인용 부호 내 "니아"는 호명 아님.
     * 인용 부호 집합(item 62): `"'""''「」『』`
     */
    private val QUOTE_RE = Regex("[\"'“”‘’「」『』][^\"“”‘’「」『』']*[\"'“”‘’「」『』]")

    /** 한글/영숫자 토큰 정규식(hard_policy.py `_TOKEN_RE`, item 25). */
    private val TOKEN_RE = Regex("[0-9A-Za-z가-힣]+")

    /** @멘션 정규식(rules.py `_mentions_other_user`, item 78). */
    private val MENTION_RE = Regex("@([^\\s@]+)")

    /**
     * 이름+호격(야/아) 시작 정규식(rules.py `_is_directed_address`, item 76).
     * JVM `\b` 는 ASCII 라 한글 뒤 경계가 안 맞으므로 `(?![가-힣])` 부정 lookahead 로 대체.
     * Python 의 `re.match(r"^([가-힣]{2,4})[야아]\b")` 와 동일 의미.
     */
    private val VOCATIVE_RE = Regex("^([가-힣]{2,4})[야아](?![가-힣])")

    // ── 상수 — continuation (policies.yaml continuation_policy, 항목 22-24) ─────

    /** 니아 직전 발화 후 continuation 인정 TTL (ms). policies.yaml `ttl_ms` = 90000. */
    const val CONTINUATION_TTL_MS: Long = 90_000

    /** continuation 토큰 겹침 최소 개수. policies.yaml `match_count_required` = 1. */
    private const val CONTINUATION_MATCH_COUNT_REQUIRED = 1

    /** continuation 매칭 토큰 최소 길이. policies.yaml `minimum_token_length` = 2. */
    private const val CONTINUATION_MIN_TOKEN_LENGTH = 2

    // ── version strings (추적용 — core SSOT 와 동기화 검증) ─────────────────────────

    /** 이식 기준 hard_policy SSOT 버전(policies.yaml `hard_policy_version`). 규칙 drift 추적용. */
    const val HARD_POLICY_VERSION: String = "hp-1"

    // ── 함수 ────────────────────────────────────────────────────────────────────

    /**
     * hard_policy direct_address_markers 포함 매칭(case-insensitive).
     * policies.yaml `direct_address_markers` 항목 3-8 (항목 2 "@니아"는 `mentioned` 플래그).
     *
     * core hard_policy.py 는 MVP 단순 포함(인용 미제거)이지만, 그 결합 게이트는 이 마커 경로(3-b)를 rules 의
     * 인용 제외 호명(3-c)보다 **먼저** 평가한다. 인용 미제거로 두면 `아까 "니아야 와봐" 라고…` 같은 **인용 안 호명**도
     * SPEAK 로 즉결돼 과발화한다. 안전(덜 발화) 우선이라 여기서도 [stripQuotes] 후 매칭한다 — rules NIA_VOCATIVE 와
     * 인용 처리를 일치시켜(보수적) 인용 안 호명을 Candidate 로 위임한다. core policies.yaml 도 "인용 정교화는 2차"라
     * 했고, 이 일치는 그 2차 정교화를 양 경로에 동일 적용한 것이다.
     */
    private fun matchesDirectAddressMarker(text: String): Boolean {
        val lower = stripQuotes(text).lowercase()
        return DIRECT_ADDRESS_MARKERS.any { it.lowercase() in lower }
    }

    /**
     * 인용 부호로 감싼 구간을 제거(rules.py `_strip_quotes`, 항목 62).
     * 호명 오탐 억제 — 인용 안의 "니아야" 는 호명으로 보지 않는다.
     */
    private fun stripQuotes(text: String): String = QUOTE_RE.replace(text, " ")

    /**
     * 인용문 밖에서 니아를 직접 부르는지(rules.py `_mentions_nia`, 항목 42-48).
     * NIA_VOCATIVE 중 하나가 quote-stripped 텍스트에 포함되는지 확인.
     */
    private fun mentionsNia(text: String): Boolean {
        val cleaned = stripQuotes(text)
        return NIA_VOCATIVE.any { it in cleaned }
    }

    /**
     * burst/duplicate 자체는 나쁜 신호가 아니다. 직접 호명과 결합하면 "미완성/도배"가 아니라
     * "응답 실패 후 재호출" 의미가 강하므로 별도 reason code 로 남긴다.
     */
    private fun niaAddressReasonCode(input: RuleInput): String =
        if (input.burstIncomplete || input.duplicateOfPrevHuman) {
            "RULE_REPEATED_NIA_CALL"
        } else {
            "RULE_NIA_ADDRESSED"
        }

    /**
     * 특정인을 이름+호격(야/아)으로 부르며 시작하는지(rules.py `_is_directed_address`, 항목 76-77).
     * 조건: VOCATIVE_RE 매칭 + group(1) != "니아".
     */
    private fun isDirectedAddress(text: String): Boolean {
        val match = VOCATIVE_RE.find(text.trim()) ?: return false
        return match.groupValues[1] != "니아"
    }

    /**
     * 특정 타인을 호명한 질문인지(rules.py `_is_directed_question_to_other`, 항목 74-75).
     * 조건: isDirectedAddress(text) AND (? in text OR tail.endswith("니","래","까","줘","야?")).
     * tail = text.rstrip() (core 와 동일 — 후행 공백만 제거, `!.~` 는 제거 안 함).
     */
    private fun isDirectedQuestionToOther(text: String): Boolean {
        if (!isDirectedAddress(text)) return false
        val tail = text.trimEnd() // Python text.rstrip() 와 동일(후행 공백 제거)
        return "?" in text ||
            tail.endsWith("니") ||
            tail.endsWith("래") ||
            tail.endsWith("까") ||
            tail.endsWith("줘") ||
            tail.endsWith("야?")
    }

    /**
     * @멘션이 있고 그 대상이 니아가 아닌지(rules.py `_mentions_other_user`, 항목 78-80).
     * 인용 제외. 니아/"nia"/"everyone"/"here" 는 예외.
     */
    private fun mentionsOtherUser(text: String): Boolean {
        val cleaned = stripQuotes(text)
        return MENTION_RE.findAll(cleaned).any { m ->
            val name = m.groupValues[1]
            "니아" !in name && name.lowercase() !in setOf("nia", "everyone", "here")
        }
    }

    /**
     * 마지막 발화가 마커 중 하나로 끝나는지(rules.py `_ends_with_any`, 항목 72).
     * 후행 `!.~ ㅋㅎ` 를 먼저 제거 후 매칭.
     */
    private fun endsWithAny(
        text: String,
        markers: List<String>,
    ): Boolean {
        val tail = text.trimEnd().trimEnd('!', '.', '~', ' ', 'ㅋ', 'ㅎ')
        return markers.any { tail.endsWith(it) }
    }

    /**
     * 마지막 발화가 연결어로 끝나는지(rules.py `_has_trailing_connective`, 항목 73).
     * 후행 `!.~ ` 를 먼저 제거 후 매칭.
     */
    private fun hasTrailingConnective(text: String): Boolean {
        val tail = text.trimEnd().trimEnd('!', '.', '~', ' ')
        return TRAILING_CONNECTIVES.any { tail.endsWith(it) }
    }

    /**
     * 한글/영숫자 토큰만 뽑아 소문자화(hard_policy.py `tokenize`, 항목 25-27).
     * minimumLength 미만 제외.
     */
    private fun tokenize(
        text: String,
        minimumLength: Int,
    ): List<String> =
        TOKEN_RE
            .findAll(text)
            .map { it.value.lowercase() }
            .filter { it.length >= minimumLength }
            .toList()

    /**
     * 니아 직전 발화 토큰과 현재 메시지 토큰 겹침이 임계 이상인지(hard_policy continuation, 항목 22-24).
     */
    private fun continuationOverlaps(
        niaRecentTokens: List<String>,
        text: String,
    ): Boolean {
        val recent =
            niaRecentTokens
                .map { it.lowercase() }
                .filter { it.length >= CONTINUATION_MIN_TOKEN_LENGTH }
                .toSet()
        val current = tokenize(text, CONTINUATION_MIN_TOKEN_LENGTH).toSet()
        return (recent intersect current).size >= CONTINUATION_MATCH_COUNT_REQUIRED
    }

    /**
     * 두 사람만의 사적 핑퐁인지(rules.py private pingpong, 항목 81-84).
     * 조건(보수적): msg>=2 + 화자 정확히 2명 + 대화 전체에서 니아 미호명 + 첫 메시지가 특정인 호격 시작.
     */
    private fun isPrivatePingpong(input: RuleInput): Boolean {
        if (input.conversationMentionsNia) return false // item 83
        val firstText = input.firstMessageText ?: return false // item 84
        val speakers =
            (input.priorHumanSpeakerLabels + input.speakerLabel)
                .filter { it.isNotBlank() }
                .toSet()
        val messageCount = input.priorHumanSpeakerLabels.size + 1
        return messageCount >= 2 &&
            // item 81
            speakers.size == 2 &&
            // item 82
            isDirectedAddress(firstText) // item 84
    }
}

// ── AttentionGateConstants ─────────────────────────────────────────────────────

/**
 * `attention_gate.py` + `config/attention.yaml` 의 모든 임계 숫자·상수(항목 89-107).
 *
 * full 타이밍 로직(decide/idle_due/dynamic_idle_ms)은 [ChannelAttentionGate] 에 이식돼 있고, 이 객체는 그 로직과
 * 상수의 SSOT 다(attention.yaml 값과 1:1). 상수를 한곳에 모아 [ChannelAttentionGate] 와 future 참조가 yaml 재조회
 * 없이 쓰게 한다.
 *
 * 순수 도메인 상수(Spring/JPA/JDA 미참조).
 */
object AttentionGateConstants {
    /** 이식 기준 attention SSOT 버전(attention.yaml `attention_version`). 타이밍 상수 drift 추적용. */
    const val ATTENTION_VERSION: String = "att-1"

    // attention.yaml 값 (항목 89-93)

    /** idle 하한(ms) — 빠른 대화. attention.yaml `idle_min_ms`. */
    const val IDLE_MIN_MS: Int = 2_000

    /** idle 상한(ms) — 느린 대화. attention.yaml `idle_max_ms`. */
    const val IDLE_MAX_MS: Int = 7_000

    /** 핑퐁 창(ms) — 니아 직전 발화 후 이 시간 내 응답이면 WAKE_NOW. attention.yaml `pingpong_window_ms`. */
    const val PINGPONG_WINDOW_MS: Int = 20_000

    /** debounce 최소 간격(ms) — 이보다 짧으면 WAIT. attention.yaml `min_gap_ms`. */
    const val MIN_GAP_MS: Int = 1_500

    /** typing 유예(ms) — typing 이벤트 후 이 시간까지 "작성 중". attention.yaml `typing_grace_ms`. */
    const val TYPING_GRACE_MS: Int = 4_000

    // attention_gate.py 내부 상수 (항목 105)

    /** 채널 템포 추정에 쓰는 최근 간격 표본 수 상한. attention_gate.py `_GAP_WINDOW`. */
    const val GAP_WINDOW: Int = 8

    // Gate 결과 코드 (항목 85-88) — 문자열 상수로만 보존; const 는 KDoc 없이 선언
    const val WAKE_NOW: String = "WAKE_NOW"
    const val WAIT: String = "WAIT"
    const val WAKE_AFTER_IDLE: String = "WAKE_AFTER_IDLE"
    const val NO_WAKE: String = "NO_WAKE"
}
