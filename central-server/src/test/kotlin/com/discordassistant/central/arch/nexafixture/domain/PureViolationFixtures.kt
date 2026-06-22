package com.discordassistant.central.arch.nexafixture.domain

import jakarta.persistence.Entity
import jakarta.persistence.Id
import net.dv8tion.jda.api.JDA
import org.springframework.stereotype.Service

/**
 * T022 self-test fixture — 의도적 위반.
 *
 * 가짜 NEXA "도메인" 클래스가 JPA/Spring/JDA 같은 프레임워크에 의존한다.
 * `nexaDomainsArePureRule` 빌더를 이 패키지(`..arch.nexafixture.domain..`)에 적용하면 반드시
 * AssertionError 로 실패해야 한다. production NEXA 패키지에는 이런 의존이 없으므로 vacuous pass 다.
 */
@Entity
class FakeConversationEntity(
    @Id val id: Long = 0,
)

/** 도메인이 프레임워크 DI(@Service)에 의존하는 위반. */
@Service
class FakeParticipationDomainService {
    fun jda(): Class<JDA> = JDA::class.java
}
