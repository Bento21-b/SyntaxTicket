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
import java.util.EnumSet;
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
        String closedByMention = closedBy == null ? "unknown" : closedBy.getAsMention();
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
                                + "ปิดโดย: " + closedByMention + "\n"
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
        if (!isClosedTicketChannel(channel, guild)) {
            onFailure.accept("เปิดได้เฉพาะ Ticket ในหมวด closeticket");
            return;
        }
        if (!CommandListener.canManageTickets(reopenedBy)) {
            onFailure.accept("เฉพาะทีมงานเท่านั้นที่เปิด Ticket ได้");
            return;
        }

        String ownerId = extractOwnerId(channel.getTopic());
        if (ownerId != null && !ownerId.isBlank()) {
            String existing = findOpenTicketId(guild, ownerId);
            if (existing != null) {
                onFailure.accept("เจ้าของ Ticket มีห้องเปิดอยู่แล้ว: <#" + existing + ">");
                return;
            }
        }

        Category openCategory = CommandListener.resolveCategory(guild);
        String openName = toOpenName(channel.getName());
        String reopenedByMention = reopenedBy == null ? "unknown" : reopenedBy.getAsMention();

        var manager = channel.getManager()
                .setName(openName)
                .setTopic(ownerId);

        if (openCategory != null) {
            manager = manager.setParent(openCategory);
        }

        if (ownerId != null && !ownerId.isBlank()) {
            Member owner = guild.getMemberById(ownerId);
            if (owner != null) {
                EnumSet<Permission> allow = CommandListener.ticketPermissions();
                manager = manager.putPermissionOverride(owner, allow, EnumSet.noneOf(Permission.class));
            }
        }

        manager.queue(
                ok -> channel.sendMessage(
                        "🔓 Ticket ถูกเปิดใหม่แล้ว\n"
                                + "เปิดโดย: " + reopenedByMention
                ).queue(
                        sent -> onSuccess.accept("ย้าย Ticket กลับไปหมวดเปิดแล้ว"),
                        err -> onSuccess.accept("เปิด Ticket แล้ว แต่ส่งข้อความไม่สำเร็จ")
                ),
                error -> onFailure.accept("เปิด Ticket ไม่สำเร็จ: " + error.getMessage())
        );
    }

    private static String extractOwnerId(String topic) {
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
        if (currentName.startsWith("closed-")) {
            String name = currentName.substring("closed-".length());
            return name.length() > 100 ? name.substring(0, 100) : name;
        }
        return currentName.length() > 100 ? currentName.substring(0, 100) : currentName;
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
