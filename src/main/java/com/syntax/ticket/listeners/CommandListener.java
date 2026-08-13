package com.syntax.ticket.listeners;

import com.syntax.ticket.config.BotConfig;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.components.ActionComponent;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.utils.FileUpload;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public class CommandListener extends ListenerAdapter {
    public static final String SELECT_MENU_ID = "ticket-topic";

    public static StringSelectMenu buildSelectMenu() {
        StringSelectMenu.Builder menu = StringSelectMenu.create(SELECT_MENU_ID)
                .setPlaceholder(BotConfig.selectPlaceholder());
        for (BotConfig.TicketOption option : BotConfig.selectOptions()) {
            if (option.description() == null || option.description().isBlank()) {
                menu.addOption(option.label(), option.value());
            } else {
                menu.addOption(option.label(), option.value(), option.description());
            }
        }
        return menu.build();
    }

    @Override
    public void onReady(ReadyEvent event) {
        event.getJDA().updateCommands()
                .addCommands(
                        Commands.slash("ticket-setup", "โพสต์แผงเปิด Ticket ในช่องนี้ (ลบแผงเก่าออกก่อน)")
                                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_CHANNEL)),
                        Commands.slash("ticket-close", "ปิด Ticket ในช่องนี้")
                )
                .queue();
        System.out.println("Slash commands registered. Bot user: " + event.getJDA().getSelfUser().getAsTag());
        event.getJDA().getGuilds().forEach(guild -> {
            Category open = resolveCategory(guild);
            Category closed = resolveClosedCategory(guild);
            System.out.println("Guild " + guild.getName()
                    + " openCategory=" + (open == null ? "NOT FOUND" : open.getName() + ":" + open.getId())
                    + " closedCategory=" + (closed == null ? "NOT FOUND" : closed.getName() + ":" + closed.getId()));
        });
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "ticket-setup" -> handleSetup(event);
            case "ticket-close" -> handleClose(event);
            default -> event.reply("คำสั่งไม่รู้จัก").setEphemeral(true).queue();
        }
    }

    private void handleSetup(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) {
            event.reply("ใช้คำสั่งนี้ในเซิร์ฟเวอร์เท่านั้น").setEphemeral(true).queue();
            return;
        }

        MessageChannel channel = event.getChannel();
        if (channel == null) {
            event.reply("ใช้คำสั่งนี้ในช่องข้อความเท่านั้น").setEphemeral(true).queue();
            return;
        }

        if (!canManageTickets(event.getMember())) {
            event.reply("คุณไม่มีสิทธิ์ตั้งค่า Ticket").setEphemeral(true).queue();
            return;
        }

        List<BotConfig.TicketOption> options = BotConfig.selectOptions();
        if (options.isEmpty()) {
            event.reply("ยังไม่ได้ตั้งค่าตัวเลือก dropdown").setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue(
                success -> postFreshPanel(event, channel, options),
                error -> System.out.println("ticket-setup skipped (another instance handled it): " + error.getMessage())
        );
    }

    private void postFreshPanel(
            SlashCommandInteractionEvent event,
            MessageChannel channel,
            List<BotConfig.TicketOption> options
    ) {
        EmbedBuilder embed = BotConfig.panelEmbed();
        StringSelectMenu menu = buildSelectMenu();

        channel.getIterableHistory()
                .takeAsync(50)
                .thenAccept(messages -> {
                    List<Message> oldPanels = new ArrayList<>();
                    for (Message message : messages) {
                        if (message.getAuthor().getIdLong() == event.getJDA().getSelfUser().getIdLong()
                                && isTicketPanel(message)) {
                            oldPanels.add(message);
                        }
                    }

                    Runnable postPanel = () -> sendPanel(channel, embed, menu)
                            .queue(
                                    sent -> {
                                        event.getHook().sendMessage(
                                                "โพสต์แผง Ticket แล้ว (" + options.size() + " หัวข้อ)"
                                                        + (oldPanels.isEmpty() ? "" : " · ลบแผงเก่า " + oldPanels.size() + " อัน")
                                        ).queue();
                                        dedupePanelsKeepNewest(channel, event.getJDA().getSelfUser().getIdLong());
                                    },
                                    error -> event.getHook().sendMessage(
                                            "โพสต์แผงไม่สำเร็จ: " + error.getMessage()
                                    ).queue()
                            );

                    if (oldPanels.isEmpty()) {
                        postPanel.run();
                        return;
                    }

                    @SuppressWarnings("unchecked")
                    java.util.concurrent.CompletableFuture<Void>[] futures = channel.purgeMessages(oldPanels)
                            .toArray(new java.util.concurrent.CompletableFuture[0]);
                    java.util.concurrent.CompletableFuture.allOf(futures)
                            .whenComplete((ok, err) -> postPanel.run());
                })
                .exceptionally(err -> {
                    sendPanel(channel, embed, menu)
                            .queue(sent -> dedupePanelsKeepNewest(channel, event.getJDA().getSelfUser().getIdLong()));
                    event.getHook().sendMessage(
                            "โพสต์แผง Ticket แล้ว (ลบแผงเก่าไม่สำเร็จ: " + err.getMessage() + ")"
                    ).queue();
                    return null;
                });
    }

    /** เหลือแผงล่าสุดอันเดียว ลบอันเก่าที่ซ้อน */
    private void dedupePanelsKeepNewest(MessageChannel channel, long botUserId) {
        channel.getJDA().getGatewayPool().schedule(() -> {
            try {
                List<Message> recent = channel.getHistory().retrievePast(20).complete();
                List<Message> panels = new ArrayList<>();
                for (Message message : recent) {
                    if (message.getAuthor().getIdLong() == botUserId && isTicketPanel(message)) {
                        panels.add(message);
                    }
                }
                if (panels.size() <= 1) {
                    return;
                }
                // history = ใหม่สุดก่อน → เก็บ index 0 ลบที่เหลือ
                List<Message> duplicates = panels.subList(1, panels.size());
                channel.purgeMessages(duplicates);
            } catch (Exception e) {
                System.out.println("dedupe panels failed: " + e.getMessage());
            }
        }, 2, java.util.concurrent.TimeUnit.SECONDS);
    }

    private boolean isTicketPanel(Message message) {
        for (ActionRow row : message.getActionRows()) {
            for (ActionComponent component : row.getActionComponents()) {
                if (SELECT_MENU_ID.equals(component.getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    private net.dv8tion.jda.api.requests.restaction.MessageCreateAction sendPanel(
            MessageChannel channel,
            EmbedBuilder embed,
            StringSelectMenu menu
    ) {
        var action = channel.sendMessageEmbeds(embed.build()).setActionRow(menu);
        FileUpload image = BotConfig.panelImageUpload();
        if (image != null) {
            action = action.setFiles(image);
        }
        return action;
    }

    private void handleClose(SlashCommandInteractionEvent event) {
        if (!(event.getChannel() instanceof TextChannel channel)) {
            event.reply("ใช้ในช่อง Ticket เท่านั้น").setEphemeral(true).queue();
            return;
        }

        if (!TicketCloseHelper.isOpenTicketChannel(channel)) {
            event.reply("ช่องนี้ไม่ใช่ Ticket ที่เปิดอยู่").setEphemeral(true).queue();
            return;
        }

        Member member = event.getMember();
        if (!TicketCloseHelper.canCloseTicket(channel, member)) {
            event.reply("คุณปิด Ticket นี้ไม่ได้").setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue();
        TicketCloseHelper.closeTicket(
                channel,
                event.getGuild(),
                member,
                ActionRow.of(TicketListener.ticketActionButtons()),
                message -> event.getHook().sendMessage(message).queue(),
                error -> event.getHook().sendMessage(error).queue()
        );
    }

    static boolean canManageTickets(Member member) {
        if (member == null) {
            return false;
        }
        if (member.hasPermission(Permission.ADMINISTRATOR) || member.hasPermission(Permission.MANAGE_CHANNEL)) {
            return true;
        }
        String supportRoleId = BotConfig.supportRoleId();
        if (supportRoleId.isBlank()) {
            return false;
        }
        return member.getRoles().stream().anyMatch(role -> role.getId().equals(supportRoleId));
    }

    static Category resolveCategory(Guild guild) {
        Category closed = resolveClosedCategory(guild);
        String categoryId = BotConfig.ticketCategoryId();
        if (!categoryId.isBlank()) {
            Category byId = guild.getCategoryById(categoryId);
            if (byId != null) {
                return byId;
            }
        }
        Category exact = null;
        Category fuzzy = null;
        for (Category category : guild.getCategories()) {
            if (closed != null && category.getId().equals(closed.getId())) {
                continue;
            }
            String normalized = normalizeCategoryName(category.getName());
            if (normalized.equals("ticket")) {
                exact = category;
                break;
            }
            if (fuzzy == null && normalized.contains("ticket") && !normalized.contains("close")) {
                fuzzy = category;
            }
        }
        return exact != null ? exact : fuzzy;
    }

    static Category resolveClosedCategory(Guild guild) {
        String categoryId = BotConfig.closedTicketCategoryId();
        if (!categoryId.isBlank()) {
            Category byId = guild.getCategoryById(categoryId);
            if (byId != null) {
                return byId;
            }
        }
        for (Category category : guild.getCategories()) {
            String normalized = normalizeCategoryName(category.getName());
            if (normalized.equals("closeticket")
                    || normalized.equals("closedticket")
                    || normalized.contains("closeticket")
                    || normalized.contains("closedticket")) {
                return category;
            }
        }
        return null;
    }

    private static String normalizeCategoryName(String name) {
        if (name == null) {
            return "";
        }
        return name.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    static Role resolveSupportRole(Guild guild) {
        String roleId = BotConfig.supportRoleId();
        if (roleId.isBlank()) {
            return null;
        }
        return guild.getRoleById(roleId);
    }

    static EnumSet<Permission> ticketPermissions() {
        return EnumSet.of(
                Permission.VIEW_CHANNEL,
                Permission.MESSAGE_SEND,
                Permission.MESSAGE_HISTORY,
                Permission.MESSAGE_ATTACH_FILES,
                Permission.MESSAGE_EMBED_LINKS
        );
    }
}
