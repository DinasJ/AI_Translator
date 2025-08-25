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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.example.Utils.*;

@Singleton
public class TranslationOverlay extends Overlay
{
    private static final Logger log = LoggerFactory.getLogger(TranslationOverlay.class);

    private final Client client;
    private final SpriteManager spriteManager;

    /** Translations stored strictly by REAL widget id (no synthetic IDs here). */
    private final Map<Widget, String> translatedByWidget = new ConcurrentHashMap<>();

    @Inject
    public TranslationOverlay(Client client, SpriteManager spriteManager)
    {
        this.client = client;
        this.spriteManager = spriteManager;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
        setPriority(OverlayPriority.HIGHEST);
    }

    /** Store or clear translation for a REAL widget id. */
    /** Store or clear translation for a Widget (not just id). */
    public void updateTranslation(Widget widget, String translated) {
        if (widget == null) return;
        if (translated == null || translated.isEmpty()) {
            translatedByWidget.remove(widget);
            if (log.isDebugEnabled())
                log.debug("[OVERLAY] cleared widget obj={} id={}", System.identityHashCode(widget), widget.getId());
        } else {
            translatedByWidget.put(widget, translated);
            if (log.isDebugEnabled())
                log.debug("[OVERLAY] stored widget obj={} id={} -> '{}'",
                        System.identityHashCode(widget), widget.getId(), translated);
        }
    }
    @Override
    public Dimension render(Graphics2D g)
    {
        if (log.isTraceEnabled())
            log.trace("[OVERLAY] render start ({} translations stored)", translatedByWidget.size());

        // --- Draw clipped chat background under active text ---
        Widget chatBgParent = client.getWidget(162, 36);
        if (chatBgParent == null || chatBgParent.isHidden())
        {
            if (log.isTraceEnabled()) log.trace("[OVERLAY] chatBgParent null/hidden");
            return null;
        }

        Widget chatBg = chatBgParent.getChild(0);
        if (chatBg == null || chatBg.isHidden())
        {
            if (log.isTraceEnabled()) log.trace("[OVERLAY] chatBg null/hidden");
            return null;
        }

        BufferedImage bg = spriteManager.getSprite(chatBg.getSpriteId(), 0);
        if (bg == null)
        {
            if (log.isTraceEnabled()) log.trace("[OVERLAY] chatBg sprite not loaded (spriteId={})", chatBg.getSpriteId());
            return null;
        }

        // Clip differently depending on dialogue vs. option state
        Widget optContainer = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
        if (optContainer != null && !optContainer.isHidden())
        {
            if (log.isDebugEnabled()) log.debug("[OVERLAY] option container visible id={}", optContainer.getId());

            Widget[] rows = getOptionRows(optContainer);
            if (rows != null && rows.length > 0)
            {
                java.util.List<Rectangle> textRowRects = new java.util.ArrayList<>();
                java.util.List<Widget> ordered = new java.util.ArrayList<>();
                for (Widget w : rows) if (w != null && !w.isHidden()) ordered.add(w);
                ordered.sort(WIDGET_BOUNDS_COMPARATOR);

                int taken = 0;
                for (Widget r : ordered)
                {
                    String raw = r.getText();
                    String plain = stripTags(raw);
                    if (plain.isEmpty()) continue;
                    Rectangle b = r.getBounds();
                    if (b != null && b.width > 0 && b.height > 0)
                    {
                        textRowRects.add(b);
                        if (log.isTraceEnabled()) log.trace("[OVERLAY][options] row id={} bounds={} text='{}'", r.getId(), b, truncate(plain));
                        if (++taken >= 6) break;
                    }
                }

                java.util.List<Rectangle> iconRects = getOptionIconRects(optContainer);

                if (!textRowRects.isEmpty())
                {
                    if (log.isDebugEnabled())
                        log.debug("[OVERLAY] clipping background to {} text rects, subtract {} icons",
                                textRowRects.size(), iconRects.size());

                    Shape oldClip = g.getClip();
                    Area clipArea = new Area();
                    for (Rectangle r : textRowRects) clipArea.add(new Area(r));
                    for (Rectangle ir : iconRects) clipArea.subtract(new Area(ir));
                    g.setClip(clipArea);

                    Point bgLoc = chatBg.getCanvasLocation();
                    if (bgLoc != null)
                        g.drawImage(bg, bgLoc.getX(), bgLoc.getY(), null);

                    g.setClip(oldClip);
                }
            }
        }
        else
        {
            if (log.isDebugEnabled()) log.debug("[OVERLAY] non-option dialogue state");

            java.util.List<Rectangle> rects = collectTextBounds(client);
            if (!rects.isEmpty())
            {
                if (log.isDebugEnabled()) log.debug("[OVERLAY] clipping background to {} dialogue rects", rects.size());

                Shape oldClip = g.getClip();
                Area clipArea = new Area();
                for (Rectangle r : rects) clipArea.add(new Area(r));
                g.setClip(clipArea);

                Point bgLoc = chatBg.getCanvasLocation();
                if (bgLoc != null)
                    g.drawImage(bg, bgLoc.getX(), bgLoc.getY(), null);

                g.setClip(oldClip);
            }
        }

        // --- Prepare fonts ---
        Font rsFont = FontManager.getRunescapeFont();
        Font cyrFont = Utils.loadCyrFontOrFallback(rsFont);
        FontMetrics rsFM = g.getFontMetrics(rsFont);
        FontMetrics cyrFM = g.getFontMetrics(cyrFont);

        // --- Combined options translation ---
        if (optContainer != null && !optContainer.isHidden())
        {
            String combined = translatedByWidget.get(optContainer);
            if (combined != null && !combined.isEmpty())
            {
                if (log.isDebugEnabled()) log.debug("[OVERLAY] rendering combined option translation '{}'...", truncate(combined));

                Widget[] rowArr = getOptionRows(optContainer);
                if (rowArr != null && rowArr.length > 0)
                {
                    java.util.List<Widget> textRows = new java.util.ArrayList<>();
                    for (Widget r : rowArr)
                    {
                        if (r == null || r.isHidden()) continue;
                        String raw = r.getText();
                        if (raw == null || stripTags(raw).trim().isEmpty()) continue;
                        textRows.add(r);
                    }
                    textRows.sort(WIDGET_BOUNDS_COMPARATOR);

                    if (!textRows.isEmpty())
                    {
                        Shape oldClip = g.getClip();
                        Area union = new Area();
                        for (int i = 0; i < Math.min(textRows.size(), 6); i++)
                        {
                            Rectangle rb = textRows.get(i).getBounds();
                            if (rb != null && rb.width > 0 && rb.height > 0) union.add(new Area(rb));
                        }
                        for (Rectangle ir : getOptionIconRects(optContainer)) union.subtract(new Area(ir));
                        g.setClip(union);

                        String[] lines = combined.split("\\R", -1);
                        int count = Math.min(lines.length, textRows.size());
                        int rsAscent = rsFM.getAscent();
                        int lineHeight = rsAscent + rsFM.getDescent();

                        for (int i = 0; i < Math.min(count, 6); i++)
                        {
                            Widget r = textRows.get(i);
                            Rectangle rb = r.getBounds();
                            if (rb == null || rb.isEmpty()) continue;

                            String text = lines[i].trim();
                            if (log.isTraceEnabled())
                                log.trace("[OVERLAY][options] drawing line {}='{}' at {}", i, text, rb);

                            Color color;
                            try { color = new Color(r.getTextColor()); } catch (Exception ex) { color = Color.WHITE; }
                            g.setColor(color);

                            int wpx = measureMixedWidth(text, rsFM, cyrFM);
                            int x = rb.x + Math.max(0, (rb.width - wpx) / 2);
                            int y = rb.y + Math.max(0, (rb.height - lineHeight) / 2) + rsAscent;

                            drawMixed(g, text, x, y, rsFont, cyrFont, rsFM, cyrFM);
                        }

                        g.setClip(oldClip);
                    }
                }
            }
        }

        // --- Draw all stored translations ---
        for (Map.Entry<Widget, String> e : translatedByWidget.entrySet())
        {
            Widget w = e.getKey();
            if (w == null || w.isHidden()) continue;

            if (log.isTraceEnabled())
                log.trace("[OVERLAY] drawing per-widget translation id={} obj={} '{}'",
                        w.getId(), System.identityHashCode(w), truncate(e.getValue()));

            drawPerWidget(g, w, e.getValue(), rsFont, cyrFont, rsFM, cyrFM);
        }

        if (log.isTraceEnabled()) log.trace("[OVERLAY] render end");
        return null;
    }

    private void drawPerWidget(Graphics2D g,
                               Widget w,
                               String translated,
                               Font rsFont, Font cyrFont,
                               FontMetrics rsFM, FontMetrics cyrFM)
    {
        if (w == null || w.isHidden()) return;

        String fallbackText = stripTags(w.getText());
        if (translated == null || translated.isEmpty()) translated = fallbackText;

        Rectangle wb = w.getBounds();
        if (wb == null || wb.isEmpty()) return;

        Color color;
        try { color = new Color(w.getTextColor()); }
        catch (Exception ex) { color = Color.WHITE; }
        g.setColor(color);

        int group = w.getId() >>> 16;
        int child = w.getId() & 0xFFFF;

        boolean isLeftName       = group == 231 && child == 4;
        boolean isLeftText       = group == 231 && child == 6;
        boolean isLeftContinue   = group == 231 && child == 5;
        boolean isRightName      = group == 217 && child == 4;
        boolean isRightText      = group == 217 && child == 6;
        boolean isRightContinue  = group == 217 && child == 5;

        boolean isName = isLeftName || isRightName;
        boolean isTextBody = isLeftText || isRightText;
        boolean isContinue = isLeftContinue || isRightContinue;

        int rsAscent = rsFM.getAscent();
        int lineHeight = rsAscent + rsFM.getDescent();

        if (isName || isContinue)
        {
            String row = translated.trim();
            int wpx = measureMixedWidth(row, rsFM, cyrFM);
            int x = wb.x + Math.max(0, (wb.width - wpx) / 2);
            int y = wb.y + Math.max(0, (wb.height - lineHeight) / 2) + rsAscent;
            drawMixed(g, row, x, y, rsFont, cyrFont, rsFM, cyrFM);
            return;
        }
        else if (isTextBody)
        {
            List<String> rows = wrapToWidth(translated, Math.max(1, wb.width), rsFM, cyrFM);
            int n = rows.size();
            int totalBlockH = n * lineHeight + Math.max(0, (n - 1));
            int y = wb.y + Math.max(0, (wb.height - totalBlockH) / 2) + rsAscent;

            for (String row : rows)
            {
                int wpx = measureMixedWidth(row, rsFM, cyrFM);
                int x = wb.x + Math.max(0, (wb.width - wpx) / 2);
                drawMixed(g, row, x, y, rsFont, cyrFont, rsFM, cyrFM);
                y += lineHeight + 1;
                if (y > wb.y + wb.height + 1) break;
            }
            return;
        }

        // Generic fallback (options, headers, etc.)
        List<String> lines = wrapToWidth(translated, Math.max(1, wb.width), rsFM, cyrFM);
        int n = lines.size();
        int totalH = n * lineHeight;
        int startY = wb.y + Math.max(0, (wb.height - totalH) / 2) + rsAscent;
        int y = startY;

        for (String row : lines)
        {
            int wpx = measureMixedWidth(row, rsFM, cyrFM);
            int x = wb.x + Math.max(0, (wb.width - wpx) / 2);
            drawMixed(g, row, x, y, rsFont, cyrFont, rsFM, cyrFM);
            y += lineHeight;
            if (y > wb.y + wb.height + 1) break;
        }
    }
}
