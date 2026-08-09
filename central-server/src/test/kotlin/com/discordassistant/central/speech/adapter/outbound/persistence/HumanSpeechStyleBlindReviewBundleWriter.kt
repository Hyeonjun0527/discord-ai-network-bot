package com.discordassistant.central.speech.adapter.outbound.persistence

import com.discordassistant.central.speech.application.humanstyle.HumanSpeechStyleProviderPatternFactory
import com.discordassistant.central.speech.domain.model.ConversationTurn
import com.discordassistant.central.speech.domain.model.HumanSpeechResponseMode
import com.discordassistant.central.speech.domain.model.HumanSpeechStyleMatch
import com.discordassistant.central.speech.domain.model.SpeechSocialAct
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/**
 * 실제 OpenAI/H2 retrieval 결과를 사람이 blind quality review할 수 있는 private-only bundle로 직렬화한다.
 *
 * source fingerprint, card/example ID, raw source path, 원문 말풍선, 검색용 situation/style signal, embedding, score를
 * 의도적으로 뺀다. 검수자는 실제 provider에 보이는 비식별 pattern만 보고 판단해야 한다. 호출자는 owner-only 디렉터리와
 * 새 출력 경로를 제공해야 한다.
 */
internal object HumanSpeechStyleBlindReviewBundleWriter {
    const val SCHEMA: String = "nia-human-speech-style-blind-review-bundle.v6"
    const val PROVIDER_PATTERN_POLICY: String = "primary_style_cue_plus_scene_trait_or_submove_v4"
    private val OWNER_DIRECTORY_PERMISSIONS =
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
    private val OWNER_FILE_PERMISSIONS =
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )

    fun write(
        output: Path,
        candidateDigest: String,
        retrievalAuditDigest: String,
        cases: List<HumanSpeechStyleBlindReviewCase>,
    ) {
        require(candidateDigest.matches(SHA256_HEX)) { "blind review candidate digest is invalid" }
        require(retrievalAuditDigest.matches(SHA256_HEX)) { "blind review audit digest is invalid" }
        require(cases.isNotEmpty()) { "blind review needs at least one retrieval case" }

        val normalizedOutput = output.toAbsolutePath().normalize()
        val parent = requireNotNull(normalizedOutput.parent) { "blind review output requires a parent directory" }
        require(Files.isDirectory(parent) && !Files.isSymbolicLink(parent)) {
            "blind review output parent must be a real directory"
        }
        require(Files.getPosixFilePermissions(parent) == OWNER_DIRECTORY_PERMISSIONS) {
            "blind review output parent must be owner-only"
        }
        require(!Files.exists(normalizedOutput)) { "blind review output must not overwrite an existing file" }

        val document =
            linkedMapOf(
                "schema" to SCHEMA,
                "candidate_jsonl_sha256" to candidateDigest,
                "retrieval_audit_sha256" to retrievalAuditDigest,
                "provider_pattern_policy" to PROVIDER_PATTERN_POLICY,
                "case_count" to cases.size,
                "cases" to
                    cases
                        .sortedBy { case -> caseSortKey(candidateDigest, case) }
                        .mapIndexed { index, case -> casePayload(index + 1, case) },
            )
        val serialized = jacksonObjectMapper().writeValueAsString(document)
        val temporary =
            Files.createTempFile(
                parent,
                ".human-speech-style-blind-review-",
                ".tmp",
                PosixFilePermissions.asFileAttribute(OWNER_FILE_PERMISSIONS),
            )
        try {
            Files.writeString(temporary, serialized)
            Files.move(temporary, normalizedOutput, ATOMIC_MOVE)
            Files.setPosixFilePermissions(normalizedOutput, OWNER_FILE_PERMISSIONS)
        } catch (error: AtomicMoveNotSupportedException) {
            throw IllegalStateException("blind review output requires an atomic private-file move", error)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun casePayload(
        ordinal: Int,
        case: HumanSpeechStyleBlindReviewCase,
    ): Map<String, Any> {
        val providerPatterns = HumanSpeechStyleProviderPatternFactory.fromReferences(case.matches)
        return linkedMapOf(
            "case_id" to "blind-v1-%04d".format(ordinal),
            "response_mode" to case.responseMode.name,
            "social_act" to case.socialAct.name,
            "speech_intent" to case.speechIntent,
            "recent_turns" to case.recentTurns.map(::turnPayload),
            "references" to
                case.matches.zip(providerPatterns).mapIndexed { index, (match, pattern) ->
                    linkedMapOf(
                        "rank" to index + 1,
                        "prompt_surface" to match.example.promptSurface.name,
                        "provider_style_pattern" to pattern.lines,
                    )
                },
        )
    }

    private fun turnPayload(turn: ConversationTurn): Map<String, String> =
        linkedMapOf(
            "speaker" to turn.speakerLabel,
            "text" to turn.text,
        )

    private fun caseSortKey(
        candidateDigest: String,
        case: HumanSpeechStyleBlindReviewCase,
    ): String =
        sha256(
            buildString {
                append(candidateDigest)
                append('|')
                append(case.responseMode.name)
                append('|')
                append(case.socialAct.name)
                append('|')
                append(case.speechIntent)
                case.recentTurns.forEach { turn ->
                    append('|')
                    append(turn.speakerLabel)
                    append(':')
                    append(turn.text)
                }
            },
        )

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private val SHA256_HEX = Regex("[0-9a-f]{64}")
}

internal data class HumanSpeechStyleBlindReviewCase(
    val responseMode: HumanSpeechResponseMode,
    val socialAct: SpeechSocialAct,
    val recentTurns: List<ConversationTurn>,
    val speechIntent: String,
    val matches: List<HumanSpeechStyleMatch>,
)
