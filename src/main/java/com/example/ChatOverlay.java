package com.example;

import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Draw a background image from widget 162,36, but only underneath
 * the regions where dialogue text is visible. We do NOT render text;
 * we just clip the background to the text areas, leaving the original
 * English text fully visible.
 */
public class ChatOverlay extends Overlay
{
    private final Client client;
    private final SpriteManager spriteManager;

    @Inject
    public ChatOverlay(Client client, SpriteManager spriteManager)
    {
        this.client = client;
        this.spriteManager = spriteManager;

        setPosition(OverlayPosition.DYNAMIC);
        setPriority(OverlayPriority.MED);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
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

        List<Rectangle> textBounds = new ArrayList<>();
        addWidgetBounds(textBounds, client.getWidget(WidgetInfo.DIALOG_NPC_TEXT));
        addWidgetBounds(textBounds, client.getWidget(WidgetInfo.DIALOG_PLAYER_TEXT));
        addWidgetBounds(textBounds, client.getWidget(WidgetInfo.DIALOG_NPC_NAME));
        addWidgetBounds(textBounds, client.getWidget(WidgetInfo.DIALOG_OPTION));
        addWidgetBounds(textBounds, client.getWidget(WidgetInfo.DIALOG_SPRITE_TEXT));
        addWidgetBounds(textBounds, client.getWidget(231, 5)); // ChatLeft.Continue
        addWidgetBounds(textBounds, client.getWidget(217, 5)); // ChatRight.Continue

        if (textBounds.isEmpty())
        {
            return null;
        }

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
        return null;
    }

    /**
     * Build union of rectangles covering the visible text areas of dialogue widgets.
     * We estimate line rects using FontMetrics against the widget text (HTML-stripped),
     * with a small padding to avoid tight clipping.
     */
    private Area buildTextArea(Graphics2D g)
    {
        final Area area = new Area();

        // Candidate widgets that contain dialogue text
        final Widget[] candidates = new Widget[] {
                client.getWidget(231, 4), // NAME (left)
                client.getWidget(231, 5), // CONTINUE (left)
                client.getWidget(231, 6), // TEXT (left)
                client.getWidget(217, 4), // NAME (right)
                client.getWidget(217, 5), // CONTINUE (right)
                client.getWidget(217, 6)  // TEXT (right)
        };

        final FontMetrics fm = g.getFontMetrics(); // overlay FM; close enough for masking
        final int lineHeight = fm.getAscent() + fm.getDescent();
        final int paddingX = 2; // tiny horizontal padding to avoid tight clipping
        final int paddingY = 1; // tiny vertical padding

        for (Widget w : candidates)
        {
            if (w == null || w.isHidden())
            {
                continue;
            }

            String text = w.getText();
            if (text == null || text.trim().isEmpty())
            {
                continue;
            }

            // Prefer precise canvas location; fall back to widget bounds if needed
            net.runelite.api.Point p = w.getCanvasLocation(); // may be null if not drawn yet
            final Rectangle wb = w.getBounds();
            if (p == null)
            {
                if (wb == null)
                {
                    continue;
                }
                p = new net.runelite.api.Point(wb.x, wb.y);
            }

            // Split by <br> and strip inline tags so stringWidth is accurate
            final String[] lines = text.split("<br>");
            int y = p.getY() - fm.getAscent(); // approximate top of first line
            boolean anyLine = false;

            for (String line : lines)
            {
                final String s = stripTags(line);
                if (s.isEmpty())
                {
                    y += lineHeight;
                    continue;
                }

                anyLine = true;
                final int wpx = fm.stringWidth(s);

                final Rectangle rect = new Rectangle(
                        p.getX() - paddingX,
                        y - paddingY,
                        Math.max(1, wpx + 2 * paddingX),
                        lineHeight + 2 * paddingY
                );
                area.add(new Area(rect));
                y += lineHeight; // next baseline
            }

            // Fallback: if no measurable lines, approximate using widget bounds
            if (!anyLine && wb != null)
            {
                area.add(new Area(wb));
            }
        }

        return area;
    }

    private static String stripTags(String s)
    {
        if (s == null) return "";
        // Remove RuneScape-style color/format tags and generic HTML tags
        // Examples: <col=ffff00>, </col>, <br>, <img=...>, <u>, </u>, etc.
        return s.replaceAll("<[^>]*>", "").trim();
    }
}
