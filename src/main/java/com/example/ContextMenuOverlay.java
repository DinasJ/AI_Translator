package com.example;

import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.ClientTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.awt.*;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
public class ContextMenuOverlay extends Overlay
{
    private static final int PADDING_X = 3;
    private static final int HEADER_HEIGHT = 17;
    private static final int ROW_HEIGHT = 15;

    private static final Color HDR_BG       = new Color(0x00, 0x00, 0x00);
    private static final Color HDR_TEXT     = new Color(0x64, 0x5B, 0x4D);
    private static final Color ROW_BG       = new Color(0x64, 0x5B, 0x4D);
    private static final Color BORDER_COL   = new Color(0x64, 0x5B, 0x4D);
    private static final Color HOVER_YELLOW = new Color(255, 255, 0);
    private static final String HDR_RU      = "Выберите Действие";

    private static final String CUSTOM_FONT_RESOURCE = "/fonts/Runescape-Bold12-ru.ttf";
    private static volatile Font customRawFont;

    private final Client client;
    private final GlossaryService glossaryService;

    private volatile MenuEntry[] lastEntries = new MenuEntry[0];
    private volatile List<String> preparedLines = java.util.Collections.emptyList();
    private volatile List<List<TextRun>> preparedRuns = java.util.Collections.emptyList();
    private volatile boolean menuOpen = false;

    @Inject
    private ContextMenuOverlay(Client client, GlossaryService glossaryService)
    {
        this.client = client;
        this.glossaryService = glossaryService;

        setPosition(OverlayPosition.DYNAMIC);
        setPriority(OverlayPriority.HIGHEST);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!client.isMenuOpen() || preparedLines.isEmpty() || preparedRuns.isEmpty())
        {
            return null;
        }

        final int x = client.getMenuX();
        final int y = client.getMenuY();
        final int w = client.getMenuWidth();
        final int h = client.getMenuHeight();
        if (w <= 0 || h <= 0) return null;

        Font rlBase = FontManager.getRunescapeFont();
        float targetSize = rlBase.getSize2D();
        Font menuFont = getMenuFont(targetSize);
        g.setFont(menuFont);
        FontMetrics fm = g.getFontMetrics(menuFont);
        final int ascent = fm.getAscent();
        final int descent = fm.getDescent();
        final int textHeight = ascent + descent;
        final int rowTopPadding = Math.max(0, (ROW_HEIGHT - textHeight) / 2);

        // Compute width based on cached runs
        int maxTextWidth = 0;
        for (List<TextRun> runs : preparedRuns)
        {
            int textW = runs.stream().mapToInt(r -> fm.stringWidth(r.text)).sum();
            if (textW > maxTextWidth) maxTextWidth = textW;
        }

        int neededW = PADDING_X * 2 + maxTextWidth;
        final int effectiveW = Math.max(w, neededW);

        java.awt.Shape menuClipOld = g.getClip();
        g.setClip(new Rectangle(x, y, effectiveW, h));

        // Header
        g.setColor(HDR_BG);
        g.fillRect(x, y, effectiveW, HEADER_HEIGHT);
        g.setColor(HDR_TEXT);
        int hdrTextY = y + ((HEADER_HEIGHT - textHeight) / 2) + ascent - 2;
        int hdrTextX = x + PADDING_X;
        g.drawString(HDR_RU, hdrTextX, hdrTextY);

        // Body
        final int bodyY = y + HEADER_HEIGHT;
        final int bodyH = Math.max(0, h - HEADER_HEIGHT);
        g.setColor(ROW_BG);
        g.fillRect(x, bodyY, effectiveW, bodyH);

        g.setColor(BORDER_COL);
        g.drawRect(x, y, effectiveW - 1, h - 1);

        g.setColor(Color.BLACK);
        if (bodyH > 2 && effectiveW > 2)
        {
            g.drawRect(x + 1, bodyY + 1, effectiveW - 3, bodyH - 3);
        }

        // Hover detection
        net.runelite.api.Point mp = client.getMouseCanvasPosition();
        int hoveredIndex = -1;
        if (mp != null)
        {
            int relY = mp.getY() - y - HEADER_HEIGHT;
            if (relY >= 0)
            {
                hoveredIndex = relY / ROW_HEIGHT;
                if (hoveredIndex < 0 || hoveredIndex >= preparedRuns.size()) hoveredIndex = -1;
            }
        }

        int baseY = y + HEADER_HEIGHT + rowTopPadding + ascent;

        for (int line = 0; line < preparedRuns.size(); line++)
        {
            List<TextRun> runs = preparedRuns.get(line);
            int lineY = baseY + line * ROW_HEIGHT;
            int cxLine = x + PADDING_X;

            boolean hovered = (line == hoveredIndex);
            int cx = cxLine;

            // Shadow
            String shadowLine = runs.stream().map(r -> r.text).reduce("", String::concat);
            if (!shadowLine.isEmpty())
            {
                g.setColor(Color.BLACK);
                g.drawString(shadowLine, cxLine + 1, lineY + 1);
            }

            for (TextRun run : runs)
            {
                g.setColor(hovered ? HOVER_YELLOW : run.color);
                g.drawString(run.text, cx, lineY);
                cx += fm.stringWidth(run.text);
            }
        }
        g.setClip(menuClipOld);

        return new Dimension(effectiveW, h);
    }

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
                                log.info("Loaded custom font from {}", CUSTOM_FONT_RESOURCE);
                            }
                        }
                        catch (Exception ex)
                        {
                            log.warn("Failed to load custom font: {}", ex.getMessage());
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

    /**
     * Prepare translated + parsed lines (runs cached for rendering).
     */
    private void prepareFrom(MenuEntry[] entries)
    {
        List<String> lines = new ArrayList<>();
        List<List<TextRun>> runsList = new ArrayList<>();
        if (entries != null && entries.length > 0)
        {
            for (int line = 0; line < entries.length; line++)
            {
                int idx = entries.length - 1 - line;
                MenuEntry e = entries[idx];
                if (e == null) continue;

                String actionRaw = safe(e.getOption()).trim();
                String targetRaw = safe(e.getTarget()).trim();
                GlossaryService.Type targetType = resolveType(e);

                String actionTranslated = glossaryService.translate(GlossaryService.Type.ACTION, actionRaw);
                String targetTranslated = translateWithColor(targetType, targetRaw);
                String composedLine = composeLine(actionTranslated, targetTranslated, targetType);

                lines.add(composedLine);
                runsList.add(parseTaggedRuns(composedLine, Color.WHITE));

                log.debug("[MenuEntry] idx={} type={} option='{}' target='{}' -> type={}, line='{}'",
                        idx, e.getType(), actionRaw, targetRaw, targetType, composedLine);
            }
        }
        preparedLines = lines;
        preparedRuns = runsList;
    }

    @Subscribe
    public void onMenuOpened(MenuOpened event)
    {
        if (!glossaryService.isLoaded())
        {
            log.info("First menu opened → loading glossaries...");
            glossaryService.loadAll();
        }

        MenuEntry[] entries = client.getMenuEntries();
        lastEntries = (entries != null) ? entries : new MenuEntry[0];

        prepareFrom(lastEntries);
        menuOpen = true;

        log.debug("Menu opened with {} entries, prepared {} translated lines",
                lastEntries.length, preparedLines.size());
    }

    private String translateWithColor(GlossaryService.Type type, String text)
    {
        if (text == null || text.isEmpty()) return text;

        if (text.toLowerCase().startsWith("<col="))
        {
            int endTag = text.indexOf(">");
            if (endTag >= 0)
            {
                String colorPrefix = text.substring(0, endTag + 1);
                String inner = text.substring(endTag + 1);
                String translatedInner = glossaryService.translate(type, inner);
                return colorPrefix + translatedInner + "</col>";
            }
        }

        return glossaryService.translate(type, text);
    }

    @Subscribe
    public void onClientTick(ClientTick tick)
    {
        if (menuOpen && !client.isMenuOpen())
        {
            log.debug("Menu closed, clearing overlay state");
            lastEntries = new MenuEntry[0];
            preparedLines = java.util.Collections.emptyList();
            preparedRuns = java.util.Collections.emptyList();
            menuOpen = false;
        }
    }

    private static final Pattern COL_OPEN_OR_CLOSE =
            Pattern.compile("(?i)<col=([0-9a-f]{6})>|</col>");

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
                if (buf.length() > 0)
                {
                    runs.add(new TextRun(buf.toString(), current));
                    buf.setLength(0);
                }
                int end = input.indexOf(">", i);
                if (end > i)
                {
                    String hex = input.substring(i + 5, end);
                    try { current = Color.decode("#" + hex); }
                    catch (Exception ignored) { current = defaultColor; }
                    i = end + 1;
                    continue;
                }
            }
            else if (input.regionMatches(true, i, "</col>", 0, 6))
            {
                if (buf.length() > 0)
                {
                    runs.add(new TextRun(buf.toString(), current));
                    buf.setLength(0);
                }
                current = defaultColor;
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

    private static String composeLine(String action, String target, GlossaryService.Type type)
    {
        if (action == null) action = "";
        if (target == null) target = "";
        if (action.isEmpty()) return target;
        if (target.isEmpty()) return action;

        char left = action.charAt(action.length() - 1);
        char right = target.charAt(0);
        boolean needSpace = needsSpaceBetween(left, right);

        // Only wrap if target isn’t already color-tagged
        String coloredTarget = target;
        if (!target.toLowerCase().startsWith("<col="))
        {
            String targetColor;
            switch (type)
            {
                case NPC:    targetColor = "ffff00"; break; // yellow
                case ITEM:   targetColor = "ff9040"; break; // orange
                case OBJECT: targetColor = "00ffff"; break; // cyan
                default:     targetColor = "ffffff"; break; // white
            }
            coloredTarget = "<col=" + targetColor + ">" + target + "</col>";
        }

        return needSpace ? (action + " " + coloredTarget) : (action + coloredTarget);
    }

    private static boolean needsSpaceBetween(char left, char right)
    {
        if (Character.isWhitespace(left)) return false;
        if (Character.isWhitespace(right)) return false;
        if (")],.:;!?".indexOf(right) >= 0) return false;
        if ("([{\\u00AB".indexOf(left) >= 0) return false;
        return true;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private GlossaryService.Type resolveType(MenuEntry e)
    {
        switch (e.getType())
        {
            // NPC actions
            case NPC_FIRST_OPTION: case NPC_SECOND_OPTION:
            case NPC_THIRD_OPTION: case NPC_FOURTH_OPTION:
            case NPC_FIFTH_OPTION:
            case EXAMINE_NPC:
                return GlossaryService.Type.NPC;

            // Item actions
            case ITEM_FIRST_OPTION: case ITEM_SECOND_OPTION:
            case ITEM_THIRD_OPTION: case ITEM_FOURTH_OPTION:
            case ITEM_FIFTH_OPTION: case ITEM_USE:
            case EXAMINE_ITEM:
                return GlossaryService.Type.ITEM;

            // Object actions
            case GAME_OBJECT_FIRST_OPTION: case GAME_OBJECT_SECOND_OPTION:
            case GAME_OBJECT_THIRD_OPTION: case GAME_OBJECT_FOURTH_OPTION:
            case GAME_OBJECT_FIFTH_OPTION:
            case EXAMINE_OBJECT:
                return GlossaryService.Type.OBJECT;

            default:
                return GlossaryService.Type.ACTION;
        }
    }
}
