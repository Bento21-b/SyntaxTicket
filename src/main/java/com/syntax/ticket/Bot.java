package com.syntax.ticket;

import com.syntax.ticket.listeners.CommandListener;
import com.syntax.ticket.listeners.TicketListener;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;

public final class Bot {
    private Bot() {}

    public static void main(String[] args) throws InterruptedException {
        String token = requiredEnv("DISCORD_TOKEN");

        JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MESSAGES)
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .setActivity(Activity.watching("tickets"))
                .addEventListeners(new CommandListener(), new TicketListener())
                .build()
                .awaitReady();

        System.out.println("Syntax ticket bot is online.");
    }

    public static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value.trim();
    }

    public static String optionalEnv(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
