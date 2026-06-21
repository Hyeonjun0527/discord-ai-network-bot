package com.discordassistant.central.arch.nexafixture.existingdomain

import com.discordassistant.central.arch.nexafixture.nexaadapter.FakeSpeechPersistenceAdapter

/**
 * T023 self-test fixture — 기존 도메인이 NEXA adapter 내부 구현을 직접 참조하는 위반.
 *
 * module-dag.md: "기존 도메인은 NEXA를 모른다." NEXA 소비는 공개 application port 로만 한다.
 * 이 가짜 기존 도메인은 NEXA adapter 내부(`..nexaadapter..`)를 직접 import 하므로
 * `existingDomainsDoNotReachIntoNexaAdapterRule` 적용 시 AssertionError 로 실패한다.
 */
class FakeRoutingService {
    fun reachIn(): FakeSpeechPersistenceAdapter = FakeSpeechPersistenceAdapter()
}
