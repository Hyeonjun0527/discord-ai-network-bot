package com.discordassistant.central.guild.application

import com.discordassistant.central.guild.adapter.outbound.persistence.GuildEntity
import com.discordassistant.central.guild.adapter.outbound.persistence.GuildRepository
import com.discordassistant.central.guild.domain.model.PrivacyMode
import com.discordassistant.central.shared.ModelBurden
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 프라이버시 처리주체 표시 (K-차수 14, specs §10). 모드 A(익명)/B(부분 공개)/C(관리자만, 기본).
 */
@Service
class PrivacyService(
    private val guilds: GuildRepository,
) {
    fun mode(guildId: Long): PrivacyMode =
        guilds
            .findById(guildId)
            .map {
                runCatching { PrivacyMode.valueOf(it.privacyMode) }.getOrDefault(PrivacyMode.DEFAULT)
            }.orElse(PrivacyMode.DEFAULT)

    @Transactional
    fun setMode(
        guildId: Long,
        mode: PrivacyMode,
    ) {
        // privacy_mode 컬럼만 targeted UPDATE — 전체 엔티티 저장은 stale 스냅샷으로 동시 쓰인 auto_approve·
        // 기본값을 되돌리는 lost update 를 낳는다. 행이 없으면(신규 길드) 기본 엔티티로 생성한다(신규 insert 라 lost update 없음).
        if (guilds.updatePrivacyMode(guildId, mode.name) == 0) {
            guilds.save(GuildEntity(id = guildId, privacyMode = mode.name))
        }
    }

    /** 요청 처리 결과에 붙일 처리주체 안내 문구. */
    fun processedNotice(
        guildId: Long,
        burden: ModelBurden?,
        providerId: Long?,
        isAdmin: Boolean,
    ): String {
        val level = burden?.let { " · 모델 수준 $it" } ?: ""
        return when (mode(guildId)) {
            PrivacyMode.A_ANONYMOUS -> "커뮤니티 로컬 AI 풀에서 처리됨$level"
            PrivacyMode.B_PARTIAL -> "커뮤니티 프로바이더가 처리$level · 처리 위치: community local provider"
            PrivacyMode.C_ADMIN_ONLY ->
                if (isAdmin && providerId != null) {
                    "함께 만드는 AI 네트워크에서 처리됨$level · Provider 상세는 관리자 대시보드에서 확인"
                } else {
                    "함께 만드는 AI 네트워크에서 처리됨"
                }
        }
    }
}
