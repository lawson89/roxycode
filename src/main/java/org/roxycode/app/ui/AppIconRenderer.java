package org.roxycode.app.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.springframework.lang.Nullable;

public final class AppIconRenderer {

    public enum AgentStatus {
        IDLE, BUSY, WAITING_FEEDBACK
    }

    public static final Color DEFAULT_PROJECT_GREEN = new Color(0x05, 0x96, 0x69); // Emerald

    public static final List<Color> PROJECT_PALETTE = List.of(DEFAULT_PROJECT_GREEN, // Emerald
            new Color(0x1d, 0x4e, 0xd8), // Cobalt
            new Color(0x7e, 0x22, 0xce), // Purple
            new Color(0x16, 0xa3, 0x4a), // Forest Green
            new Color(0x0f, 0x76, 0x6e), // Teal
            new Color(0x43, 0x38, 0xca), // Indigo
            new Color(0x02, 0x84, 0xc7), // Sky Blue
            new Color(0x0e, 0x74, 0x90) // Cyan
    );
    public static final Color STATUS_WAITING_COLOR = UiComponents.AMBER_DARK; // Amber 500
    public static final Color STATUS_WAITING_ALT_COLOR = new Color(0xff, 0xfb, 0xeb); // Warm Ivory
    public static final Color STATUS_WAITING_DARK_TEXT = new Color(0x45, 0x1a, 0x03);

    public static final int[] STANDARD_ICON_SIZES = new int[]{16, 32, 64, 128};
    private static final int MIN_PARTS_FOR_MONOGRAM = 2;
    private static final int MIN_CHARS_FOR_MONOGRAM = 2;

    @Nullable
    private static final BufferedImage BASE_ROXY_IMAGE = UiComponents
            .loadClasspathImage(UiComponents.ROXY_LOGO_RESOURCE);

    private AppIconRenderer() {
    }

    public static String extractMonogram(@Nullable String projectName) {
        if (projectName == null) {
            return "";
        }
        String trimmed = projectName.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if ("No Project".equalsIgnoreCase(trimmed) || "Select Project...".equalsIgnoreCase(trimmed)) {
            return "";
        }

        String[] parts = trimmed.split("[-_\s./]+");
        if (parts.length >= MIN_PARTS_FOR_MONOGRAM) {
            String p1 = parts[0].replaceAll("[^a-zA-Z0-9]", "");
            String p2 = parts[1].replaceAll("[^a-zA-Z0-9]", "");
            if (!p1.isEmpty() && !p2.isEmpty()) {
                String result = p1.substring(0, 1) + p2.substring(0, 1);
                return result.toUpperCase(Locale.ROOT);
            }
        }

        String clean = trimmed.replaceAll("[^a-zA-Z0-9]", "");
        if (clean.isEmpty()) {
            return "";
        }

        List<String> words = splitCamelCase(clean);
        if (words.size() >= MIN_PARTS_FOR_MONOGRAM) {
            String w1 = words.get(0);
            String w2 = words.get(1);
            if (!w1.isEmpty() && !w2.isEmpty()) {
                String result = w1.substring(0, 1) + w2.substring(0, 1);
                return result.toUpperCase(Locale.ROOT);
            }
        }

        if (clean.length() >= MIN_CHARS_FOR_MONOGRAM) {
            return clean.substring(0, 2).toUpperCase(Locale.ROOT);
        }
        return clean.toUpperCase(Locale.ROOT);
    }

    private static List<String> splitCamelCase(String s) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(s.charAt(i - 1))) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            }
            current.append(c);
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    public static Color resolveProjectColor(@Nullable String projectName) {
        if (projectName == null) {
            return PROJECT_PALETTE.get(0);
        }
        String normalized = projectName.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || "no project".equals(normalized)) {
            return PROJECT_PALETTE.get(0);
        }
        int hash = normalized.hashCode();
        int index = Math.floorMod(hash, PROJECT_PALETTE.size());
        return PROJECT_PALETTE.get(index);
    }

    public static List<Image> renderAppIconImages(@Nullable String projectName, AgentStatus status,
            boolean blinkHighlight) {
        List<Image> images = new ArrayList<>(STANDARD_ICON_SIZES.length);
        for (int size : STANDARD_ICON_SIZES) {
            images.add(renderAppIcon(size, projectName, status, blinkHighlight));
        }
        return Collections.unmodifiableList(images);
    }

    public static BufferedImage renderAppIcon(int size, @Nullable String projectName, AgentStatus status,
            boolean blinkHighlight) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

            paintBaseIcon(g2, size);

            if (status == AgentStatus.WAITING_FEEDBACK && blinkHighlight) {
                paintBlinkRing(g2, size);
            }

            String monogram = extractMonogram(projectName);
            if (!monogram.isEmpty()) {
                Color projectColor = resolveProjectColor(projectName);
                paintMonogramBadge(g2, size, monogram, projectColor);
            }

            if (status == AgentStatus.WAITING_FEEDBACK) {
                paintWaitingOverlay(g2, size, blinkHighlight);
            }
        } finally {
            g2.dispose();
        }
        return image;
    }

    private static void paintBaseIcon(Graphics2D g2, int size) {
        int arc = Math.max(4, Math.round(size * 0.22f));
        if (BASE_ROXY_IMAGE != null) {
            g2.drawImage(BASE_ROXY_IMAGE, 0, 0, size, size, null);
        } else {
            g2.setColor(new Color(0x1e, 0x29, 0x3b));
            g2.fillRoundRect(0, 0, size, size, arc, arc);
            g2.setColor(new Color(0x64, 0x74, 0x8b));
            g2.setStroke(new BasicStroke(1.0f));
            g2.drawRoundRect(0, 0, size - 1, size - 1, arc, arc);
        }
    }

    private static void paintBlinkRing(Graphics2D g2, int size) {
        int strokeWidth = Math.max(2, Math.round(size * 0.08f));
        int arc = Math.max(4, Math.round(size * 0.24f));
        g2.setColor(STATUS_WAITING_COLOR);
        g2.setStroke(new BasicStroke(strokeWidth));
        int halfStroke = strokeWidth / 2;
        g2.drawRoundRect(halfStroke, halfStroke, size - strokeWidth, size - strokeWidth, arc, arc);
    }

    private static void paintMonogramBadge(Graphics2D g2, int size, String monogram, Color badgeColor) {
        int badgeH = Math.max(7, Math.round(size * 0.30f));
        int badgeW = Math.max(8, Math.round(size * 0.46f));
        int x = Math.max(0, Math.round(size * 0.03f));
        int y = size - badgeH - Math.max(0, Math.round(size * 0.03f));
        int arc = Math.max(2, Math.round(badgeH * 0.42f));

        g2.setColor(new Color(0, 0, 0, 190));
        g2.fillRoundRect(x - 1, y - 1, badgeW + 2, badgeH + 2, arc + 2, arc + 2);

        g2.setColor(badgeColor);
        g2.fillRoundRect(x, y, badgeW, badgeH, arc, arc);

        int fontSize = Math.max(5, Math.round(badgeH * 0.74f));
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
        g2.setFont(font);
        FontMetrics fm = g2.getFontMetrics();
        int textW = fm.stringWidth(monogram);
        int textX = x + (badgeW - textW) / 2;
        int textY = y + ((badgeH - fm.getHeight()) / 2) + fm.getAscent();

        g2.setColor(Color.WHITE);
        g2.drawString(monogram, textX, textY);
    }

    private static void paintWaitingOverlay(Graphics2D g2, int size, boolean blinkHighlight) {
        int badgeSize = Math.max(7, Math.round(size * 0.36f));
        int pad = Math.max(1, Math.round(size * 0.03f));
        int x = size - badgeSize - pad;
        int y = pad;

        g2.setColor(new Color(0, 0, 0, 200));
        g2.fillOval(x - 1, y - 1, badgeSize + 2, badgeSize + 2);

        Color bg = STATUS_WAITING_COLOR;
        Color fg = Color.WHITE;
        if (blinkHighlight) {
            bg = STATUS_WAITING_ALT_COLOR;
            fg = STATUS_WAITING_DARK_TEXT;
        }

        g2.setColor(bg);
        g2.fillOval(x, y, badgeSize, badgeSize);

        int iconSize = Math.max(5, Math.round(badgeSize * 0.68f));
        FontIcon bellIcon = FontIcon.of(Codicons.BELL, iconSize, fg);
        int iconX = x + (badgeSize - bellIcon.getIconWidth()) / 2;
        int iconY = y + (badgeSize - bellIcon.getIconHeight()) / 2;
        bellIcon.paintIcon(null, g2, iconX, iconY);
    }
}
