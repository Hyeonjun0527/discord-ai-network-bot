package com.discordassistant.central.participation.application.port.out

import com.discordassistant.central.participation.application.model.ShadowModelCandidate

/**
 * shadow 모델 후보 레지스트리 저장 포트(NEXA-P11-T020, application 레이어).
 *
 * 학습 모델 후보(ID·hash·feature schema·calibration·status)를 영속화한다. 저장소는 등록·상태 갱신·조회만
 * 제공하고, **승인/LIVE 선택 불변식은 application 서비스**([com.discordassistant.central.participation.application.model.ShadowModelRegistry])
 * 가 강제한다(포트는 순수 저장 — 정책은 도메인/application).
 *
 * 순수성 경계: application 레이어 — 도메인/application 값 객체만. Spring/JPA/JDA 미참조(어댑터가 채운다).
 */
interface ShadowModelRegistryPort {
    /** [candidate] 를 저장한다(modelId 신규면 insert, 기존이면 상태 갱신). */
    fun save(candidate: ShadowModelCandidate)

    /** [modelId] 후보를 찾는다(없으면 null). */
    fun find(modelId: String): ShadowModelCandidate?

    /** 모든 후보(등록 시각 최신 우선). */
    fun listAll(): List<ShadowModelCandidate>
}
