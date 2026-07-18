package com.discordassistant.central.speech.support

import com.discordassistant.central.speech.application.generation.CompleteActionSelector
import com.discordassistant.central.speech.application.port.out.CompleteActionEvaluation
import com.discordassistant.central.speech.application.port.out.CompleteActionEvaluationPort
import com.discordassistant.central.speech.application.port.out.CompleteActionKind

/** 외부 가치 모델을 쓰지 않는 테스트가 실제 후보 평가 경계를 명시적으로 통과하게 하는 결정론 fixture다. */
fun deterministicCompleteActionSelector(): CompleteActionSelector =
    CompleteActionSelector(
        CompleteActionEvaluationPort { request ->
            val selected =
                request.candidates.firstOrNull { it.kind == CompleteActionKind.SEND }
                    ?: request.candidates.firstOrNull { it.kind == CompleteActionKind.REACT }
                    ?: request.candidates.single { it.kind == CompleteActionKind.IGNORE }
            CompleteActionEvaluation(
                selectedCandidateId = selected.candidateId,
                predictedOutcome = "test fixture selects a safe complete action",
                reasonCode = "TEST_FIXTURE",
                confidence = 1.0,
            )
        },
    )
