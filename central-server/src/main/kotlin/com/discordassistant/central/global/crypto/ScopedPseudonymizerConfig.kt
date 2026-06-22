package com.discordassistant.central.global.crypto

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration

/**
 * 부팅 시 scoped 가명화 키(버전별) 주입(NEXA-P02-T010).
 *
 * - `nexa.pseudonym.keys`: `버전=키` 쌍을 콤마로 구분(예: `1=secretA,2=secretB`). 키 회전 시 새 버전 추가.
 * - `nexa.pseudonym.active-version`: 신규 가명에 쓸 기본 키 버전(기본 1).
 *
 * 키 미설정이면 폴백 키로 동작(개발/테스트). 운영은 반드시 키를 설정한다. 키는 로그로 출력하지 않는다.
 */
@Configuration
class ScopedPseudonymizerConfig(
    @param:Value("\${nexa.pseudonym.keys:}") private val rawKeys: String,
    @param:Value("\${nexa.pseudonym.active-version:1}") private val activeVersion: Int,
) {
    @PostConstruct
    fun init() {
        val keys =
            rawKeys
                .split(",")
                .mapNotNull { entry ->
                    val (version, key) = entry.split("=", limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                    version.trim().toIntOrNull()?.let { it to key.trim() }
                }.toMap()
        ScopedPseudonymizer.configure(keys, activeVersion)
    }
}
