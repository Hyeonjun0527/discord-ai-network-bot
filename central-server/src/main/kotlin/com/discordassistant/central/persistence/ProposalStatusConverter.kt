package com.discordassistant.central.persistence

import com.discordassistant.central.domain.ProposalStatus
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * `ProposalStatus` ↔ DB VARCHAR 변환기.
 *
 * DB 컬럼(`ai_change_proposal.status`)에는 이미 **소문자**("pending"/"approved"/...) 값이 들어 있으므로
 * [ProposalStatus.wire](소문자)로 그대로 저장/조회한다. 따라서 스키마 마이그레이션이 필요 없다.
 * 알 수 없는/깨진 문자열은 [ProposalStatus.fromWire] 가 견고하게 [ProposalStatus.PENDING] 으로 폴백한다.
 */
@Converter(autoApply = false)
class ProposalStatusConverter : AttributeConverter<ProposalStatus, String> {
    override fun convertToDatabaseColumn(attribute: ProposalStatus?): String = (attribute ?: ProposalStatus.PENDING).wire

    override fun convertToEntityAttribute(dbData: String?): ProposalStatus = ProposalStatus.fromWire(dbData)
}
