package com.discordassistant.central.participation.application.port.out

import com.discordassistant.central.participation.application.reporting.ShadowDailyReport
import java.time.LocalDate

/**
 * shadow 일일 리포트 저장 아웃바운드 포트(NEXA-P09-T021, application 레이어). 하루치 정책별 집계 리포트를 **원문
 * 없이**(집계 수치만) 영속화·조회한다.
 *
 * 순수성 경계: application — 도메인/리포트 값 객체·표준 타입만. Spring/JPA/JDA 미참조(어댑터가 채운다).
 */
interface ShadowDailyReportStorePort {
    /** 하루치 리포트를 저장한다. 같은 [ShadowDailyReport.date] 재저장은 멱등(덮어쓰기 — 재집계 재현). */
    fun save(report: ShadowDailyReport)

    /** [date] 의 리포트를 돌려준다(없으면 null). */
    fun findByDate(date: LocalDate): ShadowDailyReport?
}
