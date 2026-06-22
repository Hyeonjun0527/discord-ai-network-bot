package com.discordassistant.central.participation.application.port.out

import com.discordassistant.central.participation.application.reporting.PolicyDailyInput
import java.time.LocalDate

/**
 * shadow 일일 리포트 **입력 소스** 아웃바운드 포트(NEXA-P09-T021, application 레이어). 하루치 정책별 집계 입력
 * ([PolicyDailyInput])을 모은다 — 예측 store·관찰 창에서 어댑터가 채운다.
 *
 * 순수성 경계: application — 리포트 값 객체·표준 타입만. Spring/JPA/JDA 미참조(어댑터가 구현).
 */
fun interface ShadowDailyInputSource {
    /** [date] 의 정책별 집계 입력을 돌려준다(없으면 빈 리스트 — 단정 금지). */
    fun collectFor(date: LocalDate): List<PolicyDailyInput>
}
