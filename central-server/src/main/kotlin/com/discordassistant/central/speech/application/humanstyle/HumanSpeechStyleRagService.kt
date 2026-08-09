package com.discordassistant.central.speech.application.humanstyle

import com.discordassistant.central.speech.application.port.out.HumanSpeechStyleExampleStorePort
import com.discordassistant.central.speech.application.port.out.HumanSpeechStyleRagPort
import com.discordassistant.central.speech.application.port.out.SpeechStyleEmbeddingPort
import com.discordassistant.central.speech.domain.model.HumanSpeechResponseMode
import com.discordassistant.central.speech.domain.model.HumanSpeechSceneTrait
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleExample
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleMatch
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleProviderStyleCue
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleResponseMove
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleRhythmCue
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleSelection
import com.discordassistant.central.speech.domain.model.SpeechScenePacket
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 현재 Speech 장면과 같은 반응 방식의 사람 말투 카드 사이에서 vector 유사도를 계산하는 실제 Speech-only RAG.
 *
 * 현재 private corpus 규모에서는 DB vector extension 없이 암호화된 벡터를 메모리에서 cosine으로 비교하는 편이 단순하고
 * 운영상 충분하다. Judge의 7개 말투 enum만 공개 후보 축으로 쓴다. 그 안에서는 OpenAI embedding 유사도를 기본으로 하되,
 * 실제 답변에서 관찰된 닫힌 form/rhythm metadata만 근접 동점의 보조값으로 쓴다. 원문 앞 대화와 실제 답변은 선택·생성
 * provider 어느 경로에도 사용하지 않는다.
 */
@Service
class HumanSpeechStyleRagService(
    private val store: HumanSpeechStyleExampleStorePort,
    private val embeddingPort: SpeechStyleEmbeddingPort,
    @param:Value("\${central.nexa.speech-style-rag.enabled:true}") private val enabled: Boolean = true,
    @param:Value("\${central.nexa.speech-style-rag.embedding-model:text-embedding-3-small}")
    private val embeddingModel: String = DEFAULT_EMBEDDING_MODEL,
    private val metrics: HumanSpeechStyleRagMetrics = HumanSpeechStyleRagMetrics.Noop,
) : HumanSpeechStyleRagPort {
    override fun retrieve(packet: SpeechScenePacket): HumanSpeechStyleSelection {
        if (!enabled) return empty(HumanSpeechStyleRagOutcome.DISABLED)
        val requestedMode = packet.styleResponseMode ?: return empty(HumanSpeechStyleRagOutcome.MISSING_RESPONSE_MODE)
        val examples =
            store
                .listEnabled(requestedMode)
                .filter { it.embeddingModel == embeddingModel && it.promptSurface.isProviderSafe() }
        if (examples.isEmpty()) return empty(HumanSpeechStyleRagOutcome.NO_ENABLED_EXAMPLES)
        val requestedMove = requestedResponseMove(packet)
        val requestedSceneTrait = requestedSceneTrait(packet)
        val requestedDeliveryRhythm = requestedDeliveryRhythm(packet)
        val desiredPrimaryStyleCue =
            desiredPrimaryStyleCue(
                responseMode = requestedMode,
                requestedMove = requestedMove,
                requestedSceneTrait = requestedSceneTrait,
                requestedDeliveryRhythm = requestedDeliveryRhythm,
            )

        val queryEmbeddings =
            embeddingPort.embedAll(
                listOf(
                    queryText(
                        responseMode = requestedMode,
                        requestedSceneTrait = requestedSceneTrait,
                        requestedBubbleCount = packet.burstShape.fragmentCount,
                    ),
                    rhythmQueryText(
                        responseMode = requestedMode,
                        requestedDeliveryRhythm = requestedDeliveryRhythm,
                    ),
                ),
            )
        if (queryEmbeddings == null || queryEmbeddings.size != QUERY_EMBEDDING_COUNT || queryEmbeddings.any(FloatArray::isEmpty)) {
            return empty(HumanSpeechStyleRagOutcome.EMBEDDING_UNAVAILABLE)
        }

        val rankedMatches =
            examples
                .asSequence()
                .mapNotNull { example -> score(example, queryEmbeddings.first(), queryEmbeddings.last(), packet) }
                .filter { it.score >= MIN_SCORE }
                .sortedWith(compareByDescending<HumanSpeechStyleMatch> { it.score }.thenBy { it.example.exampleId })
                .toList()

        val selection =
            HumanSpeechStyleSelection(
                selectReferenceMatches(
                    rankedMatches = rankedMatches,
                    requestedMove = requestedMove,
                    requestedSceneTrait = requestedSceneTrait,
                    requestedDeliveryRhythm = requestedDeliveryRhythm,
                    desiredPrimaryStyleCue = desiredPrimaryStyleCue,
                ).map { it.withSceneSupportedMetadata(requestedMove, requestedSceneTrait) },
            )
        metrics.record(if (selection.isEmpty) HumanSpeechStyleRagOutcome.NO_MATCH else HumanSpeechStyleRagOutcome.SELECTED)
        return selection
    }

    private fun empty(outcome: HumanSpeechStyleRagOutcome): HumanSpeechStyleSelection {
        metrics.record(outcome)
        return HumanSpeechStyleSelection.EMPTY
    }

    private fun requestedResponseMove(packet: SpeechScenePacket): HumanSpeechStyleResponseMove? {
        val responseMode = packet.styleResponseMode ?: return null
        val text =
            packet.recentTurns
                .lastOrNull()
                ?.text
                ?.take(MAX_TURN_CHARS)
                ?.trim()
                .orEmpty()
        if (text.isEmpty()) return null
        val patterns = RESPONSE_MOVE_PATTERNS[responseMode].orEmpty()
        // 세부 move는 현재 마지막 발화에서 하나로 명시된 경우만 provider 지시로 승격한다. 과거 turn이나
        // Judge의 요약은 retrieval 의미에는 쓰되, 이유·시간·몸 상태 같은 더 좁은 사실을 지시할 근거는 아니다.
        return patterns.filter { (_, pattern) -> pattern.containsMatchIn(text) }.map { it.first }.singleOrNull()
    }

    /**
     * 카드 앞 장면과 비교할 단서는 현재 마지막 발화에서 하나로 확인될 때만 쓴다.
     *
     * response move와 달리 이 값은 카드의 응답 행동이 아니라 앞 장면의 일반적인 결이다. 여러 결이 한 번에 보이면
     * 임의 우선순위를 두지 않고 null로 돌아가 provider에는 enum 기본 안내만 남긴다.
     */
    private fun requestedSceneTrait(packet: SpeechScenePacket): HumanSpeechSceneTrait? {
        val responseMode = packet.styleResponseMode ?: return null
        val text =
            packet.recentTurns
                .lastOrNull()
                ?.text
                ?.take(MAX_TURN_CHARS)
                ?.trim()
                .orEmpty()
        if (text.isEmpty()) return null
        return SCENE_TRAIT_PATTERNS[responseMode]
            .orEmpty()
            .filter { (_, pattern) -> pattern.containsMatchIn(text) }
            .map { it.first }
            .singleOrNull()
    }

    /**
     * 마지막 발화의 전달 리듬만 닫힌 cue로 읽는다. 원문 축약어·문장 자체는 embedding 입력에 복사하지 않고,
     * 동점인 안전 카드의 말풍선 길이와 끝맺음을 고르는 보조값으로만 쓴다.
     */
    private fun requestedDeliveryRhythm(packet: SpeechScenePacket): Set<HumanSpeechStyleRhythmCue> {
        val latestText =
            packet.recentTurns
                .lastOrNull()
                ?.text
                ?.take(MAX_TURN_CHARS)
                ?.trim()
                .orEmpty()
        if (latestText.isEmpty()) return emptySet()

        return buildSet {
            if (TRAILING_PAUSE_PATTERN.containsMatchIn(latestText)) add(HumanSpeechStyleRhythmCue.TRAILING_PAUSE)
            if (CASUAL_SHORT_FORM_PATTERN.containsMatchIn(latestText)) add(HumanSpeechStyleRhythmCue.CASUAL_SHORT_FORM)
            if (SOFT_EMOTION_MARKER_PATTERN.containsMatchIn(latestText)) add(HumanSpeechStyleRhythmCue.SOFT_EMOTION_MARKER)
        }
    }

    private fun selectReferenceMatches(
        rankedMatches: List<HumanSpeechStyleMatch>,
        requestedMove: HumanSpeechStyleResponseMove?,
        requestedSceneTrait: HumanSpeechSceneTrait?,
        requestedDeliveryRhythm: Set<HumanSpeechStyleRhythmCue>,
        desiredPrimaryStyleCue: HumanSpeechStyleProviderStyleCue?,
    ): List<HumanSpeechStyleMatch> {
        val remaining = rankedMatches.toMutableList()
        val selected = mutableListOf<HumanSpeechStyleMatch>()
        while (selected.size < HumanSpeechStyleSelection.MAX_MATCHES && remaining.isNotEmpty()) {
            val semanticLeader = remaining.first()
            val nearTies = remaining.takeWhile { semanticLeader.score - it.score <= METADATA_TIE_WINDOW }
            val firstSelectedPrimaryStyleCue = selected.firstOrNull()?.primaryStyleCue()
            val selectedMatch =
                nearTies.minWith(
                    compareByDescending<HumanSpeechStyleMatch> {
                        primaryStyleCueRerankPriority(
                            candidate = it,
                            desiredPrimaryStyleCue = desiredPrimaryStyleCue,
                            firstSelectedPrimaryStyleCue = firstSelectedPrimaryStyleCue,
                        )
                    }.thenByDescending<HumanSpeechStyleMatch> {
                        responseMetadataPriority(it, requestedMove, requestedSceneTrait, requestedDeliveryRhythm)
                    }.thenByDescending(HumanSpeechStyleMatch::score)
                        .thenBy { it.example.exampleId },
                )
            selected += selectedMatch
            remaining.removeAll { it.example.sourceFingerprint == selectedMatch.example.sourceFingerprint }
        }
        return selected
    }

    /**
     * v11은 mode와 semantic score를 먼저 고정하고, 같은 semantic window 안에서만 primary cue를 쓴다.
     *
     * 첫 참고는 최신 turn의 닫힌 metadata가 가리키는 cue를 선호한다. 두 번째 참고는 첫 cue와 다른 카드가
     * 같은 window 안에 있을 때만 그 카드를 선호한다. 카드의 cue 목록은 import 검증상 하나지만, 오래된
     * 데이터와의 경계에서도 첫 값만 읽어 provider 표면을 넓히지 않는다.
     */
    private fun primaryStyleCueRerankPriority(
        candidate: HumanSpeechStyleMatch,
        desiredPrimaryStyleCue: HumanSpeechStyleProviderStyleCue?,
        firstSelectedPrimaryStyleCue: HumanSpeechStyleProviderStyleCue?,
    ): Int {
        val candidatePrimaryStyleCue = candidate.primaryStyleCue()
        return when {
            firstSelectedPrimaryStyleCue != null ->
                if (candidatePrimaryStyleCue != null && candidatePrimaryStyleCue != firstSelectedPrimaryStyleCue) 1 else 0

            desiredPrimaryStyleCue != null && candidatePrimaryStyleCue == desiredPrimaryStyleCue -> 1
            else -> 0
        }
    }

    private fun HumanSpeechStyleMatch.primaryStyleCue(): HumanSpeechStyleProviderStyleCue? = example.providerStyleCues.firstOrNull()

    /**
     * 현재 마지막 turn에서 이미 읽은 닫힌 metadata만 cue 선택에 사용한다.
     *
     * response move가 가장 좁은 근거이고, scene trait와 delivery 표지는 그보다 약한 fallback이다. raw turn,
     * speech intent, 카드 원문은 이 결정에 다시 읽지 않는다.
     */
    private fun desiredPrimaryStyleCue(
        responseMode: HumanSpeechResponseMode,
        requestedMove: HumanSpeechStyleResponseMove?,
        requestedSceneTrait: HumanSpeechSceneTrait?,
        requestedDeliveryRhythm: Set<HumanSpeechStyleRhythmCue>,
    ): HumanSpeechStyleProviderStyleCue? =
        requestedMove
            ?.takeIf { it.responseMode == responseMode }
            ?.desiredPrimaryStyleCue()
            ?: requestedSceneTrait
                ?.takeIf { it.responseMode == responseMode }
                ?.desiredPrimaryStyleCue()
            ?: desiredPrimaryStyleCueFromDelivery(responseMode, requestedDeliveryRhythm)

    private fun HumanSpeechStyleResponseMove.desiredPrimaryStyleCue(): HumanSpeechStyleProviderStyleCue =
        when (this) {
            HumanSpeechStyleResponseMove.REACTION_GOOD_NEWS -> HumanSpeechStyleProviderStyleCue.REACTION_WARM_ACK
            HumanSpeechStyleResponseMove.REACTION_SURPRISE -> HumanSpeechStyleProviderStyleCue.REACTION_IMMEDIATE
            HumanSpeechStyleResponseMove.REACTION_FUNNY -> HumanSpeechStyleProviderStyleCue.REACTION_LAUGH_ALONG
            HumanSpeechStyleResponseMove.ALIGNMENT_COMPLAINT -> HumanSpeechStyleProviderStyleCue.ALIGNMENT_SHARED_FEELING
            HumanSpeechStyleResponseMove.ALIGNMENT_LOW_ENERGY -> HumanSpeechStyleProviderStyleCue.ALIGNMENT_LOW_KEY_ACK
            HumanSpeechStyleResponseMove.PLAY_COMPETITIVE_TEASE,
            HumanSpeechStyleResponseMove.PLAY_FRIENDLY_TEASE,
            -> HumanSpeechStyleProviderStyleCue.PLAY_COUNTERTEASE
            HumanSpeechStyleResponseMove.PLAY_LIGHT_EXAGGERATION -> HumanSpeechStyleProviderStyleCue.PLAY_LIGHT_EXAGGERATION
            HumanSpeechStyleResponseMove.FOLLOW_UP_STATUS,
            HumanSpeechStyleResponseMove.FOLLOW_UP_PROGRESS,
            -> HumanSpeechStyleProviderStyleCue.FOLLOW_UP_SOFT_CHECK
            HumanSpeechStyleResponseMove.FOLLOW_UP_CHANGE,
            HumanSpeechStyleResponseMove.FOLLOW_UP_CAUSE,
            -> HumanSpeechStyleProviderStyleCue.FOLLOW_UP_DIRECT_CHECK
            HumanSpeechStyleResponseMove.SPECULATION_CAUSE,
            HumanSpeechStyleResponseMove.SPECULATION_FUTURE,
            HumanSpeechStyleResponseMove.SPECULATION_PRESENT,
            -> HumanSpeechStyleProviderStyleCue.SPECULATION_LIGHT_HEDGE
            HumanSpeechStyleResponseMove.CARE_PHYSICAL,
            HumanSpeechStyleResponseMove.CARE_EMOTIONAL,
            -> HumanSpeechStyleProviderStyleCue.CARE_GENTLE_VALIDATE
            HumanSpeechStyleResponseMove.CARE_FATIGUE -> HumanSpeechStyleProviderStyleCue.CARE_SOFT_NUDGE
            HumanSpeechStyleResponseMove.COORDINATION_CHOICE,
            HumanSpeechStyleResponseMove.COORDINATION_TIME,
            -> HumanSpeechStyleProviderStyleCue.COORDINATION_ASK_ONE
            HumanSpeechStyleResponseMove.COORDINATION_ACTION -> HumanSpeechStyleProviderStyleCue.COORDINATION_PROPOSE
            HumanSpeechStyleResponseMove.COORDINATION_ROLE -> HumanSpeechStyleProviderStyleCue.COORDINATION_CONFIRM
        }

    private fun HumanSpeechSceneTrait.desiredPrimaryStyleCue(): HumanSpeechStyleProviderStyleCue =
        when (this) {
            HumanSpeechSceneTrait.REACTION_GOOD_NEWS -> HumanSpeechStyleProviderStyleCue.REACTION_WARM_ACK
            HumanSpeechSceneTrait.REACTION_SURPRISE_OR_FUNNY -> HumanSpeechStyleProviderStyleCue.REACTION_IMMEDIATE
            HumanSpeechSceneTrait.ALIGNMENT_COMPLAINT_OR_LOW_ENERGY -> HumanSpeechStyleProviderStyleCue.ALIGNMENT_LOW_KEY_ACK
            HumanSpeechSceneTrait.PLAY_BANTER -> HumanSpeechStyleProviderStyleCue.PLAY_COUNTERTEASE
            HumanSpeechSceneTrait.FOLLOW_UP_STATUS_OR_PROGRESS -> HumanSpeechStyleProviderStyleCue.FOLLOW_UP_SOFT_CHECK
            HumanSpeechSceneTrait.FOLLOW_UP_CHANGE,
            HumanSpeechSceneTrait.FOLLOW_UP_CAUSE,
            -> HumanSpeechStyleProviderStyleCue.FOLLOW_UP_DIRECT_CHECK
            HumanSpeechSceneTrait.SPECULATION_CAUSE,
            HumanSpeechSceneTrait.SPECULATION_FUTURE,
            HumanSpeechSceneTrait.SPECULATION_PRESENT,
            -> HumanSpeechStyleProviderStyleCue.SPECULATION_LIGHT_HEDGE
            HumanSpeechSceneTrait.CARE_PHYSICAL_CONDITION,
            HumanSpeechSceneTrait.CARE_EMOTIONAL_DISTRESS,
            -> HumanSpeechStyleProviderStyleCue.CARE_GENTLE_VALIDATE
            HumanSpeechSceneTrait.CARE_FATIGUE_OVERLOAD -> HumanSpeechStyleProviderStyleCue.CARE_SOFT_NUDGE
            HumanSpeechSceneTrait.COORDINATION_CHOICE,
            HumanSpeechSceneTrait.COORDINATION_TIME,
            -> HumanSpeechStyleProviderStyleCue.COORDINATION_ASK_ONE
            HumanSpeechSceneTrait.COORDINATION_ACTION_PROPOSAL -> HumanSpeechStyleProviderStyleCue.COORDINATION_PROPOSE
            HumanSpeechSceneTrait.COORDINATION_ROLE_OR_ORDER -> HumanSpeechStyleProviderStyleCue.COORDINATION_CONFIRM
        }

    private fun desiredPrimaryStyleCueFromDelivery(
        responseMode: HumanSpeechResponseMode,
        requestedDeliveryRhythm: Set<HumanSpeechStyleRhythmCue>,
    ): HumanSpeechStyleProviderStyleCue? =
        when {
            responseMode == HumanSpeechResponseMode.CARE &&
                HumanSpeechStyleRhythmCue.SOFT_EMOTION_MARKER in requestedDeliveryRhythm ->
                HumanSpeechStyleProviderStyleCue.CARE_GENTLE_VALIDATE

            responseMode == HumanSpeechResponseMode.PLAY &&
                HumanSpeechStyleRhythmCue.CASUAL_SHORT_FORM in requestedDeliveryRhythm ->
                HumanSpeechStyleProviderStyleCue.PLAY_COUNTERTEASE

            responseMode == HumanSpeechResponseMode.SPECULATION &&
                HumanSpeechStyleRhythmCue.TRAILING_PAUSE in requestedDeliveryRhythm ->
                HumanSpeechStyleProviderStyleCue.SPECULATION_LIGHT_HEDGE

            else -> null
        }

    private fun HumanSpeechStyleMatch.withSceneSupportedMetadata(
        requestedMove: HumanSpeechStyleResponseMove?,
        requestedSceneTrait: HumanSpeechSceneTrait?,
    ): HumanSpeechStyleMatch =
        copy(
            sceneSupportedResponseMove = requestedMove?.takeIf { example.responseMove == it },
            sceneSupportedSceneTrait = requestedSceneTrait?.takeIf { it in example.sceneTraits },
        )

    private fun responseMetadataPriority(
        match: HumanSpeechStyleMatch,
        requestedMove: HumanSpeechStyleResponseMove?,
        requestedSceneTrait: HumanSpeechSceneTrait?,
        requestedDeliveryRhythm: Set<HumanSpeechStyleRhythmCue>,
    ): Int =
        when {
            // trait는 현재 마지막 발화와 카드 앞 장면 양쪽에서 닫힌 값으로 확인됐다. 같은 가까운 semantic 후보 중에서만
            // 이 카드를 앞세워, enum 기본 지시문보다 카드별 말풍선 형식이 실제로 선택될 수 있게 한다.
            requestedSceneTrait != null && requestedSceneTrait in match.example.sceneTraits -> SCENE_TRAIT_PRIORITY
            requestedMove != null && match.example.responseMove == requestedMove -> OBSERVED_MOVE_PRIORITY
            requestedDeliveryRhythm.any { it in match.example.responseRhythm } -> MATCHING_DELIVERY_RHYTHM_PRIORITY
            match.example.responseForm != null -> OBSERVED_FORM_PRIORITY
            match.example.hasObservedResponseRhythm() -> OBSERVED_RHYTHM_PRIORITY
            else -> UNKNOWN_RESPONSE_METADATA_PRIORITY
        }

    private fun score(
        example: HumanSpeechStyleExample,
        sceneQueryEmbedding: FloatArray,
        rhythmQueryEmbedding: FloatArray,
        packet: SpeechScenePacket,
    ): HumanSpeechStyleMatch? {
        val sceneSemantic = cosineSimilarity(sceneQueryEmbedding, example.embedding) ?: return null
        val rhythmSemantic =
            cosineSimilarity(rhythmQueryEmbedding, example.rhythmEmbedding)
                ?: sceneSemantic
        val semantic = sceneSemantic * SCENE_SEMANTIC_WEIGHT + rhythmSemantic * RHYTHM_SEMANTIC_WEIGHT
        val bubbleDistance = abs(example.responseBubbles.size - packet.burstShape.fragmentCount)
        val deliveryFit = 1.0 / (1.0 + bubbleDistance)
        val score = semantic * SEMANTIC_WEIGHT + deliveryFit * DELIVERY_WEIGHT
        return HumanSpeechStyleMatch(
            example = example,
            score = score,
        )
    }

    private fun queryText(
        responseMode: HumanSpeechResponseMode,
        requestedSceneTrait: HumanSpeechSceneTrait?,
        requestedBubbleCount: Int,
    ): String =
        buildString {
            // 이 함수의 매개변수는 닫힌 metadata뿐이다. live speech intent·최근 turn 원문은
            // embedding provider에 보낼 수 없다.
            appendLine("반응 방식: ${responseMode.name}")
            requestedSceneTrait?.let { appendLine("현재 장면 단서: ${it.retrievalDescription}") }
            appendLine("원하는 말풍선 수: $requestedBubbleCount")
        }.trim()

    private fun rhythmQueryText(
        responseMode: HumanSpeechResponseMode,
        requestedDeliveryRhythm: Set<HumanSpeechStyleRhythmCue>,
    ): String =
        buildString {
            appendLine("반응 방식: ${responseMode.name}")
            appendLine("반응 목표: ${responseMode.retrievalDescription}")
            if (requestedDeliveryRhythm.isNotEmpty()) {
                appendLine(
                    "현재 대화 전달 표지: " +
                        requestedDeliveryRhythm
                            .sortedBy(HumanSpeechStyleRhythmCue::name)
                            .joinToString(" · ") { it.retrievalDescription },
                )
            }
        }.trim()

    private fun cosineSimilarity(
        left: FloatArray,
        right: FloatArray,
    ): Double? {
        if (left.size != right.size || left.isEmpty()) return null
        var dot = 0.0
        var leftMagnitude = 0.0
        var rightMagnitude = 0.0
        left.indices.forEach { index ->
            val leftValue = left[index].toDouble()
            val rightValue = right[index].toDouble()
            dot += leftValue * rightValue
            leftMagnitude += leftValue * leftValue
            rightMagnitude += rightValue * rightValue
        }
        val denominator = sqrt(leftMagnitude) * sqrt(rightMagnitude)
        return if (denominator > 0.0) (dot / denominator).coerceIn(-1.0, 1.0).coerceAtLeast(0.0) else null
    }

    private fun HumanSpeechStyleExample.hasObservedResponseRhythm(): Boolean =
        responseRhythm.any(HumanSpeechStyleRhythmCue::isObservedResponseBehavior)

    private companion object {
        const val MAX_TURN_CHARS: Int = 420
        const val MIN_SCORE: Double = 0.28
        const val SEMANTIC_WEIGHT: Double = 0.90
        const val DELIVERY_WEIGHT: Double = 0.10
        const val SCENE_SEMANTIC_WEIGHT: Double = 0.85
        const val RHYTHM_SEMANTIC_WEIGHT: Double = 0.15
        const val QUERY_EMBEDDING_COUNT: Int = 2

        // 닫힌 response move는 이미 같은 enum 안에서 같은 반응 행동을 뜻한다. 점수가 사실상 비슷한 카드만
        // 이 작은 범위에서 rerank해, generic enum 안내문보다 구체적인 말투 선택을 하되 더 관련도 높은 카드가
        // 뒤집히지 않게 한다.
        const val METADATA_TIE_WINDOW: Double = 0.035
        const val SCENE_TRAIT_PRIORITY: Int = 5
        const val OBSERVED_MOVE_PRIORITY: Int = 4
        const val MATCHING_DELIVERY_RHYTHM_PRIORITY: Int = 3
        const val OBSERVED_FORM_PRIORITY: Int = 2
        const val OBSERVED_RHYTHM_PRIORITY: Int = 1
        const val UNKNOWN_RESPONSE_METADATA_PRIORITY: Int = 0
        const val DEFAULT_EMBEDDING_MODEL: String = "text-embedding-3-small"
        val TRAILING_PAUSE_PATTERN: Regex = Regex("(?:\\.{2,}|…|~+)\\s*$")
        val CASUAL_SHORT_FORM_PATTERN: Regex =
            Regex(
                "(?:^|\\s)(?:ㅇㅇ|응|어|웅|ㄴㄷ|ㅇㅈ|ㄹㅇ|ㅁㅊ|ㅇㅋ|ㄱㄱ|ㄱㄴㄲ|ㅇㄴ|ㅈㄴ|ㅅㅂ|ㅂㅅ)(?=$|\\s|[.!?~…])",
                RegexOption.IGNORE_CASE,
            )
        val SOFT_EMOTION_MARKER_PATTERN: Regex = Regex("(?:ㅠ|ㅜ)+")
        val SCENE_TRAIT_PATTERNS: Map<HumanSpeechResponseMode, List<Pair<HumanSpeechSceneTrait, Regex>>> =
            mapOf(
                HumanSpeechResponseMode.REACTION to
                    listOf(
                        HumanSpeechSceneTrait.REACTION_GOOD_NEWS to Regex("드디어|해냈|성공|합격|축하|좋은\\s*소식|반가운|잘됐"),
                        HumanSpeechSceneTrait.REACTION_SURPRISE_OR_FUNNY to Regex("헐|헉|와|뭐임|대박|ㅁㅊ|미친|뜻밖|예상\\s*못|웃기|웃겨|ㅋㅋ|ㅋ{2,}|재밌|개웃|웃음"),
                    ),
                HumanSpeechResponseMode.ALIGNMENT to
                    listOf(
                        HumanSpeechSceneTrait.ALIGNMENT_COMPLAINT_OR_LOW_ENERGY to
                            Regex("기빨|피곤|졸리|졸령|집중\\s*안|처진|기운\\s*없|지치|답답|싫|불편|불평|불만|너무\\s*많|안\\s*와|길게\\s*느껴|별로|짜증|눅눅"),
                    ),
                HumanSpeechResponseMode.PLAY to
                    listOf(
                        HumanSpeechSceneTrait.PLAY_BANTER to
                            Regex("이기|더\\s*빠|대결|경쟁|못\\s*이겨|졌다|승부|너답|왜\\s*이렇게|착함|착하|또\\s*너|니가|쟤\\s*왜|너무\\s*일찍|과장|큰일|죽겠|레전드|미쳤"),
                    ),
                HumanSpeechResponseMode.FOLLOW_UP to
                    listOf(
                        HumanSpeechSceneTrait.FOLLOW_UP_STATUS_OR_PROGRESS to
                            Regex("병원|감기|치료|약|다쳤|아프|몸살|컨디션|상태|결국|어떻게\\s*됐|진행|결과|마무리|그\\s*뒤|후에"),
                        HumanSpeechSceneTrait.FOLLOW_UP_CHANGE to Regex("바뀌|변경|일정|달라졌|취소|미뤄"),
                        HumanSpeechSceneTrait.FOLLOW_UP_CAUSE to Regex("왜|이유|어쩌다|그렇게\\s*된"),
                    ),
                HumanSpeechResponseMode.SPECULATION to
                    listOf(
                        HumanSpeechSceneTrait.SPECULATION_CAUSE to Regex("왜|이유|어쩌다|때문|그럴까"),
                        HumanSpeechSceneTrait.SPECULATION_FUTURE to Regex("내일|나중|앞으로|오늘.{0,12}(?:올|갈|될)|올까|될까|려나"),
                        HumanSpeechSceneTrait.SPECULATION_PRESENT to Regex("지금|현재|자고\\s*있|하고\\s*있|있나|왔나"),
                    ),
                HumanSpeechResponseMode.CARE to
                    listOf(
                        HumanSpeechSceneTrait.CARE_PHYSICAL_CONDITION to Regex("병원|감기|머리\\s*아프|몸살|약|치료|다쳤|아프(?:다|네|냐|겠|구나)"),
                        HumanSpeechSceneTrait.CARE_FATIGUE_OVERLOAD to Regex("피곤|지치|지쳐|잠|기운\\s*없|졸리|쉬어"),
                        HumanSpeechSceneTrait.CARE_EMOTIONAL_DISTRESS to Regex("예민|속상|마음|기분|힘들|울|스트레스"),
                    ),
                HumanSpeechResponseMode.COORDINATION to
                    listOf(
                        HumanSpeechSceneTrait.COORDINATION_ROLE_OR_ORDER to Regex("누가|누굴|누구|먼저|역할|순서|담당|맡"),
                        HumanSpeechSceneTrait.COORDINATION_CHOICE to
                            Regex("(?:(?:뭐|어디|어느|뭐로|골라|선택).{0,12}(?:갈|하|보|먹|만나)|(?:갈|하|보|먹|만나).{0,12}(?:뭐|어디|어느|뭐로|골라|선택))"),
                        HumanSpeechSceneTrait.COORDINATION_TIME to Regex("몇\\s*시|언제|주말|이따|끝나고|오전|오후|저녁"),
                        HumanSpeechSceneTrait.COORDINATION_ACTION_PROPOSAL to Regex("갈까|가자|하자|할까|ㄱㄱ|보자|볼까|만나자|만날까|먹자|먹을까|제안"),
                    ),
            )
        val RESPONSE_MOVE_PATTERNS: Map<HumanSpeechResponseMode, List<Pair<HumanSpeechStyleResponseMove, Regex>>> =
            mapOf(
                HumanSpeechResponseMode.REACTION to
                    listOf(
                        HumanSpeechStyleResponseMove.REACTION_GOOD_NEWS to Regex("드디어|해냈|성공|합격|샀어|샀다|축하|좋은\\s*소식|반가운|잘됐"),
                        HumanSpeechStyleResponseMove.REACTION_SURPRISE to Regex("헐|헉|와|뭐임|대박|ㅁㅊ|미친|뜻밖|예상\\s*못"),
                        HumanSpeechStyleResponseMove.REACTION_FUNNY to Regex("웃기|웃겨|ㅋㅋ|ㅋ{2,}|재밌|개웃|웃음"),
                    ),
                HumanSpeechResponseMode.ALIGNMENT to
                    listOf(
                        HumanSpeechStyleResponseMove.ALIGNMENT_LOW_ENERGY to Regex("기빨|피곤|졸리|졸령|집중\\s*안|처진|기운\\s*없|지치"),
                        HumanSpeechStyleResponseMove.ALIGNMENT_COMPLAINT to Regex("답답|싫|불편|불평|불만|너무\\s*많|안\\s*와|길게\\s*느껴|별로|짜증|눅눅"),
                    ),
                HumanSpeechResponseMode.PLAY to
                    listOf(
                        HumanSpeechStyleResponseMove.PLAY_COMPETITIVE_TEASE to Regex("이기|더\\s*빠|대결|경쟁|못\\s*이겨|졌다|승부"),
                        HumanSpeechStyleResponseMove.PLAY_FRIENDLY_TEASE to Regex("너답|왜\\s*이렇게|착함|착하|또\\s*너|니가|쟤\\s*왜"),
                        HumanSpeechStyleResponseMove.PLAY_LIGHT_EXAGGERATION to Regex("너무\\s*일찍|과장|큰일|죽겠|레전드|미쳤"),
                    ),
                HumanSpeechResponseMode.FOLLOW_UP to
                    listOf(
                        HumanSpeechStyleResponseMove.FOLLOW_UP_STATUS to Regex("병원|감기|치료|약|다쳤|아프|몸살|컨디션|상태"),
                        HumanSpeechStyleResponseMove.FOLLOW_UP_CHANGE to Regex("바뀌|변경|일정|달라졌|취소|미뤄"),
                        HumanSpeechStyleResponseMove.FOLLOW_UP_CAUSE to Regex("왜|이유|어쩌다|그렇게\\s*된"),
                        HumanSpeechStyleResponseMove.FOLLOW_UP_PROGRESS to Regex("결국|어떻게\\s*됐|진행|결과|마무리|그\\s*뒤|후에"),
                    ),
                HumanSpeechResponseMode.SPECULATION to
                    listOf(
                        HumanSpeechStyleResponseMove.SPECULATION_CAUSE to Regex("왜|이유|어쩌다|때문|그럴까"),
                        HumanSpeechStyleResponseMove.SPECULATION_FUTURE to Regex("내일|나중|앞으로|오늘.{0,12}(?:올|갈|될)|올까|될까|려나"),
                        HumanSpeechStyleResponseMove.SPECULATION_PRESENT to Regex("지금|현재|자고\\s*있|하고\\s*있|있나|왔나"),
                    ),
                HumanSpeechResponseMode.CARE to
                    listOf(
                        HumanSpeechStyleResponseMove.CARE_PHYSICAL to Regex("병원|감기|머리\\s*아프|몸살|약|치료|다쳤|아프(?:다|네|냐|겠|구나)"),
                        HumanSpeechStyleResponseMove.CARE_FATIGUE to Regex("피곤|지치|지쳐|잠|기운\\s*없|졸리|쉬어"),
                        HumanSpeechStyleResponseMove.CARE_EMOTIONAL to Regex("예민|속상|마음|기분|힘들|울|스트레스"),
                    ),
                HumanSpeechResponseMode.COORDINATION to
                    listOf(
                        HumanSpeechStyleResponseMove.COORDINATION_ROLE to Regex("누가|누굴|누구|먼저|역할|순서|담당|맡"),
                        HumanSpeechStyleResponseMove.COORDINATION_CHOICE to
                            Regex("(?:(?:뭐|어디|어느|뭐로|골라|선택).{0,12}(?:갈|하|보|먹|만나)|(?:갈|하|보|먹|만나).{0,12}(?:뭐|어디|어느|뭐로|골라|선택))"),
                        HumanSpeechStyleResponseMove.COORDINATION_TIME to Regex("몇\\s*시|언제|주말|이따|끝나고|오전|오후|저녁"),
                        HumanSpeechStyleResponseMove.COORDINATION_ACTION to Regex("갈까|가자|하자|할까|ㄱㄱ|보자|볼까|만나자|만날까|먹자|먹을까|제안"),
                    ),
            )
    }
}
