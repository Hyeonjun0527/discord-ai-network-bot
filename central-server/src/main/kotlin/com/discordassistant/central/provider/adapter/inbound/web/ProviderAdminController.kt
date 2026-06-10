package com.discordassistant.central.provider.adapter.inbound.web

import com.discordassistant.central.channelai.application.GuildChannelAiQuery
import com.discordassistant.central.globalpromptset.application.GlobalPromptSetService
import com.discordassistant.central.globalpromptset.application.GlobalPromptSetView
import com.discordassistant.central.guild.application.GuildChannelPolicy
import com.discordassistant.central.knowledge.application.GuildKnowledgeAdmin
import com.discordassistant.central.knowledge.application.GuildKnowledgeQuery
import com.discordassistant.central.platform.discord.BotGuildLister
import com.discordassistant.central.preset.application.GuildPresetAdmin
import com.discordassistant.central.preset.application.GuildPresetQuery
import com.discordassistant.central.provider.application.ProviderRegistrationService
import com.discordassistant.central.provider.application.ProviderRosterInfo
import com.discordassistant.central.provider.application.TokenService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 관리 작업 요청. durableToken=요청자(관리자) 신원, guildId=대상 서버, targetProviderId=대상 프로바이더(목록 조회 땐 무시). */
data class AdminActionRequest(
    val durableToken: String = "",
    val guildId: Long = 0,
    val targetProviderId: Long = 0,
)

data class AdminActionResponse(
    val ok: Boolean,
    val message: String = "",
    val token: String? = null,
)

/** 로스터 항목 — 이름·상태·제공 모델 수·오늘 처리 건수(관리 화면 13). providerId 는 64bit → 문자열(JS 정밀도). */
data class ManageProviderDto(
    val providerId: String,
    val name: String?,
    val state: String,
    val models: Int,
    val today: Long,
)

/** 승인 대기 항목 — 아직 미연결이라 이름만(모델/통계는 승인·연결 후). providerId 는 64bit → 문자열. */
data class ManagePendingDto(
    val providerId: String,
    val name: String?,
)

data class ManagePolicyDto(
    val autoApprove: Boolean,
)

data class ManageResponse(
    val ok: Boolean,
    val policy: ManagePolicyDto? = null,
    val pending: List<ManagePendingDto> = emptyList(),
    val roster: List<ManageProviderDto> = emptyList(),
)

/** 서버 제공 정책 변경 — 신규 자동 승인 토글. */
data class AdminPolicyRequest(
    val durableToken: String = "",
    val guildId: Long = 0,
    val autoApprove: Boolean = false,
)

/** 전역 프롬프트셋 관리 요청. id=default/delete 대상("nia"=기본 페르소나), name/content=add 용. */
data class AdminPromptSetRequest(
    val durableToken: String = "",
    val guildId: Long = 0,
    val id: String = "",
    val name: String = "",
    val content: String = "",
)

data class AdminPromptSetResponse(
    val ok: Boolean,
    val message: String = "",
    val sets: List<GlobalPromptSetView> = emptyList(),
)

/** 채널 목록 조회 요청. */
data class AdminChannelsRequest(
    val durableToken: String = "",
    val guildId: Long = 0,
)

/** 채널 AI 허용 토글 요청. allow=true 허용 / false 금지. */
data class AdminChannelToggleRequest(
    val durableToken: String = "",
    val guildId: Long = 0,
    val channelId: Long = 0,
    val allow: Boolean = true,
)

/** 관리 화면 채널 항목. channelId 는 64bit Discord ID — JSON number 정밀도 손실 방지로 문자열. */
data class ManageChannelDto(
    val channelId: String,
    val name: String,
    val aiAllowed: Boolean,
)

data class AdminChannelsResponse(
    val ok: Boolean,
    val message: String = "",
    val channels: List<ManageChannelDto> = emptyList(),
)

// ── 읽기 전용 관리 탭(채널AI/RAG/프리셋) — durable-token 브리지. 모든 64bit id 는 문자열. ──

/** 채널 AI 프로필 항목(관리 화면 09 읽기). model 은 채널 AI 가 직접 저장하지 않아 미포함(라우팅 정책 소관). */
data class ManageChannelAiDto(
    val channelId: String,
    val name: String,
    val tone: String,
    val purpose: String,
)

data class AdminChannelAiResponse(
    val ok: Boolean,
    val message: String = "",
    val items: List<ManageChannelAiDto> = emptyList(),
)

/** 지식 소스 항목(관리 화면 10 읽기). */
data class ManageKnowledgeDocDto(
    val id: String,
    val title: String,
    val status: String,
    val riskLevel: String,
    val addedAt: String,
    val indexedAt: String?,
)

data class AdminKnowledgeResponse(
    val ok: Boolean,
    val message: String = "",
    val docs: List<ManageKnowledgeDocDto> = emptyList(),
)

/** 지식 소스 삭제 요청(관리자). sourceId 는 64bit 안전을 위해 문자열로 받아 Long 변환. */
data class AdminKnowledgeDeleteRequest(
    val durableToken: String = "",
    val guildId: Long = 0,
    val sourceId: String = "",
)

/** 프리셋 항목(관리 화면 11 읽기). */
data class ManagePresetDto(
    val id: String,
    val name: String,
    val category: String,
    val status: String,
    val summary: String?,
)

data class AdminPresetsResponse(
    val ok: Boolean,
    val message: String = "",
    val presets: List<ManagePresetDto> = emptyList(),
)

/** 프리셋 삭제 요청(관리자). presetId 는 64bit 안전을 위해 문자열로 받아 Long 변환. */
data class AdminPresetDeleteRequest(
    val durableToken: String = "",
    val guildId: Long = 0,
    val presetId: String = "",
)

/**
 * 데스크톱 앱(관리자)용 서버 관리 채널 — Provider 승인/거절/제거 + 목록 조회.
 *
 * 인증·권한(2단):
 *  1. **신원**: durable 토큰(dv1.…, providerId=Discord userId)의 HMAC 검증(소모하지 않음).
 *  2. **권한**: 그 사용자가 **대상 길드의 관리자**(MANAGE_SERVER|ADMINISTRATOR)인지 JDA 로 판정.
 *
 * 둘 다 통과해야만 기존 [ProviderRegistrationService] 의 관리 작업을 수행한다. 권한 상승 불가 —
 * "내 durable 토큰" 으로 "내가 관리자인 서버" 만 관리할 수 있다. (웹 OAuth 대시보드와 동등한 권한, 앱 경로.)
 *
 * 기존 슬래시 명령(/provider-approve 등)·웹 대시보드와 **같은 서비스**를 호출하므로 정책·감사 로그가 일관된다.
 */
@RestController
@RequestMapping("/provider/admin")
class ProviderAdminController(
    private val tokens: TokenService,
    private val registration: ProviderRegistrationService,
    private val botGuilds: BotGuildLister,
    private val roster: ProviderRosterInfo,
    private val globalPromptSets: GlobalPromptSetService,
    private val guildChannels: GuildChannelPolicy,
    private val guildChannelAi: GuildChannelAiQuery,
    private val guildKnowledge: GuildKnowledgeQuery,
    private val guildKnowledgeAdmin: GuildKnowledgeAdmin,
    private val guildPresets: GuildPresetQuery,
    private val guildPresetAdmin: GuildPresetAdmin,
    private val licenseGate: com.discordassistant.central.licensing.application.LicenseGate,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(ProviderAdminController::class.java)

    /** durable 토큰 → 요청자 providerId 복원 후 그가 guildId 관리자면 그 id 반환, 아니면 null(거부). */
    private fun authedAdmin(
        durableToken: String,
        guildId: Long,
    ): Long? {
        if (!durableToken.startsWith("dv1.")) return null // 일회용 토큰은 verify 가 소모하므로 거부
        val binding = tokens.verify(durableToken) ?: return null
        return if (botGuilds.isGuildAdmin(guildId, binding.providerId)) binding.providerId else null
    }

    @PostMapping("/approve")
    fun approve(
        @RequestBody req: AdminActionRequest,
    ): AdminActionResponse {
        val adminId =
            authedAdmin(req.durableToken, req.guildId)
                ?: return AdminActionResponse(false, "관리자 권한이 필요합니다")
        val token =
            registration.approve(req.targetProviderId, req.guildId, adminId)
                ?: return AdminActionResponse(false, "승인할 수 없습니다(승인 대기 상태가 아님)")
        return AdminActionResponse(true, "승인됨", token)
    }

    @PostMapping("/reject")
    fun reject(
        @RequestBody req: AdminActionRequest,
    ): AdminActionResponse {
        val adminId =
            authedAdmin(req.durableToken, req.guildId)
                ?: return AdminActionResponse(false, "관리자 권한이 필요합니다")
        return if (registration.reject(req.targetProviderId, req.guildId, adminId)) {
            AdminActionResponse(true, "거절됨")
        } else {
            AdminActionResponse(false, "거절할 수 없습니다(승인 대기 상태가 아님)")
        }
    }

    @PostMapping("/remove")
    fun remove(
        @RequestBody req: AdminActionRequest,
    ): AdminActionResponse {
        val adminId =
            authedAdmin(req.durableToken, req.guildId)
                ?: return AdminActionResponse(false, "관리자 권한이 필요합니다")
        return if (registration.remove(req.targetProviderId, req.guildId, adminId)) {
            AdminActionResponse(true, "제거됨")
        } else {
            AdminActionResponse(false, "제거할 수 없습니다")
        }
    }

    /** 승인 대기·로스터(이름·모델·오늘 건수)·정책 조회(관리 화면 13). 권한 없으면 ok=false. */
    @PostMapping("/manage")
    fun manage(
        @RequestBody req: AdminActionRequest,
    ): ManageResponse {
        authedAdmin(req.durableToken, req.guildId) ?: return ManageResponse(false)
        val models = roster.modelsByProvider(req.guildId)
        val today = roster.todayByProvider(req.guildId)
        val rosterList =
            registration.providersInGuild(req.guildId).map { pid ->
                ManageProviderDto(
                    providerId = pid.toString(),
                    name = botGuilds.memberName(req.guildId, pid),
                    state = registration.stateOf(pid, req.guildId)?.name ?: "UNKNOWN",
                    models = models[pid] ?: 0,
                    today = today[pid] ?: 0L,
                )
            }
        val pending = registration.pending(req.guildId).map { ManagePendingDto(it.toString(), botGuilds.memberName(req.guildId, it)) }
        return ManageResponse(true, ManagePolicyDto(roster.isAutoApprove(req.guildId)), pending, rosterList)
    }

    /** 서버 제공 정책 — 신규 자동 승인 토글(관리자). 기존 PolicyService 와 동일 저장·감사. */
    @PostMapping("/manage/policy")
    fun setPolicy(
        @RequestBody req: AdminPolicyRequest,
    ): AdminActionResponse {
        val adminId =
            authedAdmin(req.durableToken, req.guildId)
                ?: return AdminActionResponse(false, "관리자 권한이 필요합니다")
        roster.setAutoApprove(req.guildId, req.autoApprove, adminId)
        return AdminActionResponse(true, "정책을 저장했어요")
    }

    /** 전역 프롬프트셋(서버 전체 기본 AI 성격) 목록. builtin(니아)은 preview 만(전문 비공개). 권한 없으면 ok=false. */
    @PostMapping("/prompt-sets")
    fun promptSets(
        @RequestBody req: AdminPromptSetRequest,
    ): AdminPromptSetResponse {
        authedAdmin(req.durableToken, req.guildId) ?: return AdminPromptSetResponse(false, "관리자 권한이 필요합니다")
        return AdminPromptSetResponse(true, sets = globalPromptSets.list(req.guildId))
    }

    /** 전역 프롬프트셋 추가(사용자 작성). 추가만으로 기본이 되지는 않는다. */
    @PostMapping("/prompt-sets/add")
    fun addPromptSet(
        @RequestBody req: AdminPromptSetRequest,
    ): AdminPromptSetResponse {
        val adminId =
            authedAdmin(req.durableToken, req.guildId)
                ?: return AdminPromptSetResponse(false, "관리자 권한이 필요합니다")
        licenseGate.denyReason(adminId)?.let { return AdminPromptSetResponse(false, it) } // 프리미엄: 페르소나 작성
        return runCatching { globalPromptSets.add(req.guildId, req.name, req.content, adminId) }
            .fold(
                onSuccess = { AdminPromptSetResponse(true, "추가했어요", globalPromptSets.list(req.guildId)) },
                onFailure = { AdminPromptSetResponse(false, addFailureMessage(it)) },
            )
    }

    /** 기본 셋 지정. id="nia" 면 NEXA 기본 정체성(니아)으로 되돌린다. */
    @PostMapping("/prompt-sets/default")
    fun setDefaultPromptSet(
        @RequestBody req: AdminPromptSetRequest,
    ): AdminPromptSetResponse {
        val adminId =
            authedAdmin(req.durableToken, req.guildId)
                ?: return AdminPromptSetResponse(false, "관리자 권한이 필요합니다")
        licenseGate.denyReason(adminId)?.let { return AdminPromptSetResponse(false, it) } // 프리미엄: 페르소나 기본 지정
        return runCatching { globalPromptSets.setDefault(req.guildId, req.id) }
            .fold(
                onSuccess = { AdminPromptSetResponse(true, "기본으로 지정했어요", globalPromptSets.list(req.guildId)) },
                onFailure = {
                    // 실패 사유를 통째로 버리지 않고 남긴다(예외 원칙 3) — 어떤 셋/길드에서 왜 실패했는지 추적.
                    log.warn("프롬프트셋 기본 지정 실패(guild={}, id={}): {}", req.guildId, req.id, it.message)
                    AdminPromptSetResponse(false, "지정할 수 없는 프롬프트셋이에요")
                },
            )
    }

    /** 전역 프롬프트셋 삭제. 기본이던 셋을 지우면 니아로 되돌아간다. builtin(니아)은 삭제 불가. */
    @PostMapping("/prompt-sets/delete")
    fun deletePromptSet(
        @RequestBody req: AdminPromptSetRequest,
    ): AdminPromptSetResponse {
        val adminId =
            authedAdmin(req.durableToken, req.guildId)
                ?: return AdminPromptSetResponse(false, "관리자 권한이 필요합니다")
        licenseGate.denyReason(adminId)?.let { return AdminPromptSetResponse(false, it) } // 프리미엄: 페르소나 삭제
        return runCatching { globalPromptSets.delete(req.guildId, req.id) }
            .fold(
                onSuccess = { AdminPromptSetResponse(true, "삭제했어요", globalPromptSets.list(req.guildId)) },
                onFailure = { AdminPromptSetResponse(false, "삭제할 수 없어요(기본 페르소나는 삭제 불가)") },
            )
    }

    /**
     * 채널 AI 허용 목록(관리 화면 08). JDA 텍스트 채널 + 허용 정책을 합쳐 채널별 aiAllowed 를 채운다.
     * 허용 목록이 비어 있으면 도메인 의미상 전체 허용이므로 모든 채널이 aiAllowed=true.
     */
    @PostMapping("/channels")
    fun channels(
        @RequestBody req: AdminChannelsRequest,
    ): AdminChannelsResponse {
        authedAdmin(req.durableToken, req.guildId) ?: return AdminChannelsResponse(false, "관리자 권한이 필요합니다")
        return AdminChannelsResponse(true, channels = channelDtos(req.guildId))
    }

    /**
     * 채널 AI 허용 토글. "빈 목록 = 전체 허용" 의미를 보존하려고 전체 채널 집합 기준으로 계산한다:
     * 현재 허용이 비어 있으면(전체 허용) 토글 OFF 는 "그 채널만 빼고 전부 허용"으로 교체해야 한다.
     * 토글 결과가 전체 채널과 같아지면 다시 제한 해제(빈 목록)로 되돌린다.
     */
    @PostMapping("/channels/toggle")
    fun toggleChannel(
        @RequestBody req: AdminChannelToggleRequest,
    ): AdminChannelsResponse {
        val adminId =
            authedAdmin(req.durableToken, req.guildId)
                ?: return AdminChannelsResponse(false, "관리자 권한이 필요합니다")
        val all = botGuilds.botChannels(req.guildId).map { it.id }
        if (all.isEmpty()) return AdminChannelsResponse(false, "채널 목록을 가져올 수 없어요(봇 미연결)")
        val current = guildChannels.allowedChannelIds(req.guildId)
        val effective = (if (current.isEmpty()) all else current).toMutableSet()
        if (req.allow) effective.add(req.channelId) else effective.remove(req.channelId)
        if (effective == all.toSet()) {
            guildChannels.allowAllChannels(req.guildId, adminId)
        } else {
            guildChannels.replaceAllowedChannels(req.guildId, effective, adminId)
        }
        return AdminChannelsResponse(true, channels = channelDtos(req.guildId))
    }

    private fun channelDtos(guildId: Long): List<ManageChannelDto> {
        val allowed = guildChannels.allowedChannelIds(guildId)
        val allowAll = allowed.isEmpty()
        val allowedSet = allowed.toSet()
        return botGuilds.botChannels(guildId).map {
            ManageChannelDto(it.id.toString(), it.name, allowAll || it.id in allowedSet)
        }
    }

    /** 채널 AI 프로필 목록(관리 화면 09 읽기). 추가/편집은 아직 Discord 명령·웹 대시보드 경유(앱 UI 는 안내). */
    @PostMapping("/channel-ai")
    fun channelAi(
        @RequestBody req: AdminChannelsRequest,
    ): AdminChannelAiResponse {
        authedAdmin(req.durableToken, req.guildId) ?: return AdminChannelAiResponse(false, "관리자 권한이 필요합니다")
        val items =
            guildChannelAi.listChannelAis(req.guildId).map {
                ManageChannelAiDto(it.channelId.toString(), it.displayName, it.tone, it.purpose)
            }
        return AdminChannelAiResponse(true, items = items)
    }

    /** 지식 소스(RAG) 목록(관리 화면 10 읽기). RAG 비활성이면 graceful ok=false. */
    @PostMapping("/knowledge")
    fun knowledge(
        @RequestBody req: AdminChannelsRequest,
    ): AdminKnowledgeResponse {
        authedAdmin(req.durableToken, req.guildId) ?: return AdminKnowledgeResponse(false, "관리자 권한이 필요합니다")
        return runCatching {
            guildKnowledge.listGuildSources(req.guildId).map {
                ManageKnowledgeDocDto(it.id.toString(), it.title, it.status, it.riskLevel, it.addedAt, it.indexedAt)
            }
        }.fold(
            onSuccess = { AdminKnowledgeResponse(true, docs = it) },
            onFailure = { AdminKnowledgeResponse(false, "지식 공간(RAG) 기능이 꺼져 있어요") },
        )
    }

    /** 지식 소스 삭제(관리 화면 10 쓰기). 소유권은 길드로 가드. 성공 시 갱신 목록 반환. */
    @PostMapping("/knowledge/delete")
    fun deleteKnowledge(
        @RequestBody req: AdminKnowledgeDeleteRequest,
    ): AdminKnowledgeResponse {
        val adminId =
            authedAdmin(req.durableToken, req.guildId)
                ?: return AdminKnowledgeResponse(false, "관리자 권한이 필요합니다")
        licenseGate.denyReason(adminId)?.let { return AdminKnowledgeResponse(false, it) } // 프리미엄: RAG 지식 삭제
        val sourceId = req.sourceId.toLongOrNull() ?: return AdminKnowledgeResponse(false, "잘못된 소스입니다")
        return runCatching {
            val ok = guildKnowledgeAdmin.removeGuildSource(req.guildId, sourceId)
            if (!ok) return AdminKnowledgeResponse(false, "소스를 찾을 수 없거나 권한이 없어요")
            guildKnowledge.listGuildSources(req.guildId).map {
                ManageKnowledgeDocDto(it.id.toString(), it.title, it.status, it.riskLevel, it.addedAt, it.indexedAt)
            }
        }.fold(
            onSuccess = { AdminKnowledgeResponse(true, "삭제했어요", it) },
            onFailure = { AdminKnowledgeResponse(false, "지식 공간(RAG) 기능이 꺼져 있어요") },
        )
    }

    /** 프리셋 목록(관리 화면 11 읽기). 프리셋 기능 비활성이면 graceful ok=false. */
    @PostMapping("/presets")
    fun presets(
        @RequestBody req: AdminChannelsRequest,
    ): AdminPresetsResponse {
        authedAdmin(req.durableToken, req.guildId) ?: return AdminPresetsResponse(false, "관리자 권한이 필요합니다")
        return runCatching {
            guildPresets.listGuildPresets(req.guildId).map {
                ManagePresetDto(it.id.toString(), it.name, it.category, it.status, it.summary)
            }
        }.fold(
            onSuccess = { AdminPresetsResponse(true, presets = it) },
            onFailure = { AdminPresetsResponse(false, "프리셋 기능이 꺼져 있어요") },
        )
    }

    /** 프리셋 삭제(관리 화면 11 쓰기). 소유권은 길드로 가드(다른 길드 프리셋 삭제 불가). 성공 시 갱신 목록 반환. */
    @PostMapping("/presets/delete")
    fun deletePreset(
        @RequestBody req: AdminPresetDeleteRequest,
    ): AdminPresetsResponse {
        val adminId =
            authedAdmin(req.durableToken, req.guildId)
                ?: return AdminPresetsResponse(false, "관리자 권한이 필요합니다")
        licenseGate.denyReason(adminId)?.let { return AdminPresetsResponse(false, it) } // 프리미엄: 프리셋 삭제
        val presetId = req.presetId.toLongOrNull() ?: return AdminPresetsResponse(false, "잘못된 프리셋입니다")
        return runCatching {
            val ok = guildPresetAdmin.deleteGuildPreset(req.guildId, presetId)
            if (!ok) return AdminPresetsResponse(false, "프리셋을 찾을 수 없거나 권한이 없어요")
            guildPresets.listGuildPresets(req.guildId).map {
                ManagePresetDto(it.id.toString(), it.name, it.category, it.status, it.summary)
            }
        }.fold(
            onSuccess = { AdminPresetsResponse(true, "삭제했어요", it) },
            onFailure = { AdminPresetsResponse(false, "프리셋 기능이 꺼져 있어요") },
        )
    }

    private fun addFailureMessage(e: Throwable): String =
        when (e.message) {
            "duplicate_name" -> "같은 이름의 프롬프트셋이 이미 있어요"
            "too_many_sets" -> "프롬프트셋이 너무 많아요(최대 개수 초과)"
            "name_required" -> "이름을 입력해 주세요"
            "content_required" -> "프롬프트 내용을 입력해 주세요"
            else -> "추가할 수 없어요"
        }
}
