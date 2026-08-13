package com.syntax.ticket.config;

import com.syntax.ticket.Bot;
import net.dv8tion.jda.api.EmbedBuilder;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BotConfig {
    private BotConfig() {}

    public record TicketOption(String value, String label, String description) {}

    public static String supportRoleId() {
        return Bot.optionalEnv("SUPPORT_ROLE_ID", "");
    }

    public static String ticketCategoryId() {
        return Bot.optionalEnv("TICKET_CATEGORY_ID", "");
    }

    public static String closedTicketCategoryId() {
        return Bot.optionalEnv("CLOSED_TICKET_CATEGORY_ID", "");
    }

    public static String panelTitle() {
        return unescape(Bot.optionalEnv("PANEL_TITLE", "→ ticket for order 🪄✨"));
    }

    public static String panelDescription() {
        return unescape(Bot.optionalEnv(
                "PANEL_DESCRIPTION",
                "กดปุ่มด้านล่าง\n. สนใจเซ็ตติ้ง จิ้มเลย"
        ));
    }

    public static String panelFooter() {
        return unescape(Bot.optionalEnv("PANEL_FOOTER", "Powered by Syntax"));
    }

    public static String panelImageUrl() {
        return Bot.optionalEnv("PANEL_IMAGE_URL", "");
    }

    public static String panelThumbnailUrl() {
        return Bot.optionalEnv("PANEL_THUMBNAIL_URL", "");
    }

    public static String panelFooterIconUrl() {
        return Bot.optionalEnv("PANEL_FOOTER_ICON_URL", "");
    }

    public static String panelAuthorName() {
        return unescape(Bot.optionalEnv("PANEL_AUTHOR_NAME", ""));
    }

    public static String panelAuthorIconUrl() {
        return Bot.optionalEnv("PANEL_AUTHOR_ICON_URL", "");
    }

    public static Color panelColor() {
        return parseColor(Bot.optionalEnv("PANEL_COLOR", "2B2D31"), new Color(0x2B2D31));
    }

    public static String selectPlaceholder() {
        return unescape(Bot.optionalEnv("PANEL_SELECT_PLACEHOLDER", "Select a topic..."));
    }

    /**
     * อ่านตัวเลือก dropdown จาก env
     * รองรับ:
     *   PANEL_SELECT_LABEL / PANEL_SELECT_DESCRIPTION   (ตัวเลือกที่ 1 แบบเดิม)
     *   PANEL_SELECT_LABEL_2 / PANEL_SELECT_DESCRIPTION_2
     *   PANEL_SELECT_LABEL_3 / ...
     * สูงสุด 25 ตัวเลือก (ขีดจำกัด Discord)
     */
    public static List<TicketOption> selectOptions() {
        List<TicketOption> options = new ArrayList<>();

        // ตัวเลือกที่ 1: รองรับทั้งแบบมีเลขและไม่มีเลข
        String label1 = firstNonBlank(
                Bot.optionalEnv("PANEL_SELECT_LABEL_1", ""),
                Bot.optionalEnv("PANEL_SELECT_LABEL", "Setting")
        );
        String desc1 = firstNonBlank(
                Bot.optionalEnv("PANEL_SELECT_DESCRIPTION_1", ""),
                Bot.optionalEnv("PANEL_SELECT_DESCRIPTION", "เปิด Ticket สำหรับเซ็ตติ้ง")
        );
        String value1 = firstNonBlank(
                Bot.optionalEnv("PANEL_SELECT_VALUE_1", ""),
                Bot.optionalEnv("PANEL_SELECT_VALUE", "setting")
        );
        options.add(new TicketOption(sanitizeValue(value1, "option-1"), label1, desc1));

        addOption(options, 2, "Reset", "เปิด Ticket สำหรับรีเซต", "reset");
        addOption(options, 3, "สอบถามทั่วไป", "เปิด Ticket เพื่อสอบถาม", "general");

        for (int i = 4; i <= 25; i++) {
            String label = Bot.optionalEnv("PANEL_SELECT_LABEL_" + i, "");
            if (label.isBlank()) {
                break;
            }
            String desc = Bot.optionalEnv("PANEL_SELECT_DESCRIPTION_" + i, "");
            String value = Bot.optionalEnv("PANEL_SELECT_VALUE_" + i, "option-" + i);
            options.add(new TicketOption(sanitizeValue(value, "option-" + i), label, desc));
        }

        return options;
    }

    private static void addOption(
            List<TicketOption> options,
            int index,
            String defaultLabel,
            String defaultDescription,
            String defaultValue
    ) {
        String label = Bot.optionalEnv("PANEL_SELECT_LABEL_" + index, defaultLabel);
        if (label.isBlank()) {
            return;
        }
        String desc = Bot.optionalEnv("PANEL_SELECT_DESCRIPTION_" + index, defaultDescription);
        String value = Bot.optionalEnv("PANEL_SELECT_VALUE_" + index, defaultValue);
        options.add(new TicketOption(sanitizeValue(value, "option-" + index), label, desc));
    }

    public static TicketOption findOption(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (TicketOption option : selectOptions()) {
            if (option.value().equals(value)) {
                return option;
            }
        }
        return null;
    }

    public static String welcomeTitle() {
        return unescape(Bot.optionalEnv("WELCOME_TITLE", "Ticket"));
    }

    public static String welcomeDescription() {
        return unescape(Bot.optionalEnv(
                "WELCOME_DESCRIPTION",
                "สวัสดี {user}\nทีมงานจะเข้ามาตอบเร็วๆ นี้\n\n**หัวข้อ:** {topic}\nพิมพ์รายละเอียดที่ต้องการได้เลย"
        ));
    }

    public static String welcomeFooter() {
        return unescape(Bot.optionalEnv("WELCOME_FOOTER", "Syntax Ticket"));
    }

    public static String welcomeImageUrl() {
        return Bot.optionalEnv("WELCOME_IMAGE_URL", "");
    }

    public static Color welcomeColor() {
        return parseColor(Bot.optionalEnv("WELCOME_COLOR", "5865F2"), new Color(0x5865F2));
    }

    public static EmbedBuilder panelEmbed() {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(panelTitle())
                .setDescription(panelDescription())
                .setColor(panelColor());

        applyUrl(embed::setImage, panelImageUrl());
        applyUrl(embed::setThumbnail, panelThumbnailUrl());

        String author = panelAuthorName();
        if (!author.isBlank()) {
            String icon = panelAuthorIconUrl();
            embed.setAuthor(author, icon.isBlank() ? null : icon);
        }

        String footer = panelFooter();
        if (!footer.isBlank()) {
            String icon = panelFooterIconUrl();
            embed.setFooter(footer, icon.isBlank() ? null : icon);
        }

        return embed;
    }

    public static EmbedBuilder welcomeEmbed(String userMention, String topicLabel) {
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(welcomeTitle())
                .setDescription(
                        welcomeDescription()
                                .replace("{user}", userMention)
                                .replace("{topic}", topicLabel == null ? "-" : topicLabel)
                )
                .setColor(welcomeColor())
                .setFooter(welcomeFooter());

        applyUrl(embed::setImage, welcomeImageUrl());
        return embed;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return unescape(a);
        }
        return unescape(b == null ? "" : b);
    }

    private static String sanitizeValue(String raw, String fallback) {
        String cleaned = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "-")
                .replaceAll("-{2,}", "-");
        if (cleaned.isBlank()) {
            return fallback;
        }
        if (cleaned.length() > 100) {
            return cleaned.substring(0, 100);
        }
        return cleaned;
    }

    private static void applyUrl(java.util.function.Consumer<String> setter, String url) {
        if (url != null && !url.isBlank()) {
            setter.accept(url.trim());
        }
    }

    private static Color parseColor(String raw, Color fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return new Color(Integer.parseInt(raw.replace("#", "").trim(), 16));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String unescape(String value) {
        return value.replace("\\n", "\n");
    }
}
