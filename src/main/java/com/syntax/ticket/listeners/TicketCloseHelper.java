package com.syntax.ticket.listeners;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.components.ActionRow;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class TicketCloseHelper {
    static final String CLOSED_TOPIC_PREFIX = "closed:";
    private static final long LOCK_MS = 1_500L;

    private static final ConcurrentHashMap<String, Long> PROCESSING = new ConcurrentHashMap<>();
    private static final Set<String> CLOSED = ConcurrentHashMap.newKeySet();

    private TicketCloseHelper() {}

    static boolean beginProcessing(String channelId) {
        long now = System.currentTimeMillis();
        Long previous = PROCESSING.get(channelId);
        if (previous != null && now - previous < LOCK_MS) {
            return false;
        }
        PROCESSING.put(channelId, now);
        return true;
    }

    static void endProcessing(String channelId) {
        PROCESSING.remove(channelId);
    }

    static boolean isTicketChannel(TextChannel channel) {
        if (channel == null) {
            return false;
        }
        String name = channel.getName();
        return name.startsWith("ticket-") || name.startsWith("closed-");
    }

    static boolean isOpenTicketChannel(TextChannel channel) {
        return isTicketChannel(channel) && !isClosedTicketChannel(channel, channel.getGuild());
    }

    static boolean isClosedTicketChannel(TextChannel channel, Guild guild) {
        if (!isTicketChannel(channel) || guild == null) {
            return false;
        }
        if (CLOSED.contains(channel.getId())) {
            return true;
        }
        if (inClosedCategory(channel, guild)) {
            return true;
        }
        String topic = channel.getTopic();
        return topic != null && topic.startsWith(CLOSED_TOPIC_PREFIX);
    }

    private static boolean inClosedCategory(TextChannel channel, Guild guild) {
        Category closedCategory = CommandListener.resolveClosedCategory(guild);
        if (closedCategory == null || channel.getParentCategory() == null) {
            return false;
        }
        return channel.getParentCategory().getId().equals(closedCategory.getId());
    }

    static String findOpenTicketId(Guild guild, String userId) {
        for (TextChannel channel : guild.getTextChannels()) {
            if (!isOpenTicketChannel(channel)) {
                continue;
            }
            String ownerId = extractOwnerId(channel.getTopic());
            if (userId.equals(ownerId)) {
                return channel.getId();
            }
        }
        return null;
    }

    static boolean canCloseTicket(TextChannel channel, Member member) {
        if (member == null || !isOpenTicketChannel(channel)) {
            return false;
        }
        if (CommandListener.canManageTickets(member)) {
            return true;
        }
        String ownerId = extractOwnerId(channel.getTopic());
        return ownerId != null && ownerId.equals(member.getId());
    }

    static void closeTicket(
            TextChannel channel,
            Guild guild,
            Member closedBy,
            ActionRow buttons,
            Consumer<String> onSuccess,
            Consumer<String> onFailure
    ) {
        TextChannel fresh = guild.getTextChannelById(channel.getId());
        if (fresh == null) {
            onFailure.accept("ไม่พบช่อง Ticket");
            return;
        }
        if (!isOpenTicketChannel(fresh)) {
            onFailure.accept("ช่องนี้ถูกปิดไปแล้ว");
            return;
        }
        if (!canCloseTicket(fresh, closedBy)) {
            onFailure.accept("คุณปิด Ticket นี้ไม่ได้");
            return;
        }

        Category closedCategory = CommandListener.resolveClosedCategory(guild);
        if (closedCategory == null) {
            onFailure.accept("หาหมวด closeticket ไม่เจอ");
            return;
        }

        CLOSED.add(fresh.getId());
        String ownerId = extractOwnerId(fresh.getTopic());
        String closedByMention = closedBy == null ? "unknown" : closedBy.getAsMention();
        String time = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneId.of("Asia/Bangkok"))
                .format(Instant.now());

        onSuccess.accept("ย้าย Ticket ไปหมวด **" + closedCategory.getName() + "** แล้ว");
        fresh.sendMessage(
                "🔒 Ticket ถูกปิดแล้ว\n"
                        + "ปิดโดย: " + closedByMention + "\n"
                        + "เวลา: " + time
        ).setComponents(buttons).queue();

        hideOwner(fresh, guild, ownerId);
        if (!inClosedCategory(fresh, guild)) {
            fresh.getManager().setParent(closedCategory).queue(
                    ok -> {},
                    err -> System.out.println("move to closeticket failed: " + err.getMessage())
            );
        }
    }

    static void reopenTicket(
            TextChannel channel,
            Guild guild,
            Member reopenedBy,
            ActionRow buttons,
            Consumer<String> onSuccess,
            Consumer<String> onFailure
    ) {
        TextChannel fresh = guild.getTextChannelById(channel.getId());
        if (fresh == null) {
            onFailure.accept("ไม่พบช่อง Ticket");
            return;
        }
        if (!isClosedTicketChannel(fresh, guild)) {
            onFailure.accept("เปิดได้เฉพาะ Ticket ที่ถูกปิดแล้ว");
            return;
        }
        if (!CommandListener.canManageTickets(reopenedBy)) {
            onFailure.accept("เฉพาะทีมงานเท่านั้นที่เปิด Ticket ได้");
            return;
        }

        String ownerId = extractOwnerId(fresh.getTopic());
        if (ownerId != null && !ownerId.isBlank()) {
            String existing = findOpenTicketId(guild, ownerId);
            if (existing != null && !existing.equals(fresh.getId())) {
                onFailure.accept("เจ้าของ Ticket มีห้องเปิดอยู่แล้ว: <#" + existing + ">");
                return;
            }
        }

        Category openCategory = CommandListener.resolveCategory(guild);
        if (openCategory == null) {
            onFailure.accept("หาหมวด ticket ไม่เจอ");
            return;
        }

        CLOSED.remove(fresh.getId());
        String reopenedByMention = reopenedBy == null ? "unknown" : reopenedBy.getAsMention();

        onSuccess.accept("ย้าย Ticket กลับไปหมวดเปิดแล้ว");
        fresh.sendMessage(
                "🔓 Ticket ถูกเปิดใหม่แล้ว\n"
                        + "เปิดโดย: " + reopenedByMention
        ).setComponents(buttons).queue();

        showOwner(fresh, guild, ownerId);
        if (fresh.getParentCategory() == null
                || !fresh.getParentCategory().getId().equals(openCategory.getId())) {
            fresh.getManager().setParent(openCategory).queue(
                    ok -> {},
                    err -> System.out.println("move to open ticket category failed: " + err.getMessage())
            );
        }
    }

    private static void hideOwner(TextChannel channel, Guild guild, String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return;
        }
        guild.retrieveMemberById(ownerId).queue(
                owner -> channel.upsertPermissionOverride(owner)
                        .deny(Permission.VIEW_CHANNEL)
                        .queue(ok -> {}, err -> {}),
                err -> {}
        );
    }

    private static void showOwner(TextChannel channel, Guild guild, String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return;
        }
        EnumSet<Permission> allow = CommandListener.ticketPermissions();
        guild.retrieveMemberById(ownerId).queue(
                owner -> channel.upsertPermissionOverride(owner)
                        .grant(allow)
                        .queue(ok -> {}, err -> {}),
                err -> {}
        );
    }

    static void deleteTicket(
            TextChannel channel,
            Guild guild,
            Member deletedBy,
            Runnable onSuccess,
            Consumer<String> onFailure
    ) {
        if (!isTicketChannel(channel)) {
            onFailure.accept("ใช้ได้เฉพาะในช่อง Ticket");
            return;
        }
        if (!CommandListener.canManageTickets(deletedBy)) {
            onFailure.accept("คุณไม่มีสิทธิ์ลบ Ticket");
            return;
        }

        CLOSED.remove(channel.getId());
        String deletedByName = deletedBy == null ? "unknown" : deletedBy.getEffectiveName();
        channel.delete().reason("Ticket deleted by " + deletedByName)
                .timeout(15, TimeUnit.SECONDS)
                .queue(ok -> onSuccess.run(), error -> onFailure.accept("ลบ Ticket ไม่สำเร็จ: " + error.getMessage()));
    }

    static String extractOwnerId(String topic) {
        if (topic == null || topic.isBlank()) {
            return null;
        }
        if (topic.startsWith(CLOSED_TOPIC_PREFIX)) {
            String ownerId = topic.substring(CLOSED_TOPIC_PREFIX.length());
            return ownerId.isBlank() || "unknown".equals(ownerId) ? null : ownerId;
        }
        return topic;
    }
}
