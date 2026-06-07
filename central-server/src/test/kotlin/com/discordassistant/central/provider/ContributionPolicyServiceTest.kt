package com.discordassistant.central.provider

import com.discordassistant.central.global.audit.AuditLog
import com.discordassistant.central.provider.application.ContributionPolicyService
import com.discordassistant.central.shared.ModelBurden
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ContributionPolicyService::class, AuditLog::class)
class ContributionPolicyServiceTest
    @Autowired
    constructor(
        val svc: ContributionPolicyService,
    ) {
        @Test
        fun `모델 설정·한도`() {
            svc.setModels(1, listOf("llama3.1:8b", "qwen2.5:7b"), ModelBurden.STANDARD)
            assertEquals(2, svc.policies(1).size)

            svc.setLimit(1, "llama3.1:8b", dailyLimit = 50, maxConcurrency = 2, maxSeconds = 60)
            val p = svc.policies(1).first { it.model == "llama3.1:8b" }
            assertEquals(50, p.dailyLimit)
            assertEquals(2, p.maxConcurrency)
        }

        @Test
        fun `모델 목록 갱신 시 제거된 모델 정책 삭제`() {
            svc.setModels(2, listOf("a", "b"), ModelBurden.LIGHT)
            svc.setModels(2, listOf("b"), ModelBurden.LIGHT)
            assertEquals(listOf("b"), svc.policies(2).map { it.model })
        }
    }
