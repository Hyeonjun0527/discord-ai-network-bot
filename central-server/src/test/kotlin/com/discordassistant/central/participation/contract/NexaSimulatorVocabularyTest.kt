package com.discordassistant.central.participation.contract

import com.discordassistant.central.participation.domain.model.action.SocialActionKind
import com.discordassistant.central.participation.domain.model.decision.DelayBucket
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * NEXA 이벤트 재생 시뮬레이터(scripts/nexa-simulate.py, NEXA-P16-T001)의 **행동·타이밍 어휘**가 central
 * participation 도메인 enum 과 drift 하지 않는지 지키는 가드.
 *
 * 시뮬레이터는 central 운영 코드를 호출하지 않는(기존 central 무변경 제약) 결정론 레퍼런스 모델이라,
 * 행동 코드(IGNORE/WAIT/REACT/SPEAK/CANCEL_PENDING)와 타이밍 버킷(IMMEDIATE..NEVER)을 Python 상수로 따로
 * 가진다. 이 테스트는 그 Python 상수가 [SocialActionKind.wireName]·[DelayBucket] 와 정확히 일치함을 검증해,
 * 한쪽이 바뀌면(예: 새 action kind 추가) 이 테스트가 실패하도록 한다 — Kotlin↔Python 단일 진실원(drift 가드,
 * PolicyContractGoldenTest 와 같은 패턴).
 *
 * 순수 파일 파싱만 한다(Spring 컨텍스트·DB 없음). central 운영 코드는 일절 수정하지 않는다.
 */
class NexaSimulatorVocabularyTest {
    private val simulatorSource: String by lazy { simulatorFile().readText() }

    @Test
    fun `시뮬레이터 ACTION_KINDS 가 SocialActionKind wireName 집합과 정확히 일치한다`() {
        // ACTION_KINDS = (ACTION_IGNORE, ACTION_WAIT, ...) 는 변수 참조 튜플이라 각 변수를 값으로 해소한다.
        val refs = pythonTupleRefs("ACTION_KINDS")
        val pythonActions = refs.map { pythonStringConst(it) }.toSet()
        val domainActions = SocialActionKind.entries.map { it.wireName }.toSet()
        assertThat(pythonActions)
            .describedAs("nexa-simulate.py ACTION_KINDS 가 SocialActionKind.wireName 과 drift 없음")
            .isEqualTo(domainActions)
    }

    @Test
    fun `시뮬레이터 DELAY_BUCKETS 가 DelayBucket enum 과 정확히 일치한다`() {
        val pythonBuckets = pythonStringTuple("DELAY_BUCKETS")
        val domainBuckets = DelayBucket.entries.map { it.name }.toSet()
        assertThat(pythonBuckets)
            .describedAs("nexa-simulate.py DELAY_BUCKETS 가 DelayBucket 과 drift 없음")
            .isEqualTo(domainBuckets)
    }

    @Test
    fun `시뮬레이터 FAULTS 가 scenario schema fault enum 과 정확히 일치한다`() {
        // 견고성/장애 시나리오(P16-T016~T019)의 주입 결함 코드. central 도메인 enum 이 아니라
        // scenario DSL(schema.json) 의 fault enum 이 SSOT 짝이므로 둘의 drift 를 막는다.
        val pythonFaults = pythonTupleRefs("FAULTS").map { pythonStringConst(it) }.toSet()
        val schemaFaults = schemaFaultEnum()
        assertThat(pythonFaults)
            .describedAs("nexa-simulate.py FAULTS 가 schema.json fault enum 과 drift 없음")
            .isEqualTo(schemaFaults)
    }

    @Test
    fun `시뮬레이터 개별 action 상수가 안정 wireName 과 일치한다`() {
        // 안정 코드(quota-boundary.md: SPEAK 만 generation 소모) — 코드 변경 시 즉시 실패.
        assertThat(pythonStringConst("ACTION_IGNORE")).isEqualTo(SocialActionKind.IGNORE.wireName)
        assertThat(pythonStringConst("ACTION_WAIT")).isEqualTo(SocialActionKind.WAIT.wireName)
        assertThat(pythonStringConst("ACTION_REACT")).isEqualTo(SocialActionKind.REACT.wireName)
        assertThat(pythonStringConst("ACTION_SPEAK")).isEqualTo(SocialActionKind.SPEAK.wireName)
        assertThat(pythonStringConst("ACTION_CANCEL")).isEqualTo(SocialActionKind.CANCEL_PENDING.wireName)
    }

    /** `NAME = (X, Y, Z)` 의 변수 참조(식별자) 집합. */
    private fun pythonTupleRefs(name: String): Set<String> {
        val body = tupleBody(name)
        return IDENTIFIER.findAll(body).map { it.value }.toSet()
    }

    /** `NAME = ("a", "b")` 의 문자열 리터럴 집합. */
    private fun pythonStringTuple(name: String): Set<String> {
        val body = tupleBody(name)
        return STRING_LITERAL.findAll(body).map { it.groupValues[1] }.toSet()
    }

    private fun tupleBody(name: String): String {
        val match = Regex("""^$name\s*=\s*\(([^)]*)\)""", RegexOption.MULTILINE).find(simulatorSource)
        return match?.groupValues?.get(1)
            ?: error("Python tuple $name not found in nexa-simulate.py")
    }

    private fun pythonStringConst(name: String): String {
        val match = Regex("""^$name\s*=\s*"([^"]*)"""", RegexOption.MULTILINE).find(simulatorSource)
        return match?.groupValues?.get(1)
            ?: error("Python string constant $name not found in nexa-simulate.py")
    }

    /** scenario schema.json 의 `"fault": { ... "enum": [ ... ] }` 문자열 집합. */
    private fun schemaFaultEnum(): Set<String> {
        val schema = File("../test-fixtures/nexa/scenarios/schema.json").readText()
        val faultEnumRegex = Regex(""""fault"\s*:\s*\{.*?"enum"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
        val match = faultEnumRegex.find(schema) ?: error("fault enum not found in scenario schema.json")
        val faultBlock = match.groupValues[1]
        return STRING_LITERAL.findAll(faultBlock).map { it.groupValues[1] }.toSet()
    }

    private fun simulatorFile(): File = File("../scripts/nexa-simulate.py")

    companion object {
        private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
        private val STRING_LITERAL = Regex(""""([^"]+)"""")
    }
}
