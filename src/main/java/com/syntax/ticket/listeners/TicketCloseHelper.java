package com.syntax.ticket.listeners;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class TicketCloseHelper {
    static final String CLOSED_TOPIC_PREFIX = "closed:";
    private static final long LOCK_MS = 20_000L;

    private static final ConcurrentHashMap<String, Long> PROCESSING = new ConcurrentHashMap<>();

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
        if (!isTicketChannel(channel)) {
            return false;
        }
        String topic = channel.getTopic();
        if (topic != null && topic.startsWith(CLOSED_TOPIC_PREFIX)) {
            return false;
        }
        return !inClosedCategory(channel, channel.getGuild());
    }

    static boolean isClosedTicketChannel(TextChannel channel, Guild guild) {
        if (!isTicketChannel(channel) || guild == null) {
            return false;
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
            Consumer<String> onSuccess,
            Consumer<String> onFailure
    ) {
        TextChannel fresh = guild.getTextChannelById(channel.getId());
        if (fresh == null) {
            onFailure.accept("ไม่พบช่อง Ticket");
            return;
        }
        closeFresh(fresh, guild, closedBy, onSuccess, onFailure);
    }

    private static void closeFresh(
            TextChannel fresh,
            Guild guild,
            Member closedBy,
            Consumer<String> onSuccess,
            Consumer<String> onFailure
    ) {
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
            onFailure.accept("ยังไม่ได้ตั้งค่า CLOSED_TICKET_CATEGORY_ID บน Railway");
            return;
        }

        String ownerId = extractOwnerId(fresh.getTopic());
        String closedTopic = CLOSED_TOPIC_PREFIX + (ownerId == null ? "unknown" : ownerId);
        String closedByMention = closedBy == null ? "unknown" : closedBy.getAsMention();
        String time = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneId.of("Asia/Bangkok"))
                .format(Instant.now());

        Runnable moveToClosed = () -> fresh.getManager()
                .setParent(closedCategory)
                .setTopic(closedTopic)
                .timeout(15, TimeUnit.SECONDS)
                .queue(
                        ok -> fresh.sendMessage(
                                "🔒 Ticket ถูกปิดแล้ว\n"
                                        + "ปิดโดย: " + closedByMention + "\n"
                                        + "เวลา: " + time
                        ).queue(
                                sent -> onSuccess.accept(
                                        "ย้าย Ticket ไปหมวด **" + closedCategory.getName() + "** แล้ว"
                                ),
                                err -> onSuccess.accept("ย้าย Ticket แล้ว แต่ส่งข้อความปิดไม่สำเร็จ")
                        ),
                        error -> onFailure.accept("ปิด Ticket ไม่สำเร็จ: " + error.getMessage())
                );

        if (ownerId == null || ownerId.isBlank()) {
            moveToClosed.run();
            return;
        }

        guild.retrieveMemberById(ownerId).timeout(10, TimeUnit.SECONDS).queue(
                owner -> fresh.upsertPermissionOverride(owner)
                        .deny(Permission.VIEW_CHANNEL)
                        .timeout(10, TimeUnit.SECONDS)
                        .queue(ok -> moveToClosed.run(), err -> moveToClosed.run()),
                err -> moveToClosed.run()
        );
    }

    static void reopenTicket(
            TextChannel channel,
            Guild guild,
            Member reopenedBy,
            Consumer<String> onSuccess,
            Consumer<String> onFailure
    ) {
        TextChannel fresh = guild.getTextChannelById(channel.getId());
        if (fresh == null) {
            onFailure.accept("ไม่พบช่อง Ticket");
            return;
        }
        reopenFresh(fresh, guild, reopenedBy, onSuccess, onFailure);
    }

    private static void reopenFresh(
            TextChannel fresh,
            Guild guild,
            Member reopenedBy,
            Consumer<String> onSuccess,
            Consumer<String> onFailure
    ) {
        if (!isClosedTicketChannel(fresh, guild)) {
            onFailure.accept("เปิดได้เฉพาะ Ticket ในหมวด closeticket");
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
            onFailure.accept("ยังไม่ได้ตั้งค่า TICKET_CATEGORY_ID บน Railway");
            return;
        }

        String reopenedByMention = reopenedBy == null ? "unknown" : reopenedBy.getAsMention();
        EnumSet<Permission> allow = CommandListener.ticketPermissions();

        Runnable moveToOpen = () -> fresh.getManager()
                .setParent(openCategory)
                .setTopic(ownerId)
                .timeout(15, TimeUnit.SECONDS)
                .queue(
                        ok -> fresh.sendMessage(
                                "🔓 Ticket ถูกเปิดใหม่แล้ว\n"
                                        + "เปิดโดย: " + reopenedByMention
                        ).queue(
                                sent -> onSuccess.accept("ย้าย Ticket กลับไปหมวดเปิดแล้ว"),
                                err -> onSuccess.accept("เปิด Ticket แล้ว แต่ส่งข้อความไม่สำเร็จ")
                        ),
                        error -> onFailure.accept("เปิด Ticket ไม่สำเร็จ: " + error.getMessage())
                );

        if (ownerId == null || ownerId.isBlank()) {
            moveToOpen.run();
            return;
        }

        guild.retrieveMemberById(ownerId).timeout(10, TimeUnit.SECONDS).queue(
                owner -> fresh.upsertPermissionOverride(owner)
                        .grant(allow)
                        .timeout(10, TimeUnit.SECONDS)
                        .queue(ok -> moveToOpen.run(), err -> moveToOpen.run()),
                err -> moveToOpen.run()
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
