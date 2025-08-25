package com.example;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.ui.FontManager;

import java.awt.*;
import java.io.InputStream;
import java.util.*;
import java.util.List;

public final class Utils
{
    private Utils() {}

    /* ===== String helpers ===== */

    /** Remove RuneLite/RS color tags etc. and normalize NBSP to space. */
    public static String stripTags(String s)
    {
        return s == null ? "" : s.replaceAll("<[^>]*>", "").replace('\u00A0', ' ');
    }

    /** Normalize a name for comparison (case- and space-insensitive). */
    public static String normalizeName(String s)
    {
        if (s == null) return "";
        String t = s.replace('\u00A0', ' ').trim();
        return t.toLowerCase();
    }

    /** Truncate long strings for logging/debug output. */
    public static String truncate(String s)
    {
        if (s == null) return "null";
        s = s.replace("\n", "\\n");
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    /** Ensure a non-empty language code, defaulting to RU. */
    public static String safeLang(String lang)
    {
        if (lang == null || lang.trim().isEmpty()) return "RU";
        return lang.trim().toUpperCase();
    }

    /** Extracts the plain, tag-free, trimmed text from a widget. */
    public static String toPlainText(Widget w)
    {
        if (w == null) return "";
        String raw = w.getText();
        return stripTags(raw == null ? "" : raw).trim();
    }

    /** Returns whichever set of children (dynamic → normal → static) is non-empty. */
    public static List<Widget> getAllChildren(Widget w)
    {
        if (w == null) return Collections.emptyList();
        Widget[] dyn = w.getDynamicChildren();
        if (dyn != null && dyn.length > 0) return Arrays.asList(dyn);
        Widget[] ch = w.getChildren();
        if (ch != null && ch.length > 0) return Arrays.asList(ch);
        Widget[] st = w.getStaticChildren();
        return st != null ? Arrays.asList(st) : Collections.emptyList();
    }

    /** Alternate comparator name (top→bottom then left→right). */
    public static final Comparator<Widget> BOUNDS_ORDER =
            Comparator.comparingInt((Widget w) -> {
                var b = w.getBounds();
                return b == null ? Integer.MAX_VALUE : b.y;
            }).thenComparingInt(w -> {
                var b = w.getBounds();
                return b == null ? Integer.MAX_VALUE : b.x;
            });

    /* ===== Widget classification helpers ===== */

    /** Is this the left/right chat name widget? */
    public static boolean isNameWidget(Widget w)
    {
        if (w == null) return false;
        int id = w.getId();
        int group = (id >>> 16);
        int child = (id & 0xFFFF);
        return (group == 231 || group == 217) && child == 4;
    }

    public static boolean isDialogNameWidget(Widget w)
    {
        if (w == null) return false;
        int id = w.getId();
        return id == WidgetInfo.DIALOG_NPC_NAME.getId()
                || id == WidgetInfo.DIALOG_PLAYER.getId(); // player name
    }

    public static boolean isDialogTextWidget(Widget w)
    {
        if (w == null) return false;
        int id = w.getId();
        return id == WidgetInfo.DIALOG_NPC_TEXT.getId()
                || id == WidgetInfo.DIALOG_PLAYER_TEXT.getId();
    }

    public static boolean isDialogUiWidget(Widget w)
    {
        if (w == null) return false;
        int id = w.getId();
        // "Click here to continue" left and right
        return id == (231 << 16 | 5) // left continue
                || id == (217 << 16 | 5); // right continue
    }

    /** Comparator used to sort widgets by bounds (top->bottom then left->right). */
    public static final Comparator<Widget> WIDGET_BOUNDS_COMPARATOR = (a, b) ->
    {
        if (a == null || b == null) return 0;
        Rectangle ra = a.getBounds();
        Rectangle rb = b.getBounds();
        if (ra == null || rb == null) return 0;
        int dy = Integer.compare(ra.y, rb.y);
        return (dy != 0) ? dy : Integer.compare(ra.x, rb.x);
    };

    /**
     * Heuristic: classify widget text into glossary type.
     * Consolidates rules used in TranslationManager + WidgetCollector.
     */
    public static GlossaryService.Type classifyWidget(Widget w, String plain)
    {
        if (w == null) return GlossaryService.Type.ACTION;
        if (isDialogNameWidget(w)) return GlossaryService.Type.NPC;
        if (isDialogTextWidget(w)) return GlossaryService.Type.DIALOG;
        if (isDialogUiWidget(w))   return GlossaryService.Type.UI;
        if (isNameWidget(w))       return GlossaryService.Type.NPC;

        if (plain != null)
        {
            String p = plain.trim();
            if ("Click here to continue".equalsIgnoreCase(p))
            {
                return GlossaryService.Type.UI;
            }
            if (p.endsWith(":"))
            {
                String name = p.substring(0, p.length() - 1).trim();
                if (!name.isEmpty() && name.length() <= 24 && !name.contains(" "))
                {
                    return GlossaryService.Type.NPC;
                }
            }
        }

        return GlossaryService.Type.ACTION;
    }

    /* ===== Overlay helpers ===== */

    /** Returns option row widgets in dialog options. (dynamic children preferred) */
    public static Widget[] getOptionRows(Widget optContainer)
    {
        if (optContainer == null) return new Widget[0];
        Widget[] dyn = optContainer.getDynamicChildren();
        if (dyn != null && dyn.length > 0) return dyn;
        Widget[] ch = optContainer.getChildren();
        if (ch != null && ch.length > 0) return ch;
        Widget[] st = optContainer.getStaticChildren();
        return st != null ? st : new Widget[0];
    }

    /** Returns bounding rectangles of icons inside an option container. */
    public static List<Rectangle> getOptionIconRects(Widget optContainer)
    {
        if (optContainer == null) return Collections.emptyList();
        List<Rectangle> rects = new ArrayList<>();
        Widget[] all = getOptionRows(optContainer);
        if (all == null) return rects;
        for (Widget w : all)
        {
            if (w == null || w.isHidden()) continue;
            try
            {
                int spriteId = w.getSpriteId();
                int itemId = w.getItemId();
                if (spriteId > 0 || itemId > 0)
                {
                    Rectangle b = w.getBounds();
                    if (b != null) rects.add(b);
                }
            }
            catch (Throwable ignored)
            {
                // fallback: treat empty-text children as icons
                String raw = w.getText();
                if (raw == null || stripTags(raw).trim().isEmpty())
                {
                    Rectangle b = w.getBounds();
                    if (b != null) rects.add(b);
                }
            }
        }
        return rects;
    }

    /** Collect bounding boxes of all dialogue text/name/continue widgets (non-options). */
    public static List<Rectangle> collectTextBounds(Client client)
    {
        List<Rectangle> rects = new ArrayList<>();
        int[] ids = {
                WidgetInfo.DIALOG_NPC_NAME.getId(),
                WidgetInfo.DIALOG_NPC_TEXT.getId(),
                WidgetInfo.DIALOG_PLAYER_TEXT.getId(),
                WidgetInfo.DIALOG_SPRITE_TEXT.getId()
        };
        for (int id : ids)
        {
            Widget w = client.getWidget(id);
            if (w != null && !w.isHidden())
            {
                Rectangle b = w.getBounds();
                if (b != null) rects.add(b);
            }
        }
        return rects;
    }

    /* ===== Font helpers ===== */

    /** Load the Cyrillic font from resources or return the fallback. */
    public static Font loadCyrFontOrFallback(Font fallback)
    {
        try (InputStream in = Utils.class.getResourceAsStream("/fonts/RuneScape-Quill-8-ru.ttf"))
        {
            if (in != null)
            {
                return FontManager.getRunescapeFont();
            }
        }
        catch (Exception ignored) {}
        return fallback;
    }

    /* ===== Character classification & drawing helpers ===== */

    public static boolean isCyrillic(char c)
    {
        // Broad Cyrillic ranges
        return (c >= '\u0400' && c <= '\u04FF') || (c >= '\u0500' && c <= '\u052F');
    }

    public static boolean isDigitChar(char c)
    {
        return Character.isDigit(c);
    }

    /**
     * A pragmatic punctuation check — this covers common punctuation used
     * in dialog/UI. If you want to be exhaustive, replace with Character.getType checks.
     */
    public static boolean isPunctuation(char c)
    {
        return "!?,.:;\"'()[]{}-–—".indexOf(c) >= 0;
    }

    /**
     * Draw mixed text using the cyrillic font for Cyrillic/punctuation characters,
     * and the RuneScape font for Latin/digits.
     */
    public static void drawMixed(Graphics2D g, String text, int x, int y,
                                 Font rsFont, Font cyrFont,
                                 FontMetrics rsFM, FontMetrics cyrFM)
    {
        if (text == null || text.isEmpty()) return;
        for (char c : text.toCharArray())
        {
            if (isCyrillic(c) || isPunctuation(c))
            {
                g.setFont(cyrFont);
                g.drawString(String.valueOf(c), x, y);
                x += cyrFM.charWidth(c);
            }
            else if (isDigitChar(c))
            {
                g.setFont(rsFont);
                g.drawString(String.valueOf(c), x, y);
                x += rsFM.charWidth(c);
            }
            else
            {
                g.setFont(rsFont);
                g.drawString(String.valueOf(c), x, y);
                x += rsFM.charWidth(c);
            }
        }
    }

    /** Wrap text to the given pixel width using mixed-font measurements. */
    public static List<String> wrapToWidth(String text, int maxWidth, FontMetrics rsFM, FontMetrics cyrFM)
    {
        if (text == null || text.isEmpty())
        {
            return Collections.singletonList("");
        }
        String[] tokens = text.split("\\s+");
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String token : tokens)
        {
            if (token.isEmpty()) continue;
            String candidate = current.length() == 0 ? token : current + " " + token;
            int w = measureMixedWidth(candidate, rsFM, cyrFM);

            if (w <= maxWidth)
            {
                current.setLength(0);
                current.append(candidate);
            }
            else
            {
                if (current.length() > 0)
                {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                if (measureMixedWidth(token, rsFM, cyrFM) > maxWidth)
                {
                    lines.addAll(hardBreakToken(token, maxWidth, rsFM, cyrFM));
                }
                else
                {
                    current.append(token);
                }
            }
        }
        if (current.length() > 0)
        {
            lines.add(current.toString());
        }
        return lines;
    }

    /** Measure width of mixed text using cyrillic FontMetrics for Cyrillic/punctuation and RS font for others. */
    public static int measureMixedWidth(String text, FontMetrics rsFM, FontMetrics cyrFM)
    {
        int w = 0;
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (isCyrillic(c) || isPunctuation(c))
            {
                w += cyrFM.charWidth(c);
            }
            else if (isDigitChar(c))
            {
                w += rsFM.charWidth(c);
            }
            else
            {
                w += rsFM.charWidth(c);
            }
        }
        return w;
    }

    private static List<String> hardBreakToken(String token, int maxWidth,
                                               FontMetrics rsFM, FontMetrics cyrFM)
    {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int w = 0;
        for (int i = 0; i < token.length(); i++)
        {
            char c = token.charAt(i);
            int cw = (isCyrillic(c) || isPunctuation(c)) ? cyrFM.charWidth(c) : rsFM.charWidth(c);
            if (w + cw > maxWidth && current.length() > 0)
            {
                parts.add(current.toString());
                current.setLength(0);
                w = 0;
            }
            current.append(c);
            w += cw;
        }
        if (current.length() > 0)
        {
            parts.add(current.toString());
        }
        return parts;
    }
}
