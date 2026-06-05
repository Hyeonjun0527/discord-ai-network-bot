package com.discordassistant.central.platform.discord.command

import com.discordassistant.central.knowledge.application.KnowledgeIndexingService
import com.discordassistant.central.knowledge.application.KnowledgeIngestionService
import com.discordassistant.central.knowledge.application.KnowledgeSearchService
import com.discordassistant.central.platform.discord.CommandContext
import com.discordassistant.central.platform.discord.Replies
import com.discordassistant.central.platform.discord.Reply
import org.springframework.stereotype.Component

/**
 * 채널 지식공간/RAG 명령군(knowledgeList, addKnowledge, searchKnowledge, knowledgeIndexPlan,
 * knowledgeIndexJobs, completeKnowledgeIndexJob, approveKnowledge, deleteKnowledge).
 * CommandService 에서 응집 단위로 분리 — 문구/포맷 출력 불변, 시그니처 유지·위임.
 */
@Component
class KnowledgeCommandHandler(
    private val knowledgeIngestion: KnowledgeIngestionService,
    private val knowledgeIndexing: KnowledgeIndexingService,
    private val knowledgeSearch: KnowledgeSearchService,
    private val guards: SharedCommandGuards,
) {
    fun knowledgeList(
        ctx: CommandContext,
        spaceId: Long? = null,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        return runCatching {
            if (spaceId != null) {
                val status = knowledgeIngestion.spaceStatus(ctx.guildId, spaceId)
                val sources = knowledgeIngestion.listSources(ctx.guildId, spaceId)
                val sourceRows =
                    sources.take(12).map {
                        "• `${it.id}` ${it.title} — ${it.sourceType} · ${it.status} · risk=${it.riskLevel}"
                    }
                val sourceLines = sourceRows.joinToString("\n").ifBlank { "• 아직 지식 소스가 없습니다." }
                Reply(
                    "📚 **채널 지식공간 상세**\n\n" +
                        "space `${status.knowledgeSpaceId}` · <#${status.channelId ?: ctx.channelId}> · ${status.displayName}\n" +
                        "준비상태: `${status.readiness}` · 소스 ${status.sourceCount}개 · indexed ${status.indexedSourceCount}개 · " +
                        "blocked ${status.blockedSourceCount}개\n\n" +
                        "__지식 소스__\n$sourceLines",
                )
            } else {
                val readiness = knowledgeIngestion.guildReadiness(ctx.guildId)
                val spaceRows =
                    readiness.spaces.take(12).map {
                        "• `${it.knowledgeSpaceId}` <#${it.channelId ?: ctx.channelId}> — ${it.displayName} · " +
                            "${it.readiness} · sources ${it.sourceCount}/indexed ${it.indexedSourceCount}"
                    }
                val spaceLines =
                    spaceRows
                        .joinToString("\n")
                        .ifBlank {
                            "• 아직 지식공간이 없습니다. `/지식추가 title:<제목> url:<https://...>` 로 현재 채널 지식공간을 만들 수 있어요."
                        }
                val next =
                    readiness.nextActions
                        .take(4)
                        .joinToString("\n") { "• $it" }
                        .ifBlank { "• 추가 조치 없음" }
                Reply(
                    "📚 **RAG 지식공간 목록**\n\n" +
                        "상태: `${readiness.status}` · 지식공간 ${readiness.spaceCount}개 · 소스 ${readiness.sourceCount}개 · " +
                        "indexed ${readiness.indexedSourceCount}개 · blocked ${readiness.blockedSourceCount}개\n\n" +
                        "__공간__\n$spaceLines\n\n" +
                        "__다음 행동__\n$next",
                )
            }
        }.getOrElse {
            Replies.warn("지식공간을 조회하지 못했어요. ${it.message ?: "길드/space-id를 확인해 주세요."}")
        }
    }

    fun addKnowledge(
        ctx: CommandContext,
        title: String,
        sourceType: String?,
        sourceUri: String?,
        contentPreview: String?,
        spaceId: Long? = null,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        val normalizedUri = sourceUri?.trim()?.ifBlank { null }
        val normalizedPreview = contentPreview?.trim()?.ifBlank { null }
        if (normalizedUri == null && normalizedPreview == null) {
            return Replies.warn("추가할 URL 또는 텍스트를 입력해 주세요. 민감정보·비밀번호·API 키는 넣지 마세요.")
        }
        return runCatching {
            val targetSpaceId =
                spaceId
                    ?: knowledgeIngestion
                        .guildReadiness(ctx.guildId)
                        .spaces
                        .firstOrNull { it.channelId == ctx.channelId }
                        ?.knowledgeSpaceId
                    ?: run {
                        val created =
                            knowledgeIngestion.createSpace(
                                guildId = ctx.guildId,
                                channelId = ctx.channelId,
                                channelAiId = null,
                                displayName = "채널 ${ctx.channelId} 지식공간",
                                createdBy = ctx.userId,
                                embeddingModel = null,
                                indexName = null,
                            )
                        created.id
                    }
            val type = sourceType?.trim()?.ifBlank { null } ?: if (normalizedUri != null) "link" else "text"
            val source =
                knowledgeIngestion.addSource(
                    guildId = ctx.guildId,
                    spaceId = targetSpaceId,
                    sourceType = type,
                    title = title,
                    sourceUri = normalizedUri,
                    contentPreview = normalizedPreview,
                    addedBy = ctx.userId,
                )
            val inlineIndexing =
                knowledgeIndexing.indexInlineSourceIfPossible(
                    guildId = ctx.guildId,
                    spaceId = targetSpaceId,
                    sourceId = source.id,
                    documentText = normalizedPreview,
                    triggeredBy = ctx.userId,
                )
            val plan = knowledgeIngestion.indexingPlan(ctx.guildId, targetSpaceId)
            val effectiveStatus = if (inlineIndexing.indexed) "indexed" else source.status
            val indexingHint =
                when {
                    inlineIndexing.indexed ->
                        "텍스트를 즉시 검색 가능하게 색인했습니다. embedding 재빌드 작업 `${inlineIndexing.jobId}` 도 큐에 넣었어요."
                    source.status == "pending" ->
                        "색인 대기 상태입니다. 운영자는 `${plan.command}` 를 실행해 검색 가능하게 만드세요."
                    else -> "상태가 `${source.status}` 입니다. 위험도 `${source.riskLevel}` 를 검토한 뒤 승인/삭제하세요."
                }
            Replies.ok(
                "지식 소스를 추가했습니다.\n" +
                    "space: `$targetSpaceId` · source: `${source.id}` · status: `$effectiveStatus` · risk: `${source.riskLevel}`\n" +
                    "$indexingHint\n\n" +
                    "`/지식목록 space-id:$targetSpaceId` 로 현재 목록을 확인할 수 있어요.",
            )
        }.getOrElse {
            Replies.warn("지식 소스 추가에 실패했어요. ${it.message ?: "입력값을 확인해 주세요."}")
        }
    }

    fun searchKnowledge(
        ctx: CommandContext,
        query: String,
        spaceId: Long? = null,
        limit: Int = 5,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        return runCatching {
            val result =
                knowledgeSearch.search(
                    guildId = ctx.guildId,
                    query = query,
                    limit = limit,
                    channelId = if (spaceId == null) ctx.channelId else null,
                    knowledgeSpaceId = spaceId,
                )
            val resultRows =
                result.results.take(10).map {
                    val ref = it.sourceUri?.let { uri -> " · ${uri.take(80)}" } ?: ""
                    val preview = it.contentPreview?.let { text -> " · ${text.take(100)}" } ?: ""
                    "• `${it.sourceId}` ${it.title} — score ${it.score} · ${it.sourceType}$ref$preview"
                }
            val lines =
                resultRows
                    .joinToString("\n")
                    .ifBlank {
                        when (result.fallbackReason) {
                            "blocked_sensitive_query" -> "• 민감정보처럼 보이는 검색어라 RAG 검색을 막았습니다."
                            "rag_scope_required" -> "• 검색 범위가 없습니다. 현재 채널 또는 space-id를 지정해 주세요."
                            "no_knowledge_space" -> "• 이 채널에 지식공간이 없습니다. 먼저 `/지식추가` 로 지식을 추가하세요."
                            "no_indexed_knowledge_match" -> "• 검색 가능한 indexed 지식에서 결과를 찾지 못했습니다. 색인 상태를 확인하세요."
                            else -> "• 결과 없음"
                        }
                    }
            Reply(
                "🔎 **채널 지식 검색**\n" +
                    "query: `$query` · scope: `${spaceId?.let { "space:$it" } ?: "current-channel"}`" +
                    (result.fallbackReason?.let { " · fallback: `$it`" } ?: "") +
                    "\n\n$lines",
            )
        }.getOrElse {
            Replies.warn("지식 검색에 실패했어요. ${it.message ?: "검색어/space-id를 확인해 주세요."}")
        }
    }

    fun knowledgeIndexPlan(
        ctx: CommandContext,
        spaceId: Long? = null,
        force: Boolean = false,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        return runCatching {
            if (spaceId != null) {
                val plan = knowledgeIngestion.indexingPlan(ctx.guildId, spaceId, force)
                val indexableRows =
                    plan.indexableSources.take(8).map {
                        "• `${it.id}` ${it.title} — ${it.status} · risk=${it.riskLevel}"
                    }
                val indexable = indexableRows.joinToString("\n").ifBlank { "• 색인할 소스 없음" }
                val blockedRows =
                    plan.blockedSources.take(8).map {
                        "• `${it.id}` ${it.title} — ${it.status} · risk=${it.riskLevel}"
                    }
                val blocked = blockedRows.joinToString("\n").ifBlank { "• 차단/검토 소스 없음" }
                Reply(
                    "🧭 **RAG 색인 계획**\n\n" +
                        "space `$spaceId` · ready `${plan.ready}` · runtime `${plan.runtime}`\n" +
                        "명령:\n`${plan.command}`\n\n" +
                        "__색인 대상__\n$indexable\n\n" +
                        "__검토/차단__\n$blocked",
                )
            } else {
                val ops = knowledgeIngestion.indexingOperations(ctx.guildId, force)
                val commandRows = ops.commands.take(5).map { "• `$it`" }
                val commands = commandRows.joinToString("\n").ifBlank { "• 실행할 색인 명령 없음" }
                val nextRows = ops.nextActions.take(5).map { "• $it" }
                val next = nextRows.joinToString("\n").ifBlank { "• 추가 조치 없음" }
                Reply(
                    "🧭 **RAG 색인 운영 계획**\n\n" +
                        "상태 `${ops.status}` · spaces ${ops.spaceCount} · readyPlans ${ops.readyPlanCount} · " +
                        "indexable ${ops.indexableSourceCount} · blocked ${ops.blockedSourceCount}\n\n" +
                        "__명령__\n$commands\n\n" +
                        "__다음 행동__\n$next",
                )
            }
        }.getOrElse {
            Replies.warn("색인 계획을 만들지 못했어요. ${it.message ?: "space-id를 확인해 주세요."}")
        }
    }

    fun knowledgeIndexJobs(
        ctx: CommandContext,
        spaceId: Long? = null,
        limit: Int = 10,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        return runCatching {
            val jobs = knowledgeIndexing.listIndexJobs(ctx.guildId, spaceId, limit)
            val rows =
                jobs.map {
                    "• `${it.id}` space `${it.knowledgeSpaceId}` · ${it.status} · chunks ${it.chunkCount} · " +
                        "${it.queuedAt}${it.failureReason?.let { reason -> " · $reason" } ?: ""}"
                }
            val lines = rows.joinToString("\n").ifBlank { "• 최근 RAG 색인 작업이 없습니다." }
            Reply(
                "🧱 **RAG 색인 작업 큐**\n" +
                    "scope: `${spaceId?.let { "space:$it" } ?: "guild"}` · limit `$limit`\n\n" +
                    "$lines\n\n" +
                    "완료/실패 처리는 `/지식색인완료 job-id:<id> status:<completed|failed|cancelled>` 로 기록하세요.",
            )
        }.getOrElse {
            Replies.warn("색인 작업을 조회하지 못했어요. ${it.message ?: "space-id를 확인해 주세요."}")
        }
    }

    fun completeKnowledgeIndexJob(
        ctx: CommandContext,
        jobId: Long,
        status: String = "completed",
        reason: String? = null,
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        return runCatching {
            val job =
                knowledgeIndexing.completeIndexJobSafely(
                    guildId = ctx.guildId,
                    jobId = jobId,
                    status = status,
                    failureReason = reason,
                )
            Replies.ok(
                "RAG 색인 작업 상태를 기록했습니다.\n" +
                    "job: `${job.id}` · space: `${job.knowledgeSpaceId}` · status: `${job.status}` · chunks: `${job.chunkCount}`" +
                    (job.failureReason?.let { "\nreason: `$it`" } ?: ""),
            )
        }.getOrElse {
            Replies.warn("색인 작업 상태를 기록하지 못했어요. ${it.message ?: "job-id/status를 확인해 주세요."}")
        }
    }

    fun approveKnowledge(
        ctx: CommandContext,
        spaceId: Long,
        sourceId: Long,
        reason: String = "approved from Discord",
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        return runCatching {
            val source = knowledgeIngestion.approveSourceForIndexing(ctx.guildId, spaceId, sourceId, reason)
            Replies.ok(
                "지식 소스를 색인 대기 상태로 승인했습니다.\n" +
                    "space: `$spaceId` · source: `${source.id}` · status: `${source.status}` · risk: `${source.riskLevel}`\n" +
                    "`/지식색인계획 space-id:$spaceId` 로 색인 명령을 확인하세요.",
            )
        }.getOrElse {
            Replies.warn("지식 소스를 승인하지 못했어요. ${it.message ?: "review 위험도 소스인지 확인해 주세요."}")
        }
    }

    fun deleteKnowledge(
        ctx: CommandContext,
        spaceId: Long,
        sourceId: Long,
        reason: String = "deleted from Discord",
    ): Reply {
        guards.adminOnly(ctx)?.let { return it }
        return runCatching {
            val source = knowledgeIngestion.removeSource(ctx.guildId, spaceId, sourceId, reason)
            val deletionIndex =
                knowledgeIndexing.tombstoneDeletedSourceIndex(
                    guildId = ctx.guildId,
                    spaceId = spaceId,
                    sourceId = source.id,
                    triggeredBy = ctx.userId,
                )
            Replies.ok(
                "지식 소스를 삭제했습니다. space `$spaceId` · source `${source.id}` · status `${source.status}`\n" +
                    "재색인 작업: `${deletionIndex.jobId}` · 제거된 chunk `${deletionIndex.tombstonedChunkCount}`",
            )
        }.getOrElse {
            Replies.warn("지식 소스를 삭제하지 못했어요. ${it.message ?: "space-id/source-id를 확인해 주세요."}")
        }
    }
}
