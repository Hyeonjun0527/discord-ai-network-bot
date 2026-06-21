package com.discordassistant.central.conversation.domain.model.scene

/**
 * 장면 context 버전(NEXA-P05-T018, 순수 도메인 value type·불변). 장면이 **정책 판단을 무효화할 만큼** 바뀔 때마다
 * 단조 증가하는 결정론적 버전이다 — participation 이 이 버전이 그대로면 직전 판단을 재사용할 수 있다(캐시 키).
 *
 * **증가 규칙(acceptance T018)**: 무엇이 바뀌었는지([SceneChange])에 따라 버전을 올릴지 결정한다.
 * - **올린다**: 사람이 답함(새 발화)·주제 전환·삭제 등 **정책 판단을 무효화** 하는 변화([SceneChange.invalidatesPolicy]=true).
 * - **안 올린다**: tempo/metric 갱신처럼 같은 판단을 유지해도 되는 단순 메타 변화(invalidatesPolicy=false).
 *
 * 결정론: 같은 [SceneChange] 시퀀스를 같은 시작 버전에 적용하면 항상 같은 최종 버전이 나온다(replay 안전).
 *
 * 순수성: Spring/JPA/JDA 미참조.
 */
@JvmInline
value class ContextVersion(
    val value: Long,
) {
    init {
        require(value >= 0) { "ContextVersion 은 음수일 수 없다" }
    }

    /**
     * [change] 를 반영한 다음 버전을 돌려준다. 정책을 무효화하는 변화면 +1, 단순 메타 변화면 자신(불변).
     * 결정론적이라 같은 입력 replay 면 같은 결과다(acceptance T018).
     */
    fun apply(change: SceneChange): ContextVersion = if (change.invalidatesPolicy) ContextVersion(value + 1) else this

    companion object {
        /** 장면 최초 버전. */
        val INITIAL: ContextVersion = ContextVersion(0)
    }
}

/**
 * 장면 변화 종류(NEXA-P05-T018, 순수 도메인 enum). 각 변화가 **정책 판단을 무효화** 하는지([invalidatesPolicy])를
 * 고정해 [ContextVersion.apply] 가 결정론적으로 버전을 올릴지 정한다.
 *
 * acceptance: 사람이 답함·주제 전환·삭제는 무효화(=버전↑), metric 갱신만으로는 무효화하지 않는다(=버전 유지).
 */
enum class SceneChange(
    /** 이 변화가 직전 정책 판단을 무효화하는가 — true 면 버전을 올린다. */
    val invalidatesPolicy: Boolean,
) {
    /** 사람이 새로 답함(새 발화/burst finalize) — 대화가 진전돼 판단을 다시 해야 한다. */
    HUMAN_REPLIED(invalidatesPolicy = true),

    /** 주제 전환(topic segment 경계) — 맥락이 바뀌어 직전 판단 무효. */
    TOPIC_SWITCHED(invalidatesPolicy = true),

    /** 메시지 삭제(redaction/tombstone) — 참조하던 근거가 사라져 판단 무효. */
    MESSAGE_DELETED(invalidatesPolicy = true),

    /** 스레드 merge/split 교정 — 스레드 구조가 바뀌어 판단 무효(T011/T012). */
    THREAD_RESTRUCTURED(invalidatesPolicy = true),

    /** tempo/metric 갱신만 — 같은 판단을 유지해도 되는 단순 메타 변화(버전 유지). */
    METRICS_UPDATED(invalidatesPolicy = false),

    /** 참여자 last-seen 등 비정책 메타 갱신 — 판단 무효 아님(버전 유지). */
    PARTICIPANT_METADATA_TOUCHED(invalidatesPolicy = false),
}
