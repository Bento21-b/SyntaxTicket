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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

final class TicketCloseHelper {
    static final String CLOSED_TOPIC_PREFIX = "closed:";

    private static final Set<String> PROCESSING = ConcurrentHashMap.newKeySet();

    private TicketCloseHelper() {}

    static boolean beginProcessing(String channelId) {
        return PROCESSING.add(channelId);
    }

    static void endProcessing(String channelId) {
        PROCESSING.remove(channelId);
    }

    static boolean isOpenTicketChannel(TextChannel channel) {
        if (channel == null) {
            return false;
        }
        if (isClosedTicketChannel(channel, channel.getGuild())) {
            return false;
        }
        String name = channel.getName();
        if (!name.startsWith("ticket-")) {
            return false;
        }
        String topic = channel.getTopic();
        return topic == null || !topic.startsWith(CLOSED_TOPIC_PREFIX);
    }

    static String findOpenTicketId(Guild guild, String userId) {
        Category closedCategory = CommandListener.resolveClosedCategory(guild);
        for (TextChannel channel : guild.getTextChannels()) {
            if (!isOpenTicketChannel(channel)) {
                continue;
            }
            if (closedCategory != null
                    && channel.getParentCategory() != null
                    && channel.getParentCategory().getId().equals(closedCategory.getId())) {
                continue;
            }
            String ownerId = extractOwnerId(channel.getTopic());
            if (userId.equals(ownerId)) {
                return channel.getId();
            }
        }
        return null;
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

        if (!isOpenTicketChannel(fresh)) {
            onFailure.accept("ช่องนี้ถูกปิดไปแล้ว");
            return;
        }

        Category closedCategory = CommandListener.resolveClosedCategory(guild);
        if (closedCategory == null) {
            onFailure.accept("ยังไม่ได้ตั้งค่า CLOSED_TICKET_CATEGORY_ID บน Railway");
            return;
        }

        String ownerId = extractOwnerId(fresh.getTopic());
        String closedName = toClosedName(fresh.getName());
        String closedTopic = CLOSED_TOPIC_PREFIX + (ownerId == null ? "unknown" : ownerId);
        String closedByMention = closedBy == null ? "unknown" : closedBy.getAsMention();
        String time = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneId.of("Asia/Bangkok"))
                .format(Instant.now());

        Runnable moveToClosed = () -> fresh.getManager()
                .setParent(closedCategory)
                .setName(closedName)
                .setTopic(closedTopic)
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

        guild.retrieveMemberById(ownerId).queue(
                owner -> fresh.upsertPermissionOverride(owner)
                        .deny(Permission.VIEW_CHANNEL)
                        .queue(
                                ok -> moveToClosed.run(),
                                err -> moveToClosed.run()
                        ),
                err -> moveToClosed.run()
        );
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

    static boolean isClosedTicketChannel(TextChannel channel, Guild guild) {
        if (channel == null || guild == null) {
            return false;
        }
        Category closedCategory = CommandListener.resolveClosedCategory(guild);
        if (closedCategory == null) {
            return false;
        }
        if (channel.getParentCategory() == null
                || !channel.getParentCategory().getId().equals(closedCategory.getId())) {
            return false;
        }
        String topic = channel.getTopic();
        return channel.getName().startsWith("closed-")
                || (topic != null && topic.startsWith(CLOSED_TOPIC_PREFIX));
    }

    static void deleteTicket(
            TextChannel channel,
            Guild guild,
            Member deletedBy,
            Runnable onSuccess,
            Consumer<String> onFailure
    ) {
        if (!isClosedTicketChannel(channel, guild)) {
            onFailure.accept("ลบได้เฉพาะ Ticket ในหมวด closeticket");
            return;
        }
        if (!CommandListener.canManageTickets(deletedBy)) {
            onFailure.accept("คุณไม่มีสิทธิ์ลบ Ticket");
            return;
        }

        String deletedByName = deletedBy == null ? "unknown" : deletedBy.getEffectiveName();
        channel.delete().reason("Ticket deleted by " + deletedByName).queue(
                ok -> onSuccess.run(),
                error -> onFailure.accept("ลบ Ticket ไม่สำเร็จ: " + error.getMessage())
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
        String openName = toOpenName(fresh.getName());
        String reopenedByMention = reopenedBy == null ? "unknown" : reopenedBy.getAsMention();
        EnumSet<Permission> allow = CommandListener.ticketPermissions();

        Runnable moveToOpen = () -> {
            var manager = fresh.getManager()
                    .setName(openName)
                    .setTopic(ownerId);
            if (openCategory != null) {
                manager = manager.setParent(openCategory);
            }
            manager.queue(
                    ok -> fresh.sendMessage(
                            "🔓 Ticket ถูกเปิดใหม่แล้ว\n"
                                    + "เปิดโดย: " + reopenedByMention
                    ).queue(
                            sent -> onSuccess.accept("ย้าย Ticket กลับไปหมวดเปิดแล้ว"),
                            err -> onSuccess.accept("เปิด Ticket แล้ว แต่ส่งข้อความไม่สำเร็จ")
                    ),
                    error -> onFailure.accept("เปิด Ticket ไม่สำเร็จ: " + error.getMessage())
            );
        };

        if (ownerId == null || ownerId.isBlank()) {
            moveToOpen.run();
            return;
        }

        guild.retrieveMemberById(ownerId).queue(
                owner -> fresh.upsertPermissionOverride(owner)
                        .grant(allow)
                        .clear(Permission.VIEW_CHANNEL)
                        .queue(
                                ok -> moveToOpen.run(),
                                err -> moveToOpen.run()
                        ),
                err -> moveToOpen.run()
        );
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

    private static String toOpenName(String currentName) {
        String name = currentName;
        while (name.startsWith("closed-")) {
            name = name.substring("closed-".length());
        }
        if (name.length() > 100) {
            name = name.substring(0, 100);
        }
        return name;
    }

    private static String toClosedName(String currentName) {
        String openName = toOpenName(currentName);
        String name = "closed-" + openName;
        if (name.length() > 100) {
            name = name.substring(0, 100);
        }
        return name;
    }
}
