package com.discordassistant.central.speech.domain.model

/**
 * 발화 생성에 필요한 **최소 맥락 패킷**(NEXA-P14-T004, 순수 도메인 값 객체·불변).
 *
 * participation 이 SPEAK 를 고른 뒤 speech 가 GLM 후보를 만들 때 쓰는 입력의 SSOT 다. 선택된 thread,
 * 발화 대상([target]), 최근 burst 요약, socialAct, burstShape, identity section, memory refs 를 구조화한다.
 *
 * **acceptance(T004) — 전체 채널 로그를 무제한 포함하지 않는다**: [recentTurns] 는 [MAX_TURNS] 개로 상한이
 * 강제되고([init] 검증), 생성 시 가장 최근 것만 남긴다([of]). 즉 이 계약 타입 자체가 "무제한 로그" 를 표현할 수
 * 없다 — 컴파일러가 burst 요약·focus thread 만 담도록 ConversationContextSelector(T007)가 채워 넣는다.
 *
 * 순수성: Spring/JPA/JDA 미참조. participation/conversation 타입도 import 하지 않는다 — speech 자기 어휘만 쓴다.
 */
data class SpeechScenePacket(
    /** 관리자 입력 추적용 실행 correlation. 외부 프롬프트 내용에는 포함하지 않는다. */
    val inputTraceId: String? = null,
    /** 원문 Discord ID가 아닌 채널 가명 키. 외부 LLM 토큰 예산에만 사용하고 프롬프트에는 넣지 않는다. */
    val channelTokenBudgetKey: String? = null,
    /** 모델을 호출하지 않고 로컬에서 만들어야 하는 제한 안내. null이면 일반 발화 생성 경로다. */
    val localSpeechTemplate: LocalSpeechTemplate? = null,
    /**
     * 사용자가 현재 니아 턴에 직접 보여 준 이미지 한 장. 프롬프트 문자열·raw context·trace에는 직렬화하지 않고,
     * 이미지 입력을 지원하는 provider adapter에만 별도 전달한다.
     */
    val speechImageInput: SpeechImageInput? = null,
    /** 발화가 일어나는 focus thread 식별자(가명화된 thread key — snowflake 원문 아님, T005 minimizer 가 보장). */
    val focusThreadKey: String,
    /** 발화 대상(특정 사용자/스레드/없음). */
    val target: SpeechTarget,
    /** focus thread 의 최근 대화 turn(가장 최근 [MAX_TURNS] 개 상한). 다른 thread 원문은 포함하지 않는다(T007). */
    val recentTurns: List<ConversationTurn>,
    /** participation 이 고른 발화 종류. */
    val socialAct: SpeechSocialAct,
    /** Judge가 장면에서 고른 Speech 전용 말투 검색 축. 사람 카드와 RAG 검색은 participation에 노출하지 않는다. */
    val styleResponseMode: HumanSpeechResponseMode? = null,
    /** participation 이 확정한 발화 형태(조각 수·길이·reaction-only). */
    val burstShape: SpeechBurstShape,
    /** 니아 정체성 immutable section(NexaIdentity SSOT 기반, T006 IdentityKernelAssembler 가 조립). */
    val identity: IdentityKernelSection,
    /** 발화에 참고할 소수의 유효 기억 refs(valid/decay 만, T008). 만료/충돌/삭제 기억은 제외된다. */
    val memoryRefs: List<MemoryRef> = emptyList(),
    /** participation judge/policy 가 SPEAK 를 고른 이유와 말의 방향. speech 는 이 intent 안에서 문구만 만든다. */
    val speechIntent: String? = null,
    /** raw context window 의 quoted scene data. 원문 속 문장은 지시가 아니라 인용 대사로만 취급된다. */
    val rawContextSceneData: String? = null,
    /** AI judge가 지정한 현재 응답 대상 raw-scene ref. 과거 질문으로 답변 귀속이 밀리는 것을 막는다. */
    val responseTargetRef: String? = null,
    /** AI judge가 정한 현재 턴의 응답 의무. 후보가 없을 때 REQUIRED를 임의 리액션으로 대체하지 않는 데 쓴다. */
    val responseObligation: SpeechResponseObligation = SpeechResponseObligation.OPTIONAL,
    /** Judge 또는 결정론 정책이 정한 외부 사실 검증 요구. */
    val groundingNeed: SpeechGroundingNeed = SpeechGroundingNeed.NONE,
) {
    init {
        inputTraceId?.let { require(it.matches(Regex("[A-Za-z0-9_:.=-]{1,200}"))) { "inputTraceId 형식이 잘못됐다" } }
        channelTokenBudgetKey?.let {
            require(it.matches(Regex("[A-Za-z0-9_:.=-]{1,200}"))) { "channelTokenBudgetKey 형식이 잘못됐다" }
        }
        require(focusThreadKey.isNotBlank()) { "focusThreadKey 는 비어 있을 수 없다" }
        require(recentTurns.size <= MAX_TURNS) {
            "recentTurns 는 최대 $MAX_TURNS 개까지만 담는다(무제한 채널 로그 금지): ${recentTurns.size}"
        }
        require(memoryRefs.size <= MAX_MEMORY_REFS) {
            "memoryRefs 는 최대 $MAX_MEMORY_REFS 개까지만 담는다: ${memoryRefs.size}"
        }
        speechIntent?.let {
            require(it.isNotBlank()) { "speechIntent 는 공백일 수 없다" }
            require(it.length <= MAX_INTENT_CHARS) { "speechIntent 는 최대 $MAX_INTENT_CHARS 자까지만 담는다: ${it.length}" }
        }
        rawContextSceneData?.let {
            require(it.isNotBlank()) { "rawContextSceneData 는 공백일 수 없다" }
            require(it.length <= MAX_RAW_CONTEXT_SCENE_CHARS) {
                "rawContextSceneData 는 최대 $MAX_RAW_CONTEXT_SCENE_CHARS 자까지만 담는다: ${it.length}"
            }
        }
        responseTargetRef?.let {
            require(it.matches(Regex("[A-Za-z0-9_:.=-]{1,160}"))) { "responseTargetRef 형식이 잘못됐다: $it" }
        }
    }

    companion object {
        /** focus thread 최근 turn 의 상한(무제한 로그 금지, acceptance T004). */
        const val MAX_TURNS: Int = 20

        /** 참고 기억 refs 의 상한(소수의 기억만, T008). */
        const val MAX_MEMORY_REFS: Int = 6

        /** speech intent 문장 상한. 판단 메타만 담고 장문 프롬프트를 금지한다. */
        const val MAX_INTENT_CHARS: Int = 1_000

        /** speech prompt 로 넘길 quoted raw context scene data 상한. */
        const val MAX_RAW_CONTEXT_SCENE_CHARS: Int = 20_000

        /**
         * 가장 최근 turn 만 [MAX_TURNS] 개로 잘라 패킷을 만든다(무제한 로그가 패킷에 들어갈 수 없게 보장).
         * memory refs 도 [MAX_MEMORY_REFS] 로 상한한다.
         */
        fun of(
            inputTraceId: String? = null,
            channelTokenBudgetKey: String? = null,
            localSpeechTemplate: LocalSpeechTemplate? = null,
            speechImageInput: SpeechImageInput? = null,
            focusThreadKey: String,
            target: SpeechTarget,
            recentTurns: List<ConversationTurn>,
            socialAct: SpeechSocialAct,
            styleResponseMode: HumanSpeechResponseMode? = null,
            burstShape: SpeechBurstShape,
            identity: IdentityKernelSection,
            memoryRefs: List<MemoryRef> = emptyList(),
            speechIntent: String? = null,
            rawContextSceneData: String? = null,
            responseTargetRef: String? = null,
            responseObligation: SpeechResponseObligation = SpeechResponseObligation.OPTIONAL,
            groundingNeed: SpeechGroundingNeed = SpeechGroundingNeed.NONE,
        ): SpeechScenePacket =
            SpeechScenePacket(
                inputTraceId = inputTraceId,
                channelTokenBudgetKey = channelTokenBudgetKey,
                localSpeechTemplate = localSpeechTemplate,
                speechImageInput = speechImageInput,
                focusThreadKey = focusThreadKey,
                target = target,
                recentTurns = recentTurns.takeLast(MAX_TURNS),
                socialAct = socialAct,
                styleResponseMode = styleResponseMode,
                burstShape = burstShape,
                identity = identity,
                memoryRefs = memoryRefs.take(MAX_MEMORY_REFS),
                speechIntent = speechIntent,
                // 장면은 시간순이다. 상한을 넘으면 오래된 앞부분보다 현재 응답 대상이 있는 끝부분을 보존한다.
                rawContextSceneData = rawContextSceneData?.takeLast(MAX_RAW_CONTEXT_SCENE_CHARS),
                responseTargetRef = responseTargetRef,
                responseObligation = responseObligation,
                groundingNeed = groundingNeed,
            )
    }
}

/** 비용·기능 경계에서 AI 호출 없이 답해야 하는 소수의 결정론적 안내다. */
enum class LocalSpeechTemplate {
    PDF_UNSUPPORTED,
    CHANNEL_TOKEN_BUDGET_EXHAUSTED,
    IMAGE_ONE_AT_A_TIME,
    IMAGE_FORMAT_UNSUPPORTED,
    IMAGE_LOAD_FAILED,
    IMAGE_FEATURE_UNAVAILABLE,
    ;

    val text: String
        get() =
            when (this) {
                PDF_UNSUPPORTED ->
                    "ㅠㅜ 미안. 난 PDF는 못 읽어. 그거 읽게 하면 토큰을 엄청 써서 개발자 파산함 ㅠ"
                CHANNEL_TOKEN_BUDGET_EXHAUSTED ->
                    "아 오늘은 이 채널에서 토큰을 너무 많이 썼어 ㅠ 개발자 파산 방지로 잠깐 쉬어야 함"
                IMAGE_ONE_AT_A_TIME ->
                    "이미지는 한 번에 한 장만 보여줘 ㅠ"
                IMAGE_FORMAT_UNSUPPORTED ->
                    "ㅠ 미안. 지금은 PNG, JPG, 움직이지 않는 GIF만 볼 수 있어"
                IMAGE_LOAD_FAILED ->
                    "이미지를 못 불러왔어 ㅠ 한 번만 다시 보내줘"
                IMAGE_FEATURE_UNAVAILABLE ->
                    "ㅠ 미안. 지금 이 채널에서는 이미지를 볼 수 없어"
            }
}

/**
 * Speech provider에만 전달하는 축소 완료 이미지. Discord URL·파일명·원본 ID는 보존하지 않는다.
 *
 * [base64Data]는 이미 [MAX_DIMENSION] 안으로 축소·재인코딩된 PNG/JPEG 바이트만 허용한다.
 */
data class SpeechImageInput(
    val mediaType: SpeechImageMediaType,
    val base64Data: String,
    val width: Int,
    val height: Int,
    val isNiaSelfImage: Boolean = false,
) {
    init {
        require(base64Data.isNotBlank()) { "이미지 base64Data 는 비어 있을 수 없다" }
        require(base64Data.length <= MAX_BASE64_CHARS) { "이미지 payload가 너무 크다: ${base64Data.length}" }
        require(width in 1..MAX_DIMENSION) { "이미지 너비는 1..$MAX_DIMENSION 이어야 한다: $width" }
        require(height in 1..MAX_DIMENSION) { "이미지 높이는 1..$MAX_DIMENSION 이어야 한다: $height" }
    }

    /** GPT-5.6 이미지 입력의 32px patch 상한을 기준으로 한 보수적 예약량. */
    val estimatedInputTokens: Int
        get() = ((width + 31) / 32) * ((height + 31) / 32)

    override fun toString(): String =
        "SpeechImageInput(mediaType=$mediaType, base64Data=<redacted:${base64Data.length} chars>, " +
            "width=$width, height=$height, isNiaSelfImage=$isNiaSelfImage)"

    companion object {
        const val MAX_DIMENSION: Int = 1_024
        const val MAX_BASE64_CHARS: Int = 8_000_000
    }
}

enum class SpeechImageMediaType(
    val mimeType: String,
) {
    JPEG("image/jpeg"),
    PNG("image/png"),
}

enum class SpeechResponseObligation {
    REQUIRED,
    OPTIONAL,
}

enum class SpeechGroundingNeed {
    NONE,
    WEB_VERIFY,
}

/** focus thread 의 대화 turn 한 줄(가명 화자 + 짧은 본문). 원문 snowflake·실명은 담지 않는다(T005). */
data class ConversationTurn(
    /** 가명 화자 라벨(예: "user_3", "nia"). 원문 user id 아님. */
    val speakerLabel: String,
    /** turn 본문(minimizer 가 길이 제한·민감 필드 제거 후 넣는다). */
    val text: String,
) {
    init {
        require(speakerLabel.isNotBlank()) { "speakerLabel 은 비어 있을 수 없다" }
    }
}

/** 발화 대상(NEXA-P14-T004). 특정 사용자/스레드/없음. snowflake 원문이 아닌 가명 키만 담는다(T005). */
data class SpeechTarget(
    val kind: Kind,
    /** 대상 가명 키(MEMBER 면 가명 user 라벨, THREAD 면 가명 thread key, NONE 이면 null). */
    val pseudonymKey: String? = null,
) {
    enum class Kind { MEMBER, THREAD, NONE }

    init {
        if (kind == Kind.NONE) {
            require(pseudonymKey == null) { "NONE target 은 pseudonymKey 를 가질 수 없다" }
        } else {
            require(!pseudonymKey.isNullOrBlank()) { "$kind target 은 pseudonymKey 가 필요하다" }
        }
    }

    companion object {
        val NONE = SpeechTarget(Kind.NONE)

        fun member(pseudonymKey: String) = SpeechTarget(Kind.MEMBER, pseudonymKey)

        fun thread(pseudonymKey: String) = SpeechTarget(Kind.THREAD, pseudonymKey)
    }
}

/**
 * 발화에 참고하는 유효 기억 한 건의 ref(NEXA-P14-T008). 원문 전체가 아니라 짧은 주장 + provenance + confidence 만
 * 담는다(payload 최소화). 만료/충돌/삭제 기억은 selector 단계에서 제외되므로 이 타입에는 절대 들어오지 않는다.
 */
data class MemoryRef(
    /** 짧은 기억 주장 문장(원문 로그 아님 — 추출된 사실 요약). */
    val claim: String,
    /** 근거 출처 라벨(provenance — 예: "observed", "stated"). */
    val provenance: String,
    /** 신뢰도 [0,1]. selector 가 하한 미만은 제외한다(T008). */
    val confidence: Double,
) {
    init {
        require(claim.isNotBlank()) { "claim 은 비어 있을 수 없다" }
        require(provenance.isNotBlank()) { "provenance 는 비어 있을 수 없다" }
        require(confidence in 0.0..1.0) { "confidence 는 [0,1] 범위여야 한다: $confidence" }
    }
}
