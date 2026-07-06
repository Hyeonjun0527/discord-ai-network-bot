package com.discordassistant.central.platform.discord.nexa

import com.discordassistant.central.participation.domain.service.sim.SimActor
import com.discordassistant.central.participation.domain.service.sim.SimActorKind
import com.discordassistant.central.participation.domain.service.sim.SimChannelKind
import com.discordassistant.central.participation.domain.service.sim.SimEvent
import com.discordassistant.central.participation.domain.service.sim.SimEventType
import com.discordassistant.central.participation.domain.service.sim.SimScenario

/**
 * "실제 발화 모드" 멀티턴 데모 시나리오(가명·합성, NEXA-P 실제 발화 테스트 페이지).
 *
 * 각 시나리오는 앞 turn 에서 사실/상황이 등장하고, 뒤 turn 에서 NEXA 를 호명해 그 앞 내용을 **참조**해 답하도록
 * 구성된다 — [NexaLiveSpeechService] 가 이전 turn 을 [com.discordassistant.central.speech.application.context
 * .ConversationContextSelector] 로 컨텍스트 주입하고 유효 기억도 함께 주입하므로, 실제 GLM 응답이 앞 대화를
 * "기억하고" 답하는지를 눈으로 볼 수 있다.
 *
 * 호명 burst 는 8초 cooldown 밖으로 충분히 띄워 둔다(시뮬레이터가 첫 mention 을 SPEAK 로 예약하도록).
 * 운영 데이터·고카디널리티 ID 미포함 — 가명 actor/message 라벨만.
 */
object LiveDemoScenarios {
    private val NEXA = SimActor("actor-nexa", SimActorKind.NEXA, "니아")

    fun all(): List<SimScenario> = listOf(dockerMemory(), deployMemory())

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
    ) = SimEvent(
        seq = seq,
        atOffsetMs = atMs,
        type = SimEventType.MESSAGE_CREATE,
        messageId = mid,
        authorId = author,
        content = content,
        mentionsNexa = mention,
    )

    /**
     * 도커 기억 — 앞 turn 에서 "민수가 도커 빌드 캐시 문제" 가 등장, 한참 뒤 호명에서 NEXA 가 그걸 참조해 답한다.
     * 유효 기억(observed)도 함께 주입돼 컨텍스트 + 기억이 같이 작동한다.
     */
    private fun dockerMemory() =
        SimScenario(
            scenarioId = "live-memory-docker",
            title = "도커 기억 — 앞 대화(도커 문제)를 뒤에서 참조해 답함",
            channelKind = SimChannelKind.MEMBER,
            seed = 17001,
            actors = listOf(human("actor-minsu", "민수"), human("actor-suah", "수아"), NEXA),
            events =
                listOf(
                    msg(1, 0, "m-1", "actor-minsu", "아 도커 빌드가 자꾸 캐시 때문에 옛날 걸 가져와서 새 코드가 안 들어가"),
                    msg(2, 20_000, "m-2", "actor-suah", "헐 그거 짜증나지"),
                    msg(3, 70_000, "m-3", "actor-minsu", "@니아 아까 그 도커 문제 어떻게 푸는 게 좋을까?", mention = true),
                    // 예약 SPEAK 의 발사 창(>4s)이 지나도록 후행 이벤트 — NEXA 타이핑(맥락 불변 → 발사 확정).
                    SimEvent(4, 76_000, SimEventType.TYPING_START, authorId = "actor-nexa"),
                ),
        )

    /**
     * 배포 기억 — 앞 turn 에서 자동 배포 맥락이 등장, 뒤 호명에서 NEXA 가 그 맥락을 참조해 답한다.
     */
    private fun deployMemory() =
        SimScenario(
            scenarioId = "live-memory-deploy",
            title = "배포 기억 — 자동 배포 맥락을 기억해 답함",
            channelKind = SimChannelKind.MEMBER,
            seed = 17002,
            actors = listOf(human("actor-jiho", "지호"), NEXA),
            events =
                listOf(
                    msg(1, 0, "m-1", "actor-jiho", "방금 main 에 푸시했는데 이거 자동으로 배포되는 거 맞지?"),
                    msg(2, 75_000, "m-2", "actor-jiho", "@니아 배포 끝났는지 어떻게 확인해?", mention = true),
                    // 예약 SPEAK 의 발사 창(>4s)이 지나도록 후행 이벤트(맥락 불변 → 발사 확정).
                    SimEvent(3, 81_000, SimEventType.TYPING_START, authorId = "actor-nexa"),
                ),
        )
}
