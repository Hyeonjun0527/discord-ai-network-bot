package com.discordassistant.central.speech.application.port.out

/**
 * speech 가 발화 장면(focus thread 주변 대화)을 **읽기만** 하는 아웃바운드 포트(NEXA-P14-T007).
 *
 * conversation 도메인의 scene/burst 투영을 speech 어휘([RawThreadTurn])로 읽는다 — 구현 adapter 안에서만
 * conversation 타입을 참조하고 speech application/domain 은 conversation 타입을 import 하지 않는다(순수성).
 * 포트는 focus thread 의 turn 만 노출할 책임을 진다(다른 thread 원문 혼입 방지는 selector T007 가 재확인).
 */
interface SceneContextReadPort {
    /**
     * [focusThreadKey] 스레드의 최근 turn 을 시간순(과거→현재)으로 돌려준다. 최대 [limit] 개. 가명 화자 라벨과
     * 최소화된 본문만 담는다(snowflake·실명 없음 — T005 minimizer 가 보장).
     */
    fun recentTurns(
        focusThreadKey: String,
        limit: Int,
    ): List<RawThreadTurn>
}

/** 읽어 온 thread turn 원소(NEXA-P14-T007). 어느 thread 의 turn 인지(threadKey)와 가명 화자·본문. */
data class RawThreadTurn(
    /** 이 turn 이 속한 thread 의 가명 키. selector 가 focus thread 외 turn 을 거르는 기준. */
    val threadKey: String,
    /** 가명 화자 라벨(원문 user id 아님). */
    val speakerLabel: String,
    /** turn 본문. */
    val text: String,
) {
    init {
        require(threadKey.isNotBlank()) { "threadKey 는 비어 있을 수 없다" }
        require(speakerLabel.isNotBlank()) { "speakerLabel 은 비어 있을 수 없다" }
    }
}
