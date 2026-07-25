package com.discordassistant.central.platform.discord

import net.dv8tion.jda.api.requests.GatewayIntent

object GatewayIntentPolicy {
    fun intents(
        messageContentIntentEnabled: Boolean,
        typingIntentEnabled: Boolean = false,
    ): List<GatewayIntent> =
        buildList {
            add(GatewayIntent.GUILD_MESSAGES)
            add(GatewayIntent.GUILD_MESSAGE_REACTIONS)
            if (messageContentIntentEnabled) add(GatewayIntent.MESSAGE_CONTENT)
            if (typingIntentEnabled) add(GatewayIntent.GUILD_MESSAGE_TYPING)
        }
}
