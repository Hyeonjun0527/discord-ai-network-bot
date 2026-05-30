package com.discordassistant.central.provider

/**
 * 프로바이더 가용 시간대 판정(차수 12 #159). UTC 시(0~23) 기준. 자정을 넘는 구간(예: 22~6)도 지원.
 * from/to 중 하나라도 null 이면 "항상 가용"으로 본다.
 */
object AvailabilityWindow {
    /** 현재 시각(hour, 0..23)이 [from, to) 윈도우 안인가. from==to 는 항상 가용으로 간주. */
    fun isWithin(from: Int?, to: Int?, hour: Int): Boolean {
        if (from == null || to == null) return true
        require(from in 0..23 && to in 0..23) { "시(hour)는 0..23" }
        require(hour in 0..23) { "hour 는 0..23" }
        if (from == to) return true // 24시간
        return if (from < to) {
            hour in from until to // 같은 날 구간 [from, to)
        } else {
            hour >= from || hour < to // 자정 넘김 구간(예: 22~6)
        }
    }
}
