package com.discordassistant.central.participation.domain.service.state

/**
 * speaking saturation(발화 포화도) 계산기(NEXA-P06-T011, 순수 함수·무상태).
 *
 * 최근 시간 창의 **NEXA burst 수**와 **인간 burst 대비 점유율**로 포화도 [0,1] 을 만든다. 포화도가 높으면 NEXA 가
 * 채널을 과점하고 있다는 관찰 신호다(participation 이 "끼어들지/물러설지" 입력으로 읽음, 행동 결정은 스스로).
 *
 * **acceptance(T011) — 사람 평균 메시지 수가 아니라 burst 수를 사용한다**:
 * 입력은 [nexaBurstCount] 와 [humanBurstCount](둘 다 **burst 수**)다. 메시지 개수/평균이 아니다 — 시그니처가
 * burst 단위임을 강제한다. 점유율 = nexaBurst / (nexaBurst + humanBurst).
 *
 * 순수성: Spring/JPA/JDA 미참조.
 */
object SpeakingSaturationCalculator {
    /**
     * 최근 창의 burst 수로 포화도 [0,1] 을 계산한다.
     *
     * occupancy = nexa / (nexa + human) — NEXA 의 burst 점유율.
     * volume = 1 - exp(-nexa / [volumeScale]) — 절대 발화량 포화(점유율이 높아도 발화가 거의 없으면 saturation 을
     *          억제: 1명만 있는 조용한 채널에서 NEXA 가 1번 말했다고 과포화로 보지 않는다).
     * saturation = occupancy * volume.
     *
     * @param nexaBurstCount 최근 창의 NEXA burst 수.
     * @param humanBurstCount 최근 창의 사람(비봇·비옵트아웃) burst 수.
     * @param volumeScale 절대 발화량 포화 척도(>0).
     */
    fun saturation(
        nexaBurstCount: Int,
        humanBurstCount: Int,
        volumeScale: Double = DEFAULT_VOLUME_SCALE,
    ): Double {
        require(nexaBurstCount >= 0) { "nexaBurstCount 는 음수일 수 없다" }
        require(humanBurstCount >= 0) { "humanBurstCount 는 음수일 수 없다" }
        require(volumeScale > 0.0) { "volumeScale 은 양수여야 한다" }

        val total = nexaBurstCount + humanBurstCount
        if (total == 0) return 0.0
        val occupancy = nexaBurstCount.toDouble() / total.toDouble()
        val volume = 1.0 - kotlin.math.exp(-nexaBurstCount.toDouble() / volumeScale)
        return (occupancy * volume).coerceIn(0.0, 1.0)
    }

    /** 절대 발화량 포화 척도 — 이 burst 수 부근에서 volume 이 충분히 1 에 근접한다. */
    private const val DEFAULT_VOLUME_SCALE = 3.0
}
