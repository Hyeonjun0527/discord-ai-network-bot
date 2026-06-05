package com.discordassistant.central.routing

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

@Component
class RoutingHedgingPolicy(
    private val maxOutstandingHedges: Int = 2,
) {
    private val outstanding = AtomicInteger(0)

    fun tryAcquire(
        ctx: RequestContext,
        spareProviderCapacity: Int,
        deadlineSlackMillis: Long,
    ): Boolean {
        if (!ctx.hedgingAllowed || !ctx.highPriority) return false
        if (ctx.retryCount > 0 || spareProviderCapacity < 2 || deadlineSlackMillis > 2_000L) return false
        while (true) {
            val current = outstanding.get()
            if (current >= maxOutstandingHedges) return false
            if (outstanding.compareAndSet(current, current + 1)) return true
        }
    }

    fun release() {
        while (true) {
            val current = outstanding.get()
            if (current <= 0) return
            if (outstanding.compareAndSet(current, current - 1)) return
        }
    }

    fun outstandingHedges(): Int = outstanding.get()
}
