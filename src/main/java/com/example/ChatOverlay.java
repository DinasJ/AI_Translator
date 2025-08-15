package com.example;

import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders cropped chat background and per-widget translated text.
 * Sticky rendering: translations are kept for a short grace period to avoid flicker
 * when widgets briefly disappear or report empty text between states.
 */
public class ChatOverlay extends Overlay
{
    private static final Logger log = LoggerFactory.getLogger(ChatOverlay.class);
    private static final boolean DEBUG = false; // enable for overlay-side logs

    private final Client client;
    private final SpriteManager spriteManager;

    // Source widgets to render over (kept fresh by the plugin)
    private volatile List<Widget> sourceWidgets = Collections.emptyList();

    // Translated text per widget id (plain text; no tags)
    private final Map<Integer, String> translatedById = new ConcurrentHashMap<>();
    // Last seen tick per widget id (to implement grace TTL)
    private final Map<Integer, Integer> lastSeenTickById = new ConcurrentHashMap<>();

    private static final float SCALE = 0.90f;
    private static final int CENTERED_LINE_GAP = 2;

    @Inject
    public ChatOverlay(Client client, SpriteManager spriteManager)
    {
        this.client = client;
        this.spriteManager = spriteManager;

        setPosition(OverlayPosition.DYNAMIC);
        setPriority(OverlayPriority.MED);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    // Called by plugin each tick
    public void updateSourceWidgets(List<Widget> widgets)
    {
        this.sourceWidgets = widgets != null ? new ArrayList<>(widgets) : Collections.emptyList();
    }

    public void setTranslation(int widgetId, String translated)
    {
        if (translated == null || translated.isEmpty())
        {
            // Do not remove here unconditionally; sticky behavior handled by pruneExpired
            translatedById.remove(widgetId);
        }
        else
        {
            translatedById.put(widgetId, translated);
        }
    }

    // Mark present ids as seen at a given tick (plugin calls this each tick)
    public void markSeen(Set<Integer> presentIds, int tick)
    {
        for (Integer id : presentIds)
        {
            lastSeenTickById.put(id, tick);
        }
    }

    // Remove translations that have not been seen for ttlTicks
    public void pruneExpired(int currentTick, int ttlTicks)
    {
        List<Integer> toRemove = new ArrayList<>();
        for (Map.Entry<Integer, String> e : translatedById.entrySet())
        {
            Integer id = e.getKey();
            Integer seen = lastSeenTickById.get(id);
            if (seen == null || seen < currentTick - ttlTicks)
            {
                toRemove.add(id);
            }
        }
        for (Integer id : toRemove)
        {
            if (DEBUG) log.info("Prune expired translation id={} (currentTick={})", id, currentTick);
            translatedById.remove(id);
            lastSeenTickById.remove(id);
        }
    }

    public void clearAllTranslations()
    {
        sourceWidgets = Collections.emptyList();
        translatedById.clear();
        lastSeenTickById.clear();
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        Widget chatBgParent = client.getWidget(162, 36);
        if (chatBgParent == null || chatBgParent.isHidden())
        {
            return null;
        }
        Widget chatBg = chatBgParent.getChild(0);
        if (chatBg == null || chatBg.isHidden())
        {
            return null;
        }

        BufferedImage bg = spriteManager.getSprite(chatBg.getSpriteId(), 0);
        if (bg == null)
        {
            return null;
        }

        // Clip to visible text areas and draw the background
        List<Rectangle> textBounds = collectTextBounds();
        if (!textBounds.isEmpty())
        {
            Shape oldClip = g.getClip();
            Area clipArea = new Area();
            for (Rectangle r : textBounds)
            {
                clipArea.add(new Area(r));
            }
            g.setClip(clipArea);

            Point bgLoc = chatBg.getCanvasLocation();
            g.drawImage(bg, bgLoc.getX(), bgLoc.getY(), null);

            g.setClip(oldClip);
        }

        // Draw per-widget translations (including those inside grace window)
        if (!translatedById.isEmpty())
        {
            drawPerWidget(g);
        }

        return null;
    }

    private List<Rectangle> collectTextBounds()
    {
        List<Rectangle> rects = new ArrayList<>();
        addWidgetBounds(rects, client.getWidget(WidgetInfo.DIALOG_NPC_TEXT));
        addWidgetBounds(rects, client.getWidget(WidgetInfo.DIALOG_PLAYER_TEXT));
        addWidgetBounds(rects, client.getWidget(WidgetInfo.DIALOG_NPC_NAME));
        addWidgetBounds(rects, client.getWidget(WidgetInfo.DIALOG_OPTION));
        addWidgetBounds(rects, client.getWidget(WidgetInfo.DIALOG_SPRITE_TEXT));
        addWidgetBounds(rects, client.getWidget(231, 5)); // Continue (left)
        addWidgetBounds(rects, client.getWidget(217, 5)); // Continue (right)
        return rects;
    }

    private void addWidgetBounds(List<Rectangle> rects, Widget w)
    {
        if (w != null && !w.isHidden())
        {
            Rectangle r = w.getBounds();
            if (r != null && !r.isEmpty())
            {
                rects.add(r);
            }
        }
    }

    private void drawPerWidget(Graphics2D g)
    {
        Font rsBase = FontManager.getRunescapeFont();
        Font cyrBase = new Font("Arial", Font.PLAIN, rsBase.getSize());
        Font rsFont = rsBase.deriveFont(rsBase.getSize2D() * SCALE);
        Font cyrFont = cyrBase.deriveFont(cyrBase.getSize2D() * SCALE);
        FontMetrics rsFM = g.getFontMetrics(rsFont);
        FontMetrics cyrFM = g.getFontMetrics(cyrFont);
        int rsAscent = rsFM.getAscent();
        int lineHeight = rsAscent + rsFM.getDescent();

        for (Widget w : sourceWidgets)
        {
            if (w == null || w.isHidden()) continue;
            int id = w.getId();
            String translated = translatedById.get(id);
            if (translated == null || translated.isEmpty())
            {
                continue;
            }

            Rectangle wb = w.getBounds();
            if (wb == null || wb.isEmpty())
            {
                continue;
            }

            // Slightly relaxed clip to avoid shaving off bottom baseline
            Rectangle clipRect = new Rectangle(wb.x, wb.y, wb.width, wb.height + 2);
            Shape oldClip = g.getClip();
            g.setClip(clipRect);

            java.awt.Color color;
            try { color = new java.awt.Color(w.getTextColor()); } catch (Exception e) { color = java.awt.Color.WHITE; }
            g.setColor(color);

            boolean isLeftName     = (id >>> 16) == 231 && (id & 0xFFFF) == 4;
            boolean isLeftText     = (id >>> 16) == 231 && (id & 0xFFFF) == 6;
            boolean isLeftContinue = (id >>> 16) == 231 && (id & 0xFFFF) == 5;
            boolean isRightName     = (id >>> 16) == 217 && (id & 0xFFFF) == 4;
            boolean isRightText     = (id >>> 16) == 217 && (id & 0xFFFF) == 6;
            boolean isRightContinue = (id >>> 16) == 217 && (id & 0xFFFF) == 5;

            boolean isName = isLeftName || isRightName;
            boolean isText = isLeftText || isRightText;
            boolean isContinue = isLeftContinue || isRightContinue;

            Point p = w.getCanvasLocation();
            int baseTopY = (p != null ? p.getY() : wb.y) + rsAscent;

            // Nudge baseline up by 1 px when bottom-anchored to avoid rounding clip
            int y = isContinue ? (wb.y + wb.height - rsFM.getDescent() - 1) : baseTopY;

            if (isName)
            {
                String row = translated.trim();
                int wpx = measureMixedWidth(row, rsFM, cyrFM);
                int x = wb.x + Math.max(0, (wb.width - wpx) / 2);
                drawMixed(g, row, x, y, rsFont, cyrFont, rsFM, cyrFM);
            }
            else if (isText)
            {
                List<String> rows = wrapToWidth(translated, Math.max(1, wb.width), rsFM, cyrFM);
                for (String row : rows)
                {
                    int wpx = measureMixedWidth(row, rsFM, cyrFM);
                    int x = wb.x + Math.max(0, (wb.width - wpx) / 2);
                    drawMixed(g, row, x, y, rsFont, cyrFont, rsFM, cyrFM);
                    y += lineHeight + CENTERED_LINE_GAP;
                    if (y > wb.y + wb.height + 1) break;
                }
            }
            else if (isContinue)
            {
                String row = translated.trim();
                int wpx = measureMixedWidth(row, rsFM, cyrFM);
                int x = wb.x + Math.max(0, (wb.width - wpx) / 2);
                drawMixed(g, row, x, y, rsFont, cyrFont, rsFM, cyrFM);
            }
            else
            {
                List<String> rows = wrapToWidth(translated, Math.max(1, wb.width), rsFM, cyrFM);
                int x = (p != null ? p.getX() : wb.x);
                for (String row : rows)
                {
                    drawMixed(g, row, x, y, rsFont, cyrFont, rsFM, cyrFM);
                    y += lineHeight;
                    if (y > wb.y + wb.height + 1) break;
                }
            }

            g.setClip(oldClip);
        }
    }

    // Wrapping and drawing helpers

    private List<String> wrapToWidth(String text, int maxWidth, FontMetrics rsFM, FontMetrics cyrFM)
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

    private List<String> hardBreakToken(String token, int maxWidth, FontMetrics rsFM, FontMetrics cyrFM)
    {
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        for (int i = 0; i < token.length(); i++)
        {
            char c = token.charAt(i);
            String next = part.toString() + c;
            if (measureMixedWidth(next, rsFM, cyrFM) <= maxWidth)
            {
                part.append(c);
            }
            else
            {
                if (part.length() > 0)
                {
                    parts.add(part.toString());
                    part.setLength(0);
                }
                part.append(c);
            }
        }
        if (part.length() > 0)
        {
            parts.add(part.toString());
        }
        return parts;
    }

    private int measureMixedWidth(String text, FontMetrics rsFM, FontMetrics cyrFM)
    {
        int w = 0;
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (isCyrillic(c)) w += cyrFM.charWidth(c);
            else w += rsFM.charWidth(c);
        }
        return w;
    }

    private static boolean isCyrillic(char c)
    {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CYRILLIC
                || b == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY;
    }

    private static boolean isDigitOrPunct(char c)
    {
        if (Character.isDigit(c)) return true;
        return "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~".indexOf(c) >= 0;
    }

    private void drawMixed(Graphics2D g, String text, int x, int y,
                           Font rsFont, Font cyrFont, FontMetrics rsFM, FontMetrics cyrFM)
    {
        if (text == null || text.isEmpty()) return;

        for (char c : text.toCharArray())
        {
            if (isCyrillic(c))
            {
                g.setFont(cyrFont);
                g.drawString(String.valueOf(c), x, y);
                x += cyrFM.charWidth(c);
            }
            else if (isDigitOrPunct(c))
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
}