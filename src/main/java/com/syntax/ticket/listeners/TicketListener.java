package com.syntax.ticket.listeners;

import com.syntax.ticket.config.BotConfig;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class TicketListener extends ListenerAdapter {
    public static final String CLOSE_BUTTON_ID = "ticket-close-btn";

    /** กันสร้างซ้ำตอนกดเร็ว / event มาซ้อน */
    private static final Set<String> OPENING = ConcurrentHashMap.newKeySet();

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!CommandListener.SELECT_MENU_ID.equals(event.getComponentId())) {
            return;
        }

        List<String> values = event.getValues();
        if (values.isEmpty()) {
            event.reply("หัวข้อไม่ถูกต้อง").setEphemeral(true).queue();
            return;
        }

        BotConfig.TicketOption selected = BotConfig.findOption(values.get(0));
        if (selected == null) {
            event.reply("หัวข้อไม่ถูกต้อง หรือบอทยังไม่ได้รีสตาร์ทหลังเพิ่มตัวเลือก").setEphemeral(true).queue();
            return;
        }

        Guild guild = event.getGuild();
        Member member = event.getMember();
        if (guild == null || member == null) {
            event.reply("ใช้ได้เฉพาะในเซิร์ฟเวอร์").setEphemeral(true).queue();
            return;
        }

        String lockKey = guild.getId() + ":" + member.getId();
        String existing = findOpenTicket(guild, member.getId());
        if (existing != null) {
            event.reply("คุณมี Ticket เปิดอยู่แล้ว: <#" + existing + ">").setEphemeral(true).queue();
            return;
        }

        if (!OPENING.add(lockKey)) {
            event.reply("กำลังสร้าง Ticket ให้อยู่แล้ว กรุณารอสักครู่").setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue(
                success -> createTicketChannel(event, guild, member, selected, lockKey),
                error -> {
                    OPENING.remove(lockKey);
                    System.out.println("ticket open skipped (another instance handled it): " + error.getMessage());
                }
        );
    }

    private void createTicketChannel(
            StringSelectInteractionEvent event,
            Guild guild,
            Member member,
            BotConfig.TicketOption selected,
            String lockKey
    ) {
        // เช็กอีกครั้งหลังได้สิทธิ์ตอบ interaction
        String existingAfter = findOpenTicket(guild, member.getId());
        if (existingAfter != null) {
            OPENING.remove(lockKey);
            event.getHook().sendMessage("คุณมี Ticket เปิดอยู่แล้ว: <#" + existingAfter + ">").queue();
            return;
        }

        String channelName = buildChannelName(member);
        Category category = CommandListener.resolveCategory(guild);
        Role supportRole = CommandListener.resolveSupportRole(guild);
        EnumSet<Permission> allow = CommandListener.ticketPermissions();
        EnumSet<Permission> denyNone = EnumSet.noneOf(Permission.class);

        ChannelAction<TextChannel> action = guild.createTextChannel(channelName)
                .setTopic(member.getId())
                .addPermissionOverride(guild.getPublicRole(), denyNone, EnumSet.of(Permission.VIEW_CHANNEL))
                .addPermissionOverride(member, allow, denyNone);

        if (category != null) {
            action.setParent(category);
        }
        if (supportRole != null) {
            action.addPermissionOverride(supportRole, allow, denyNone);
        }

        action.queue(
                channel -> {
                    OPENING.remove(lockKey);
                    openTicketMessage(event, channel, member, supportRole, selected);
                },
                error -> {
                    OPENING.remove(lockKey);
                    event.getHook().sendMessage("สร้าง Ticket ไม่สำเร็จ: " + error.getMessage()).queue();
                }
        );
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (!CLOSE_BUTTON_ID.equals(event.getComponentId())) {
            return;
        }

        if (!(event.getChannel() instanceof TextChannel channel) || !channel.getName().startsWith("ticket-")) {
            event.reply("ใช้ได้เฉพาะในช่อง Ticket").setEphemeral(true).queue();
            return;
        }

        Member member = event.getMember();
        boolean isStaff = CommandListener.canManageTickets(member);
        boolean isOwner = channel.getTopic() != null
                && member != null
                && channel.getTopic().equals(member.getId());

        if (!isStaff && !isOwner) {
            event.reply("คุณปิด Ticket นี้ไม่ได้").setEphemeral(true).queue();
            return;
        }

        event.reply("กำลังปิด Ticket ใน 3 วินาที...").queue(hook ->
                channel.delete()
                        .reason("Ticket closed by " + event.getUser().getName())
                        .queueAfter(3, TimeUnit.SECONDS)
        );
    }

    private void openTicketMessage(
            StringSelectInteractionEvent event,
            TextChannel channel,
            Member member,
            Role supportRole,
            BotConfig.TicketOption selected
    ) {
        EmbedBuilder welcome = BotConfig.welcomeEmbed(member.getAsMention(), selected.label());

        String mention = supportRole != null
                ? supportRole.getAsMention() + " " + member.getAsMention()
                : member.getAsMention();

        channel.sendMessage(mention)
                .addEmbeds(welcome.build())
                .setActionRow(Button.danger(CLOSE_BUTTON_ID, "ปิด Ticket"))
                .queue();

        event.getHook().sendMessage(
                "เปิด Ticket **" + selected.label() + "** แล้ว: " + channel.getAsMention()
        ).queue();
    }

    private String findOpenTicket(Guild guild, String userId) {
        for (TextChannel channel : guild.getTextChannels()) {
            if (channel.getName().startsWith("ticket-") && userId.equals(channel.getTopic())) {
                return channel.getId();
            }
        }
        return null;
    }

    /** ชื่อห้องไม่ซ้ำ: ticket-{ชื่อ}-xxxxxx (ท้ายจาก User ID) */
    private String buildChannelName(Member member) {
        String cleaned = member.getUser().getName()
                .toLowerCase()
                .replaceAll("[^a-z0-9]", "")
                .replaceAll("-{2,}", "-");
        if (cleaned.length() > 20) {
            cleaned = cleaned.substring(0, 20);
        }
        if (cleaned.isBlank()) {
            cleaned = "user";
        }

        String suffix = member.getId();
        if (suffix.length() > 6) {
            suffix = suffix.substring(suffix.length() - 6);
        }

        String name = "ticket-" + cleaned + "-" + suffix;
        if (name.length() > 90) {
            name = name.substring(0, 90);
        }
        return name;
    }
}
