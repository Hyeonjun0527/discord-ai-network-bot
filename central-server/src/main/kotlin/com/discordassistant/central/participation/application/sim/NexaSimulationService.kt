package com.discordassistant.central.participation.application.sim

import com.discordassistant.central.participation.domain.service.sim.NexaSimulator
import com.discordassistant.central.participation.domain.service.sim.SimActor
import com.discordassistant.central.participation.domain.service.sim.SimActorKind
import com.discordassistant.central.participation.domain.service.sim.SimChannelKind
import com.discordassistant.central.participation.domain.service.sim.SimEvent
import com.discordassistant.central.participation.domain.service.sim.SimEventType
import com.discordassistant.central.participation.domain.service.sim.SimResult
import com.discordassistant.central.participation.domain.service.sim.SimScenario
import com.discordassistant.central.participation.domain.service.sim.SimScenarioException
import org.springframework.stereotype.Service

/**
 * 어드민 "NEXA 테스트" 시뮬레이션 application 서비스(어드민 대시보드 전용).
 *
 * 합성·가명 시나리오(메시지 이벤트 시퀀스)를 [NexaSimulator] 로 재생해 이벤트별 NEXA 결정을 돌려준다.
 *
 * **shadow only 보장(다층)**:
 * 1. [NexaSimulator] 는 순수 함수 — Discord/GLM 전송 port 를 주입받지 않으므로 호출 경로가 **존재하지 않는다**.
 * 2. 결과 [SimResult.sends] 는 항상 0(구조적 불변식). 이 서비스는 실행 후 [requireNoSend] 로 한 번 더 단언한다
 *    (회귀 방지: 누군가 시뮬레이터에 전송을 추가하면 즉시 터진다).
 * 3. 사전 정의 시나리오는 운영 데이터가 아니라 코드에 박힌 가명 합성 케이스다(원문/고카디널리티 ID 미노출).
 */
@Service
class NexaSimulationService {
    /** 사전 정의 시나리오 목록(선택 실행용 메타). 결정 로직을 실행하지 않고 카드/드롭다운을 채운다. */
    fun listPredefined(): List<PredefinedScenario> =
        PREDEFINED.values.map { PredefinedScenario(it.scenarioId, it.title, it.channelKind.name) }

    /** 사전 정의 시나리오를 재생한다. 알 수 없는 id 면 [SimScenarioException](→400). */
    fun runPredefined(scenarioId: String): SimResult {
        val scenario = PREDEFINED[scenarioId] ?: throw SimScenarioException("알 수 없는 시나리오: $scenarioId")
        return run(scenario)
    }

    /** 임의(직접 입력) 시나리오를 재생한다. 검증은 [SimScenario] 생성자가 수행(잘못된 입력 → 400). */
    fun run(scenario: SimScenario): SimResult {
        val result = NexaSimulator(scenario).run()
        requireNoSend(result)
        return result
    }

    /** shadow 불변식 단언: 시뮬레이션은 실제 전송을 0 회 한다(회귀 가드). */
    private fun requireNoSend(result: SimResult) {
        check(result.sends == 0) { "시뮬레이션은 shadow only — 실제 전송이 0 이어야 한다(sends=${result.sends})" }
    }

    companion object {
        private val NEXA = SimActor("actor-nexa", SimActorKind.NEXA, "니아")

        // 사전 정의 시나리오는 NEXA·헬퍼를 참조하므로, companion <clinit> 순서상 이 val 들 뒤에 둔다
        // (forward-reference 시 null 참조 NPE 방지).
        private val PREDEFINED: Map<String, SimScenario> =
            listOf(
                seriousDirectQuestion(),
                silentServer(),
                alreadyAnswered(),
                mentionSpam(),
                editDelete(),
                rateLimitFault(),
            ).associateBy { it.scenarioId }

        private fun human(
            id: String,
            name: String,
        ) = SimActor(id, SimActorKind.HUMAN, name)

        private fun msg(
            seq: Int,
            atMs: Long,
            mid: String,
            author: String,
            content: String,
            mention: Boolean = false,
            thread: String? = null,
        ) = SimEvent(
            seq,
            atMs,
            SimEventType.MESSAGE_CREATE,
            messageId = mid,
            authorId = author,
            content = content,
            mentionsNexa = mention,
            threadId = thread,
        )

        // 진지한 직접 질문 — 첫 mention 에 정확히 한 번 답한다(over-conservative IGNORE 점검).
        private fun seriousDirectQuestion() =
            SimScenario(
                scenarioId = "serious-direct-question",
                title = "진지한 직접 질문 — 정확히 한 번 답함",
                channelKind = SimChannelKind.MEMBER,
                seed = 16006,
                actors = listOf(human("actor-asker", "민수"), NEXA),
                events =
                    listOf(
                        msg(1, 0, "m-1", "actor-asker", "@니아 리스트 중복 제거 깔끔하게 어떻게 해?", mention = true),
                        SimEvent(2, 8_000, SimEventType.TYPING_START, authorId = "actor-nexa"),
                    ),
            )

        // 조용한 서버 — 호명 없는 잡담엔 대부분 침묵(먼저 안 나섬).
        private fun silentServer() =
            SimScenario(
                scenarioId = "silent-server",
                title = "조용한 서버 — 호명 없으면 침묵",
                channelKind = SimChannelKind.MEMBER,
                seed = 16001,
                actors = listOf(human("actor-a", "지호"), human("actor-b", "서연"), NEXA),
                events =
                    listOf(
                        msg(1, 0, "m-1", "actor-a", "오늘 점심 뭐 먹지"),
                        msg(2, 30_000, "m-2", "actor-b", "나는 김밥"),
                        msg(3, 70_000, "m-3", "actor-a", "ㅋㅋ 좋네"),
                    ),
            )

        // 이미 답함 — 예약 SPEAK 전에 다른 사람이 답하면 취소(중복 답변 회피).
        private fun alreadyAnswered() =
            SimScenario(
                scenarioId = "already-answered",
                title = "이미 답함 — 사람이 먼저 답하면 취소",
                channelKind = SimChannelKind.MEMBER,
                seed = 16003,
                actors = listOf(human("actor-asker", "민수"), human("actor-helper", "수아"), NEXA),
                events =
                    listOf(
                        msg(1, 0, "m-1", "actor-asker", "@니아 도커 빌드 캐시 어떻게 비워?", mention = true),
                        msg(2, 2_000, "m-2", "actor-helper", "docker builder prune 하면 돼"),
                    ),
            )

        // mention spam — burst 안 반복 mention 에 1:1 로 응답하지 않는다(과반응 방지).
        private fun mentionSpam() =
            SimScenario(
                scenarioId = "mention-spam",
                title = "멘션 도배 — 1:1 응답 안 함",
                channelKind = SimChannelKind.MEMBER,
                seed = 16002,
                actors = listOf(human("actor-spammer", "노이즈"), NEXA),
                events =
                    listOf(
                        msg(1, 0, "m-1", "actor-spammer", "@니아 안녕", mention = true),
                        msg(2, 1_000, "m-2", "actor-spammer", "@니아 거기있어?", mention = true),
                        msg(3, 2_000, "m-3", "actor-spammer", "@니아 야", mention = true),
                        msg(4, 3_000, "m-4", "actor-spammer", "@니아 대답좀", mention = true),
                    ),
            )

        // edit/delete — 예약 SPEAK 대상이 삭제되면 발사 금지(취소).
        private fun editDelete() =
            SimScenario(
                scenarioId = "edit-delete",
                title = "수정·삭제 — 대상 삭제 시 발사 취소",
                channelKind = SimChannelKind.MEMBER,
                seed = 16004,
                actors = listOf(human("actor-asker", "민수"), NEXA),
                events =
                    listOf(
                        msg(1, 0, "m-1", "actor-asker", "@니아 이 코드 왜 느려?", mention = true),
                        SimEvent(2, 1_000, SimEventType.MESSAGE_DELETE, messageId = "m-1"),
                    ),
            )

        // 결함 주입(rate limit) — 발화 차단 시 침묵 fallback(다른 채널 fallback 없음).
        private fun rateLimitFault() =
            SimScenario(
                scenarioId = "rate-limit-fault",
                title = "결함 주입(rate limit) — 침묵 fallback",
                channelKind = SimChannelKind.MEMBER,
                seed = 16010,
                actors = listOf(human("actor-asker", "민수"), NEXA),
                events =
                    listOf(
                        msg(1, 0, "m-1", "actor-asker", "@니아 배포 어떻게 해?", mention = true),
                        SimEvent(2, 500, SimEventType.FAULT_INJECT, fault = NexaSimulator.FAULT_RATE_LIMIT),
                    ),
            )
    }
}

/** 사전 정의 시나리오 메타(드롭다운/카드 표시용 — 결정 로직 미실행). */
data class PredefinedScenario(
    val scenarioId: String,
    val title: String,
    val channelKind: String,
)
