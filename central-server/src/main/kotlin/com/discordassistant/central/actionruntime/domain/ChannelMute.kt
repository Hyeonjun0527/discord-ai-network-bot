package com.discordassistant.central.actionruntime.domain

/**
 * 채널별 mute 의 **순수 결정 코어**(NEXA-P18-T014, 순수 도메인 서비스).
 *
 * 길드 단위 [GuildKillSwitch] 보다 **세밀한** 채널 단위 정지다. 운영자/관리자가 특정 채널에서 NEXA 를 끄되, 두
 * 수준을 **분리**한다(deliverable T014 — "발화만 끄기"와 "관찰·저장까지 끄기"를 분리):
 *
 * | mute 수준 | 정책 평가·발화 | 신규 event store append(관찰·저장) |
 * | --- | --- | --- |
 * | [ChannelMuteLevel.NONE] | 허용 | 허용 |
 * | [ChannelMuteLevel.SPEECH_ONLY] | **차단**(발화·예약·전송 정지) | 허용(계속 관찰·기록) |
 * | [ChannelMuteLevel.OBSERVE_AND_SPEECH] | **차단** | **차단**(신규 append 부터 막음) |
 *
 * **acceptance(T014) — 관찰 중단은 새 event store append 부터 차단한다**: [allowsObservationAppend] 는
 * [ChannelMuteLevel.OBSERVE_AND_SPEECH] 에서만 false 다. 즉 OBSERVE_AND_SPEECH 채널은 conversation 수집 경계가
 * 신규 정규화 이벤트를 **적재하지 않는다**(append-only 스트림에 새 행이 안 들어간다). 이미 적재된 과거 이벤트는
 * 보존한다(삭제는 별도 동의 철회/redaction 경로 — 단순 mute 로 과거를 지우지 않는다).
 *
 * **발화/관찰 분리의 안전 의미**: SPEECH_ONLY 는 "조용히 시키되 계속 듣기"(나중에 다시 켤 때 맥락 보존),
 * OBSERVE_AND_SPEECH 는 "완전히 손 떼기"(신규 데이터도 안 받음)다. 더 강한 수준이 약한 수준을 포함한다
 * (OBSERVE_AND_SPEECH 면 발화도 당연히 차단).
 *
 * 순수성: Spring/JPA/JDA 미참조. 표준 타입만. 활성 집합은 호출자(서비스)가 SSOT 에서 로드해 넘긴다.
 */
object ChannelMute {
    /**
     * [channelPseudonym] 의 현재 mute 수준을 [activeMutes] 에서 찾는다(없으면 [ChannelMuteLevel.NONE]). 활성 집합은
     * 호출자(서비스)가 SSOT 에서 로드해 넘긴다 — 순수 조회.
     */
    fun levelOf(
        channelPseudonym: String,
        activeMutes: Map<String, ChannelMuteLevel>,
    ): ChannelMuteLevel = activeMutes[channelPseudonym] ?: ChannelMuteLevel.NONE

    /**
     * [channelPseudonym] 에서 정책 평가·발화·예약·전송이 허용되는가. [ChannelMuteLevel.NONE] 만 true —
     * SPEECH_ONLY·OBSERVE_AND_SPEECH 둘 다 발화를 막는다.
     */
    fun allowsSpeech(
        channelPseudonym: String,
        activeMutes: Map<String, ChannelMuteLevel>,
    ): Boolean = levelOf(channelPseudonym, activeMutes).allowsSpeech

    /**
     * [channelPseudonym] 에서 **신규 event store append**(관찰·저장)가 허용되는가(acceptance T014).
     * [ChannelMuteLevel.OBSERVE_AND_SPEECH] 만 false — 나머지는 계속 관찰·기록한다.
     */
    fun allowsObservationAppend(
        channelPseudonym: String,
        activeMutes: Map<String, ChannelMuteLevel>,
    ): Boolean = levelOf(channelPseudonym, activeMutes).allowsObservationAppend
}

/**
 * 채널 mute 수준(NEXA-P18-T014, 순수 도메인 enum). 발화 차단과 관찰·저장 차단을 분리한다(deliverable T014).
 */
enum class ChannelMuteLevel {
    /** mute 없음 — 정책 평가·발화·관찰·저장 모두 정상. */
    NONE,

    /** 발화만 끔 — 정책 평가·발화·예약·전송은 멈추되 신규 관찰·event store append 는 계속한다(맥락 보존). */
    SPEECH_ONLY,

    /** 관찰·저장까지 끔 — 발화 차단에 더해 신규 event store append 부터 막는다(완전히 손 뗌). */
    OBSERVE_AND_SPEECH,
    ;

    /** 정책 평가·발화·예약·전송 허용 여부. [NONE] 만 true(두 mute 수준 모두 발화 차단). */
    val allowsSpeech: Boolean
        get() = this == NONE

    /** 신규 event store append(관찰·저장) 허용 여부. [OBSERVE_AND_SPEECH] 만 false(acceptance T014). */
    val allowsObservationAppend: Boolean
        get() = this != OBSERVE_AND_SPEECH
}
