package com.discordassistant.central.participation.domain.model.state

/**
 * response confidence(응답 신뢰도) 상태(NEXA-P06-T014, 순수 도메인 값 객체·불변).
 *
 * 세 신뢰 신호를 결합한 정책 feature [0,1] 를 만든다 — 대화 대상 해석([addresseeConfidence]), 사실성
 * ([factualityConfidence]), 기억 신뢰도([memoryConfidence]). NEXA 자기 상태이며 사람을 추론하지 않는다.
 *
 * **acceptance(T014) — 낮은 confidence 가 반드시 침묵은 아니며 정책 입력으로만 제공된다**:
 * 이 객체는 결합 [confidence] 수치만 노출하고 "침묵하라/응답하라" 같은 행동 결정을 내리지 않는다([dictatesSilence]
 * 항상 false 가드). participation 정책이 이 값을 입력으로 읽어 "되묻기·헤지·확인 요청" 등 다양한 행동을 선택할 수 있다
 * (낮음 = 침묵 강제 아님, socialmemory/participation 불변식: 상태는 행동을 결정하지 않는다).
 *
 * 순수성: Spring/JPA/JDA 미참조.
 */
data class ResponseConfidence(
    /** 대화 대상(누구에게 말하는지) 해석 신뢰도 [0,1]. */
    val addresseeConfidence: Double,
    /** 사실성(아는 것을 정확히 말할 수 있는지) 신뢰도 [0,1]. */
    val factualityConfidence: Double,
    /** 기억(이 사람·맥락에 대한 기억) 신뢰도 [0,1]. */
    val memoryConfidence: Double,
    /** 세 신호 결합 가중치(주입 — 순수 함수가 상태를 갖지 않도록). */
    val weights: ConfidenceWeights = ConfidenceWeights.DEFAULT,
) {
    init {
        require(addresseeConfidence in 0.0..1.0) { "addresseeConfidence 는 [0,1] 범위여야 한다" }
        require(factualityConfidence in 0.0..1.0) { "factualityConfidence 는 [0,1] 범위여야 한다" }
        require(memoryConfidence in 0.0..1.0) { "memoryConfidence 는 [0,1] 범위여야 한다" }
        require(!dictatesSilence) {
            "ResponseConfidence 는 행동(침묵 여부)을 결정하지 않고 정책 입력일 뿐이다(acceptance T014)"
        }
    }

    /**
     * 행동(침묵)을 강제하는가 — **항상 false**. 낮은 confidence 가 침묵을 뜻하지 않으며 정책 입력일 뿐이라는
     * 불변식의 가드다(acceptance T014).
     */
    val dictatesSilence: Boolean
        get() = false

    /**
     * 가중 결합 신뢰도 [0,1]. 세 신호의 가중 평균(가중치 합으로 정규화). 정책은 이 값을 입력으로만 쓴다 — 임계값을
     * 넘으면 침묵하라는 식의 의무가 아니다(다양한 행동 선택 가능).
     */
    val confidence: Double
        get() {
            val w = weights
            val total = w.addressee + w.factuality + w.memory
            if (total == 0.0) return 0.0
            val sum =
                addresseeConfidence * w.addressee +
                    factualityConfidence * w.factuality +
                    memoryConfidence * w.memory
            return (sum / total).coerceIn(0.0, 1.0)
        }
}

/**
 * response confidence 결합 가중치(NEXA-P06-T014, 순수 value object·주입). 신호별 상대 중요도를 정한다.
 */
data class ConfidenceWeights(
    val addressee: Double = 1.0,
    val factuality: Double = 1.0,
    val memory: Double = 1.0,
) {
    init {
        require(addressee >= 0.0) { "addressee 가중치는 음수일 수 없다" }
        require(factuality >= 0.0) { "factuality 가중치는 음수일 수 없다" }
        require(memory >= 0.0) { "memory 가중치는 음수일 수 없다" }
    }

    companion object {
        val DEFAULT: ConfidenceWeights = ConfidenceWeights()
    }
}
