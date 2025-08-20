package com.example;

import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.ClientTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import java.awt.*;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class ContextMenuOverlay extends Overlay
{
    // Layout
    private static final int PADDING_X = 3;
    private static final int HEADER_HEIGHT = 17;
    private static final int ROW_HEIGHT = 15;

    // Colors and header
    private static final Color HDR_BG       = new Color(0x00, 0x00, 0x00);   // black
    private static final Color HDR_TEXT     = new Color(0x64, 0x5B, 0x4D);   // #645B4D
    private static final Color ROW_BG       = new Color(0x64, 0x5B, 0x4D);   // #645B4D
    private static final Color BORDER_COL   = new Color(0x64, 0x5B, 0x4D);   // outer 1px border color
    private static final Color HOVER_YELLOW = new Color(255, 255, 0);
    private static final String HDR_RU      = "Выберите Действие";

    // Custom font resource (bundled)
    private static final String CUSTOM_FONT_RESOURCE = "/fonts/Runescape-Bold12-ru.ttf";
    private static volatile Font customRawFont;

    private final Client client;
    private final LocalGlossary localGlossary;

    // Snapshot for current open menu
    private volatile MenuEntry[] lastEntries = new MenuEntry[0];
    private volatile List<String> preparedLines = java.util.Collections.emptyList();
    private volatile boolean menuOpen = false;

    @Inject
    private ContextMenuOverlay(Client client, LocalGlossary localGlossary)
    {
        this.client = client;
        this.localGlossary = localGlossary;

        setPosition(OverlayPosition.DYNAMIC);
        setPriority(OverlayPriority.HIGHEST);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!client.isMenuOpen() || preparedLines.isEmpty())
        {
            return null;
        }

        // Read vanilla tooltip rectangle
        final int x = client.getMenuX();
        final int y = client.getMenuY();
        final int w = client.getMenuWidth();
        final int h = client.getMenuHeight();
        if (w <= 0 || h <= 0) return null;

        // Font
        Font rlBase = FontManager.getRunescapeFont();
        float targetSize = rlBase.getSize2D();
        Font menuFont = getMenuFont(targetSize);
        g.setFont(menuFont);
        FontMetrics fm = g.getFontMetrics(menuFont);
        // Metrics
        final int ascent = fm.getAscent();
        final int descent = fm.getDescent();
        final int textHeight = ascent + descent;
        final int rowTopPadding = Math.max(0, (ROW_HEIGHT - textHeight) / 2);

        // Measure max width
        int maxTextWidth = 0;
        for (String lineText : preparedLines)
        {
            List<TextRun> runs = parseTaggedRuns(lineText, Color.WHITE);
            int textW = 0;
            for (TextRun run : runs)
            {
                textW += fm.stringWidth(run.text);
            }
            if (textW > maxTextWidth) maxTextWidth = textW;
        }

        int neededW = PADDING_X * 2 + maxTextWidth;
        final int effectiveW = Math.max(w, neededW);

        java.awt.Shape menuClipOld = g.getClip();
        g.setClip(new Rectangle(x, y, effectiveW, h));

        // Header (black, no header-specific border)
        g.setColor(HDR_BG);
        g.fillRect(x, y, effectiveW, HEADER_HEIGHT);
        g.setColor(HDR_TEXT);
        int hdrTextY = y + ((HEADER_HEIGHT - textHeight) / 2) + ascent - 2; // nudge up by 2px
        int hdrTextX = x + PADDING_X;
        g.drawString(HDR_RU, hdrTextX, hdrTextY);

        // Body background
        final int bodyY = y + HEADER_HEIGHT;
        final int bodyH = Math.max(0, h - HEADER_HEIGHT);
        g.setColor(ROW_BG);
        g.fillRect(x, bodyY, effectiveW, bodyH);

        // Outer 1px border around the whole rectangle (#645B4D)
        g.setColor(BORDER_COL);
        g.drawRect(x, y, effectiveW - 1, h - 1);

        // Inner 1px border for the options area (inside the body rect). No borders between lines.
        g.setColor(Color.BLACK);
        if (bodyH > 2 && effectiveW > 2)
        {
            g.drawRect(x + 1, bodyY + 1, effectiveW - 3, bodyH - 3);
        }

        // Hovered row index
        net.runelite.api.Point mp = client.getMouseCanvasPosition();
        int hoveredIndex = -1;
        if (mp != null)
        {
            int relY = mp.getY() - y - HEADER_HEIGHT;
            if (relY >= 0) {
                hoveredIndex = relY / ROW_HEIGHT;
                if (hoveredIndex < 0 || hoveredIndex >= preparedLines.size()) hoveredIndex = -1;
            }
        }

        // Baseline for first rendered row (bottom-most entry is index 0 in our prepared list)
        int baseY = y + HEADER_HEIGHT + rowTopPadding + ascent;

        // Draw rows (preparedLines is ordered bottom-up)
        for (int line = 0; line < preparedLines.size(); line++)
        {
            String full = preparedLines.get(line);
            int lineY = baseY + line * ROW_HEIGHT;
            int cxLine = x + PADDING_X;

            boolean hovered = (line == hoveredIndex);

            // Foreground (hover highlight for default white)
            Color base = hovered ? HOVER_YELLOW : Color.WHITE;
            List<TextRun> runs = parseTaggedRuns(full, base);

            // --- Shadow pass (draw entire line once) ---
            StringBuilder shadowLine = new StringBuilder();
            for (TextRun run : runs)
            {
                shadowLine.append(run.text);
            }
            if (shadowLine.length() > 0)
            {
                g.setColor(Color.BLACK);
                g.drawString(shadowLine.toString(), cxLine + 1, lineY + 1);
            }

            // --- Foreground pass (colored segments) ---
            int cx = cxLine;
            for (TextRun run : runs)
            {
                g.setColor(run.color);
                g.drawString(run.text, cx, lineY);
                cx += fm.stringWidth(run.text);
            }
        }
        g.setClip(menuClipOld);

        return new Dimension(effectiveW, h);
    }

    // Load bundled RU font; fallback to RuneScape font
    private Font getMenuFont(float targetSize)
    {
        try
        {
            if (customRawFont == null)
            {
                synchronized (ContextMenuOverlay.class)
                {
                    if (customRawFont == null)
                    {
                        try (InputStream is = ContextMenuOverlay.class.getResourceAsStream(CUSTOM_FONT_RESOURCE))
                        {
                            if (is != null)
                            {
                                Font raw = Font.createFont(Font.TRUETYPE_FONT, is);
                                customRawFont = raw;
                            }
                        }
                        catch (Exception ignored)
                        {
                            customRawFont = null;
                        }
                    }
                }
            }
            if (customRawFont != null)
            {
                return customRawFont.deriveFont(Font.BOLD, targetSize);
            }
            return FontManager.getRunescapeFont().deriveFont(Font.BOLD, targetSize);
        }
        catch (Exception ignored)
        {
            return FontManager.getRunescapeFont();
        }
    }

    // Build the lines once on menu open, avoiding repeated LocalGlossary lookups every frame.
    private List<String> prepareLinesFrom(MenuEntry[] entries)
    {
        List<String> out = new ArrayList<>();
        if (entries == null || entries.length == 0) return out;

        for (int line = 0; line < entries.length; line++)
        {
            int idx = entries.length - 1 - line; // bottom-up like vanilla
            MenuEntry e = entries[idx];
            if (e == null) continue;

            String rawOption = safe(e.getOption()).trim();
            String rawTarget = safe(e.getTarget()); // keep <col> tags

            // Parse action from rawOption using color logic:
            // default = action, <col=ffffff> = action, any other color = target
            List<TextRun> optionRuns = parseTaggedRuns(rawOption, Color.WHITE);
            StringBuilder actionBuf = new StringBuilder();
            for (TextRun run : optionRuns)
            {
                if (run.color.equals(Color.WHITE)) {
                    actionBuf.append(run.text);
                } else {
                    // colored => treat as target instead of action
                    rawTarget = run.text + (rawTarget.isEmpty() ? "" : " " + rawTarget);
                }
            }
            String actionRaw = actionBuf.toString().trim();

            // Translate action strictly via glossary
            String actionTranslated = lookupStrictAction(actionRaw);
            if (actionTranslated == null) {
                actionTranslated = actionRaw;
            }

            // Recompose line (this preserves col-tags on target)
            String translatedLine = composeLine(actionTranslated, rawTarget);

            out.add(translatedLine);
        }
        return out;
    }


    // New strict glossary lookup
    private String lookupStrictAction(String src)
    {
        if (src == null || src.isEmpty()) return null;

        // 1. Exact, case-sensitive
        try {
            String v = localGlossary.lookup(src, LocalGlossary.GlossaryType.ACTION);
            if (v != null) return v;
        } catch (Exception ignore) {}

        // 2. Normalized
        String norm = normalizeAction(src);
        if (!norm.equals(src))
        {
            try {
                String v = localGlossary.lookup(norm, LocalGlossary.GlossaryType.ACTION);
                if (v != null) return v;
            } catch (Exception ignore) {}
        }

        return null;
    }

    @Subscribe
    public void onMenuOpened(MenuOpened ev)
    {
        MenuEntry[] snap = client.getMenuEntries();
        if (snap == null) snap = new MenuEntry[0];
        lastEntries = snap;
        preparedLines = prepareLinesFrom(snap);
        menuOpen = true;
    }

    @Subscribe
    public void onClientTick(ClientTick tick)
    {
        // When menu closes, clear snapshot to avoid repeated prep/lookups
        if (menuOpen && !client.isMenuOpen())
        {
            lastEntries = new MenuEntry[0];
            preparedLines = java.util.Collections.emptyList();
            menuOpen = false;
        }
    }

    // ----------------- helpers -----------------

    private static final Pattern COL_OPEN_OR_CLOSE = Pattern.compile("(?i)<col=([0-9a-f]{6})>|</col>");

    private static String stripColTags(String s)
    {
        if (s == null || s.isEmpty()) return "";
        return COL_OPEN_OR_CLOSE.matcher(s).replaceAll("");
    }
    private static class TextRun
    {
        final String text;
        final Color color;
        TextRun(String text, Color color) { this.text = text; this.color = color; }
    }

    private static List<TextRun> parseTaggedRuns(String input, Color defaultColor)
    {
        List<TextRun> runs = new ArrayList<>();
        if (input == null || input.isEmpty()) return runs;

        Color current = defaultColor;
        StringBuilder buf = new StringBuilder();

        for (int i = 0; i < input.length(); )
        {
            if (input.regionMatches(true, i, "<col=", 0, 5))
            {
                // flush buffer
                if (buf.length() > 0)
                {
                    runs.add(new TextRun(buf.toString(), current));
                    buf.setLength(0);
                }

                int end = input.indexOf(">", i);
                if (end > i)
                {
                    String hex = input.substring(i + 5, end);
                    try { current = Color.decode("#" + hex); } catch (Exception ignore) {}
                    i = end + 1;
                    continue;
                }
            }
            else if (input.regionMatches(true, i, "</col>", 0, 6))
            {
                // flush buffer
                if (buf.length() > 0)
                {
                    runs.add(new TextRun(buf.toString(), current));
                    buf.setLength(0);
                }
                current = defaultColor; // reset
                i += 6;
                continue;
            }

            buf.append(input.charAt(i));
            i++;
        }

        if (buf.length() > 0)
        {
            runs.add(new TextRun(buf.toString(), current));
        }

        return runs;
    }
    private static String composeLine(String action, String target)
    {
        if (action == null) action = "";
        if (target == null) target = "";
        if (action.isEmpty()) return target;
        if (target.isEmpty()) return action;

        char left = action.charAt(action.length() - 1);
        char right = target.charAt(0);
        boolean needSpace = needsSpaceBetween(left, right);
        return needSpace ? (action + " " + target) : (action + target);
    }

    private static boolean needsSpaceBetween(char left, char right)
    {
        if (Character.isWhitespace(left)) return false;
        if (Character.isWhitespace(right)) return false;
        if (")],.:;!?".indexOf(right) >= 0) return false;
        if ("([{\\u00AB".indexOf(left) >= 0) return false;
        return true;
    }

    private static String normalizeAction(String s)
    {
        String t = safe(s).trim();
        if (t.isEmpty()) return t;
        t = t.replaceAll("<[^>]*>", "");
        t = t.replace('\u00A0', ' ');
        t = t.replace('-', ' ');
        t = t.replaceAll("\\s+", " ");
        return t;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    // Flexible glossary lookup (best-effort)
    // Flexible glossary lookup with prefix matching
    private String lookupLocalFlexible(String src)
    {
        if (src == null) return null;
        String v;

        // 1. Direct exact match
        try { v = localGlossary.lookup(src, LocalGlossary.GlossaryType.ACTION); } catch (Exception ignore) { v = null; }
        if (v != null) return v;

        // 3. Strip <col> tags and retry
        String stripped = stripColTags(src);
        if (!stripped.equals(src))
        {
            try { v = localGlossary.lookup(stripped, LocalGlossary.GlossaryType.ACTION); } catch (Exception ignore) { v = null; }
            if (v != null) return v;
        }

        // 4. Hyphen / space variants
        String hyphenVariant = src.replace(' ', '-');
        if (!hyphenVariant.equals(src))
        {
            try { v = localGlossary.lookup(hyphenVariant, LocalGlossary.GlossaryType.ACTION); } catch (Exception ignore) { v = null; }
            if (v != null) return v;
        }

        String spaceVariant = src.replace('-', ' ');
        if (!spaceVariant.equals(src))
        {
            try { v = localGlossary.lookup(spaceVariant, LocalGlossary.GlossaryType.ACTION); } catch (Exception ignore) { v = null; }
            if (v != null) return v;
        }

        // 5. Normalized variant
        String normalized = normalizeAction(src);
        if (!normalized.equals(src))
        {
            try { v = localGlossary.lookup(normalized, LocalGlossary.GlossaryType.ACTION); } catch (Exception ignore) { v = null; }
            if (v != null) return v;
        }

        return null;
    }
}
