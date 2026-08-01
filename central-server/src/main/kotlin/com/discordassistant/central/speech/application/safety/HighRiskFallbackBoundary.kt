package com.discordassistant.central.speech.application.safety

import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import com.discordassistant.central.speech.domain.model.SpeechSocialAct

/**
 * 고위험 도움 요청 fallback 경계(NEXA-P17-T016, application·무상태).
 *
 * 자해·위기·의료·법률 같은 **고위험 맥락** 에서는 장난성 캐릭터(TEASE 등)보다 **안전한 응답/침묵** 을 우선한다.
 * NEXA 는 전문가가 아니므로 함부로 확신에 찬 조언을 하지 않고, 전문 자원 안내·중립 응답·침묵으로 하강한다.
 *
 * **acceptance(T016) — 고위험 분류 실패 시 과도한 확신이나 조롱이 나오지 않는다**:
 * 분류는 세 상태다 — [RiskLevel.HIGH](명확한 고위험 신호), [RiskLevel.UNCERTAIN](신호 모호·분류 실패),
 * [RiskLevel.LOW](고위험 신호 없음). **분류 실패(UNCERTAIN)는 LOW 로 떨어지지 않고 고위험에 준해 처리** 한다
 * (fail-safe): TEASE 금지 + 확신 억제 + 안전 자원 안내 우선. 즉 "모르겠으면 안전쪽" 이라 오분류가 조롱으로
 * 이어지지 않는다. HIGH 면 더 강하게 — 장난 금지·안전 자원 또는 침묵만 허용한다.
 *
 * 이 경계는 **텍스트를 생성하지 않는다**(canned 문구 필드 없음). 어떤 발화 종류를 금지하고 어떤 안전 directive 로
 * 하강할지의 **결정** 만 낸다([HighRiskDirective]) — 실제 문구/전송은 하류(generation·fallback)가 한다.
 *
 * 순수성 경계: application 레이어 — speech 도메인 값 객체·표준 타입만. Spring/JPA/JDA 미참조.
 */
class HighRiskFallbackBoundary(
    private val classifier: HighRiskClassifier = KeywordHighRiskClassifier(),
) {
    /**
     * [packet] 의 발화 맥락을 평가해 고위험 directive 를 낸다. 분류가 실패/모호하면 안전쪽으로 하강한다(fail-safe).
     */
    fun evaluate(packet: SpeechScenePacket): HighRiskDirective {
        val level =
            runCatching { classifier.classify(packet) }
                // 분류기 예외 = 분류 실패 → UNCERTAIN(LOW 로 떨어뜨리지 않는다 — fail-safe).
                .getOrDefault(RiskLevel.UNCERTAIN)
        return when (level) {
            // 명확한 고위험: 장난 금지·확신 억제. 전문 자원 안내가 가능하면 그것을, 아니면 침묵.
            RiskLevel.HIGH ->
                HighRiskDirective(
                    level = level,
                    forbiddenActs = HIGH_RISK_FORBIDDEN_ACTS,
                    suppressConfidence = true,
                    response = HighRiskResponse.SAFE_RESOURCE,
                )
            // 분류 실패/모호: 고위험에 준해 보수적으로(조롱·확신 차단). 안전 자원으로 하강.
            RiskLevel.UNCERTAIN ->
                HighRiskDirective(
                    level = level,
                    forbiddenActs = HIGH_RISK_FORBIDDEN_ACTS,
                    suppressConfidence = true,
                    response = HighRiskResponse.SAFE_RESOURCE,
                )
            // 고위험 신호 없음: 일반 경로(제약 없음).
            RiskLevel.LOW ->
                HighRiskDirective(
                    level = level,
                    forbiddenActs = emptySet(),
                    suppressConfidence = false,
                    response = HighRiskResponse.NORMAL,
                )
        }
    }

    companion object {
        /** 고위험·분류실패 맥락에서 금지되는 발화 종류 — 장난·정정·이견 같은 공격/경박 행위. */
        val HIGH_RISK_FORBIDDEN_ACTS: Set<SpeechSocialAct> =
            setOf(SpeechSocialAct.TEASE, SpeechSocialAct.DISAGREE, SpeechSocialAct.CORRECT)
    }
}

/**
 * 고위험 맥락 분류기(application 포트). 결정론 구현([KeywordHighRiskClassifier])이 기본이지만, 외부 분류기로 교체
 * 가능하다. 어떤 구현도 **분류 실패는 [RiskLevel.LOW] 가 아니라 [RiskLevel.UNCERTAIN]** 으로 보고해야 한다(계약).
 */
fun interface HighRiskClassifier {
    fun classify(packet: SpeechScenePacket): RiskLevel
}

/**
 * 결정론 키워드 기반 고위험 분류기(application). 현재 발화 결정을 유발한 최신 turn 본문에 자해/위기/의료/법률 신호가 있으면
 * [RiskLevel.HIGH], 분류에 쓸 본문 자체가 없으면(빈 맥락) [RiskLevel.UNCERTAIN], 신호가 없으면 [RiskLevel.LOW].
 *
 * 외부 모델 호출·네트워크 없음(결정론). 운영에서 정교한 분류기로 대체될 수 있으나, 이 기본 구현도 "모르면 안전쪽"
 * 규약을 지킨다.
 */
class KeywordHighRiskClassifier : HighRiskClassifier {
    override fun classify(packet: SpeechScenePacket): RiskLevel {
        val currentTurn =
            packet.recentTurns
                .lastOrNull()
                ?.text
                ?.trim()
        if (currentTurn.isNullOrEmpty()) {
            // 평가할 본문이 없으면 분류 불가 → 안전쪽(UNCERTAIN).
            return RiskLevel.UNCERTAIN
        }
        val lowered = currentTurn.lowercase()
        if (HIGH_RISK_MARKERS.any { lowered.contains(it) }) return RiskLevel.HIGH
        return RiskLevel.LOW
    }

    companion object {
        /** 자해·위기·의료·법률 고위험 신호(한/영). 운영에서 확장 가능. */
        val HIGH_RISK_MARKERS: List<String> =
            listOf(
                // 자해·위기
                "자살",
                "죽고 싶",
                "죽고싶",
                "self harm",
                "self-harm",
                "suicide",
                "kill myself",
                "want to die",
                "해치고 싶",
                "위기",
                "긴급",
                // 의료
                "약을 먹어도",
                "복용량",
                "overdose",
                "진단",
                "처방",
                "응급실",
                // 법률
                "고소",
                "소송",
                "변호사",
                "체포",
                "법적 책임",
            )
    }
}

/** 고위험 분류 결과(application enum). 실패/모호는 [UNCERTAIN] — 절대 [LOW] 로 묵살하지 않는다. */
enum class RiskLevel {
    /** 명확한 고위험 신호. */
    HIGH,

    /** 분류 실패·신호 모호 — 고위험에 준해 보수적으로 처리(fail-safe). */
    UNCERTAIN,

    /** 고위험 신호 없음 — 일반 경로. */
    LOW,
}

/** 고위험 맥락에서 하강할 응답 형태(application enum). canned 문구가 아니라 형태 결정만. */
enum class HighRiskResponse {
    /** 전문 자원 안내·중립 응답(확신 조언 금지). */
    SAFE_RESOURCE,

    /** 일반 경로(제약 없음). */
    NORMAL,
}

/**
 * 고위험 fallback directive(application 값 객체·불변). 어떤 발화 종류를 금지하고 확신을 억제할지, 어떤 안전 응답으로
 * 하강할지의 결정. 텍스트는 담지 않는다(형태·금지 결정만 — acceptance T016).
 */
data class HighRiskDirective(
    /** 분류 결과. */
    val level: RiskLevel,
    /** 이 맥락에서 금지되는 발화 종류(장난·정정·이견 등). */
    val forbiddenActs: Set<SpeechSocialAct>,
    /** 확신에 찬 단정·조언을 억제해야 하는가(고위험·분류실패면 true). */
    val suppressConfidence: Boolean,
    /** 하강할 응답 형태. */
    val response: HighRiskResponse,
) {
    /** 일반 경로인가(제약 없음). */
    val isNormal: Boolean
        get() = response == HighRiskResponse.NORMAL && forbiddenActs.isEmpty() && !suppressConfidence
}
