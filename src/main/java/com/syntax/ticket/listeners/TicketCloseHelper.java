package com.syntax.ticket.listeners;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

final class TicketCloseHelper {
    static final String CLOSED_TOPIC_PREFIX = "closed:";

    private TicketCloseHelper() {}

    static boolean isOpenTicketChannel(TextChannel channel) {
        if (channel == null) {
            return false;
        }
        String name = channel.getName();
        if (name.startsWith("closed-")) {
            return false;
        }
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
            if (userId.equals(channel.getTopic())) {
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
        if (!isOpenTicketChannel(channel)) {
            onFailure.accept("ช่องนี้ถูกปิดไปแล้ว");
            return;
        }

        Category closedCategory = CommandListener.resolveClosedCategory(guild);
        if (closedCategory == null) {
            onFailure.accept("ยังไม่ได้ตั้งค่า CLOSED_TICKET_CATEGORY_ID บน Railway");
            return;
        }

        String ownerId = channel.getTopic();
        String closedName = toClosedName(channel.getName());
        String closedTopic = CLOSED_TOPIC_PREFIX + (ownerId == null ? "unknown" : ownerId);
        String closedByName = closedBy == null ? "unknown" : closedBy.getEffectiveName();
        String time = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneId.of("Asia/Bangkok"))
                .format(Instant.now());

        var manager = channel.getManager()
                .setParent(closedCategory)
                .setName(closedName)
                .setTopic(closedTopic);

        if (ownerId != null && !ownerId.isBlank()) {
            Member owner = guild.getMemberById(ownerId);
            if (owner != null) {
                manager = manager.putPermissionOverride(
                        owner,
                        0L,
                        Permission.VIEW_CHANNEL.getRawValue()
                );
            }
        }

        manager.queue(
                ok -> channel.sendMessage(
                        "🔒 Ticket ถูกปิดแล้ว\n"
                                + "ปิดโดย: **" + closedByName + "**\n"
                                + "เวลา: " + time
                ).queue(
                        sent -> onSuccess.accept("ย้าย Ticket ไปหมวด **" + closedCategory.getName() + "** แล้ว"),
                        err -> onSuccess.accept("ย้าย Ticket แล้ว แต่ส่งข้อความปิดไม่สำเร็จ")
                ),
                error -> onFailure.accept("ปิด Ticket ไม่สำเร็จ: " + error.getMessage())
        );
    }

    static boolean canCloseTicket(TextChannel channel, Member member) {
        if (member == null || !isOpenTicketChannel(channel)) {
            return false;
        }
        if (CommandListener.canManageTickets(member)) {
            return true;
        }
        String topic = channel.getTopic();
        return topic != null && topic.equals(member.getId());
    }

    private static String toClosedName(String currentName) {
        if (currentName.startsWith("closed-")) {
            return currentName.length() > 100 ? currentName.substring(0, 100) : currentName;
        }
        String name = "closed-" + currentName;
        if (name.length() > 100) {
            name = name.substring(0, 100);
        }
        return name;
    }
}
