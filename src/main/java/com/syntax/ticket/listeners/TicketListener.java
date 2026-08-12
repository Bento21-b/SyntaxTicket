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
import java.util.concurrent.TimeUnit;

public class TicketListener extends ListenerAdapter {
    public static final String CLOSE_BUTTON_ID = "ticket-close-btn";

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

        String existing = findOpenTicket(guild, member.getId());
        if (existing != null) {
            event.reply("คุณมี Ticket เปิดอยู่แล้ว: <#" + existing + ">").setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue();

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

        action.queue(channel -> openTicketMessage(event, channel, member, supportRole, selected),
                error -> event.getHook().sendMessage("สร้าง Ticket ไม่สำเร็จ: " + error.getMessage()).queue());
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

    private String buildChannelName(Member member) {
        String cleaned = member.getUser().getName()
                .toLowerCase()
                .replaceAll("[^a-z0-9-]", "")
                .replaceAll("-{2,}", "-");

        String name = "ticket-" + cleaned;
        if (name.length() < 8) {
            name = "ticket-" + member.getId().substring(0, 6);
        }
        if (name.length() > 90) {
            name = name.substring(0, 90);
        }
        return name;
    }
}
