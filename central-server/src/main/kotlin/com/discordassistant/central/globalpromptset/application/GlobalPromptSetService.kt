package com.discordassistant.central.globalpromptset.application

import com.discordassistant.central.globalpromptset.adapter.outbound.persistence.GlobalPromptSetEntity
import com.discordassistant.central.globalpromptset.adapter.outbound.persistence.GlobalPromptSetRepository
import com.discordassistant.central.shared.CodeNiaPromptSource
import com.discordassistant.central.shared.NexaIdentity
import com.discordassistant.central.shared.NiaPromptKey
import com.discordassistant.central.shared.NiaPromptSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * 길드 전역 프롬프트셋(서버 전체 기본 AI 성격) 관리.
 *
 * 매 질문마다 고르는 게 아니라 "서버 전체 기본 성격을 무엇으로 둘지" 한 번 설정하는 구조다. 관리자가 셋을
 * 추가/삭제하고 하나를 기본으로 지정하면, 그 길드의 모든 `/ask` 기본 답변이 그 성격으로 응답한다. 기본으로
 * 지정된 사용자 셋이 없으면 NEXA 기본 정체성(니아, [NexaIdentity.NIA_DEFAULT_PERSONA])이 적용된다.
 *
 * 비공개: builtin(니아) 셋은 영업·안전상 전문(content)을 클라이언트로 내리지 않고 preview 만 노출한다.
 * 사용자가 직접 작성한 셋은 본인 소유이므로 content 전문을 반환한다.
 */
@Service
class GlobalPromptSetService(
    private val sets: GlobalPromptSetRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val promptSource: NiaPromptSource = CodeNiaPromptSource,
) {
    /**
     * 이 길드의 `/ask` 기본 정체성으로 쓸 프롬프트 본문. 기본 지정된 사용자 셋이 있으면 그 content,
     * 없으면 NEXA 기본 정체성(니아) 전문을 반환한다. AskCommandHandler 가 가드레일과 함께 주입한다.
     */
    @Transactional(readOnly = true)
    fun activePersona(guildId: Long): String =
        sets.findFirstByGuildIdAndIsDefaultTrueOrderByIdAsc(guildId)?.content?.takeIf { it.isNotBlank() }
            ?: promptSource.text(NiaPromptKey.IDENTITY_PERSONA)

    /** 목록 — builtin 니아(preview 만) + 사용자 셋(content 전문). 기본 지정된 사용자 셋이 없으면 니아가 기본. */
    @Transactional(readOnly = true)
    fun list(guildId: Long): List<GlobalPromptSetView> {
        val rows = sets.findByGuildIdOrderByIdAsc(guildId)
        val anyUserDefault = rows.any { it.isDefault }
        val builtin =
            GlobalPromptSetView(
                id = BUILTIN_NIA_ID,
                name = "니아 (기본 페르소나)",
                builtin = true,
                isDefault = !anyUserDefault,
                preview = NexaIdentity.NIA_PREVIEW,
                content = null,
            )
        return listOf(builtin) +
            rows.map {
                GlobalPromptSetView(
                    id = it.id.toString(),
                    name = it.name,
                    builtin = false,
                    isDefault = it.isDefault,
                    preview = null,
                    content = it.content,
                )
            }
    }

    /** 새 전역 프롬프트셋 추가(사용자 작성). 추가만으로 기본이 되지는 않는다. */
    @Transactional
    fun add(
        guildId: Long,
        name: String,
        content: String,
        actorUserId: Long?,
    ): GlobalPromptSetView {
        val trimmedName = name.trim().take(MAX_NAME_CHARS)
        val trimmedContent = content.trim().take(MAX_CONTENT_CHARS)
        require(trimmedName.isNotBlank()) { "name_required" }
        require(trimmedContent.isNotBlank()) { "content_required" }
        require(!sets.existsByGuildIdAndName(guildId, trimmedName)) { "duplicate_name" }
        require(sets.findByGuildIdOrderByIdAsc(guildId).size < MAX_SETS_PER_GUILD) { "too_many_sets" }
        val saved =
            sets.save(
                GlobalPromptSetEntity(
                    guildId = guildId,
                    name = trimmedName,
                    content = trimmedContent,
                    isDefault = false,
                    createdBy = actorUserId,
                    createdAt = Instant.now(clock),
                ),
            )
        return GlobalPromptSetView(saved.id.toString(), saved.name, false, false, null, saved.content)
    }

    /** 삭제. 기본이던 셋을 지우면 기본 지정이 사라져 니아로 되돌아간다. builtin(니아)은 삭제 불가. */
    @Transactional
    fun delete(
        guildId: Long,
        id: String,
    ) {
        require(id != BUILTIN_NIA_ID) { "cannot_delete_builtin" }
        val entity = sets.findByGuildIdAndId(guildId, id.toLongOrNull() ?: return) ?: return
        sets.delete(entity)
    }

    /**
     * 기본 셋 지정. id 가 builtin(니아)이면 모든 사용자 셋의 기본을 해제(→ 니아). 사용자 셋 id 면 그 셋만
     * 기본으로 두고 나머지를 해제한다. 길드당 기본은 1개 — DB partial 인덱스 대신, 길드 셋을
     * PESSIMISTIC_WRITE([GlobalPromptSetRepository.findByGuildIdForUpdate])로 잠가 동시 setDefault 를
     * 직렬화함으로써 clear-all-then-set 을 원자화한다(is_default=true 가 둘 남는 race → activePersona 가
     * 매 /ask 에서 IncorrectResultSize 로 터지던 것을 방지). 읽기측은 findFirst 로 결정적 단일 선택(방어 이중화).
     */
    @Transactional
    fun setDefault(
        guildId: Long,
        id: String,
    ) {
        val rows = sets.findByGuildIdForUpdate(guildId)
        if (id == BUILTIN_NIA_ID) {
            rows.filter { it.isDefault }.forEach { it.isDefault = false }
            sets.saveAll(rows)
            return
        }
        val targetId = id.toLongOrNull() ?: throw IllegalArgumentException("unknown_set")
        require(rows.any { it.id == targetId }) { "unknown_set" }
        rows.forEach { it.isDefault = it.id == targetId }
        sets.saveAll(rows)
    }

    companion object {
        const val BUILTIN_NIA_ID = "nia"
        const val MAX_NAME_CHARS = 80
        const val MAX_CONTENT_CHARS = 2_000
        const val MAX_SETS_PER_GUILD = 20
    }
}

/**
 * 전역 프롬프트셋 표시 모델. builtin(니아)은 preview 만(전문 비공개), 사용자 셋은 content 전문을 담는다.
 */
data class GlobalPromptSetView(
    val id: String,
    val name: String,
    val builtin: Boolean,
    val isDefault: Boolean,
    val preview: String?,
    val content: String?,
)
