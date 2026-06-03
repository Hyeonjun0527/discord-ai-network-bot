package com.discordassistant.central.network

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 자동 온보딩 백필 텍스트를 RAG 지식공간/소스로 색인하는 **별도 트랜잭션 경계** 빈.
 *
 * 왜 별도 빈인가(S3 — 공유 트랜잭션 rollback-only 함정 방지):
 * [GuildOnboardingService.startOnboarding] 의 트랜잭션 안에서 색인(KnowledgeIngestion/Indexing, 별도 @Transactional)이
 * REQUIRED 로 합류한 채 RuntimeException 을 던지면 Spring 이 **공유 트랜잭션을 rollback-only** 로 마크해,
 * 호출부가 runCatching 으로 삼켜도 외부 커밋 시 UnexpectedRollbackException 으로 consent/proposal/run 까지 통째 롤백된다.
 *
 * 이 빈의 [indexBackfill] 은 `Propagation.REQUIRES_NEW` 로 **독립 트랜잭션**에서 색인한다. 색인이 실패하면 이 내부
 * 트랜잭션만 롤백되고 외부(온보딩 본체)는 보존된다. 또한 별도 Spring 빈이라 프록시를 거치므로 propagation 이 실제로 적용된다
 * (같은 빈 자기호출이면 프록시 우회로 REQUIRES_NEW 가 무시됨 — 그래서 일부러 분리했다).
 */
@Service
class OnboardingBackfillIndexer(
    private val knowledgeIngestion: KnowledgeIngestionService,
    private val knowledgeIndexing: KnowledgeIndexingService,
) {
    /**
     * 정제된 백필 텍스트를 지식공간/소스로 색인한다(독립 트랜잭션). 빈 텍스트면 null.
     * 민감/SSRF 위험으로 자동 색인이 막히면 `indexed=false` 로 남고 검토 큐로 간다(기존 indexInlineSourceIfPossible 동작).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun indexBackfill(
        guildId: Long,
        channelId: Long,
        channelAiId: Long,
        actorUserId: Long?,
        indexText: String,
    ): BackfillIndexResult? {
        val text = indexText.trim()
        if (text.isBlank()) return null
        val space =
            knowledgeIngestion.createSpace(
                guildId = guildId,
                channelId = channelId,
                channelAiId = channelAiId,
                displayName = "서버 대화 요약",
                createdBy = actorUserId,
                embeddingModel = null,
                indexName = null,
            )
        val source =
            knowledgeIngestion.addSource(
                guildId = guildId,
                spaceId = space.id,
                sourceType = "faq",
                title = "서버 대화 요약",
                sourceUri = null,
                contentPreview = text,
                addedBy = actorUserId,
            )
        // 위험도 normal/review + status pending 일 때만 인라인 색인(민감/SSRF 는 검토 큐로).
        val indexResult =
            knowledgeIndexing.indexInlineSourceIfPossible(
                guildId = guildId,
                spaceId = space.id,
                sourceId = source.id,
                documentText = text,
                triggeredBy = actorUserId,
            )
        return BackfillIndexResult(knowledgeSpaceId = space.id, indexed = indexResult.indexed)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(OnboardingBackfillIndexer::class.java)
    }
}

/** 백필 색인 결과 — 지식공간 id 와 자동 색인 여부(false 면 검토 큐 대기). */
data class BackfillIndexResult(
    val knowledgeSpaceId: Long,
    val indexed: Boolean,
)
