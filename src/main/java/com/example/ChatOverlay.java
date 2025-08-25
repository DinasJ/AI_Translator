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

import javax.inject.Inject;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.InputStream;
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

    // Instrumentation: when setTranslation is called we record the timestamp (ms)
    private final Map<Integer, Long> translationSetMs = new ConcurrentHashMap<>();

    // Optional cache of pre-rendered translation images (to speed repeated draws)
    private final Map<Integer, BufferedImage> translationImageById = new ConcurrentHashMap<>();
    private static final float SCALE = 1f;
    private static final int CENTERED_LINE_GAP = 1;

    // Path inside resources for your custom font (place file at src/main/resources/fonts/MyFont.ttf)
    private static final String CUSTOM_FONT_RESOURCE = "/fonts/Runescape-Quill-8-ru.ttf";
    // Cached base font (unscaled). If loading fails, will fallback to Arial.
    private Font customBaseFont;

    @Inject
    public ChatOverlay(Client client, SpriteManager spriteManager)
    {
        this.client = client;
        this.spriteManager = spriteManager;

        setPosition(OverlayPosition.DYNAMIC);
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
            // Remove translation and its timestamp and cached image
            translatedById.remove(widgetId);
            translationSetMs.remove(widgetId);
            translationImageById.remove(widgetId);
        }
        else
        {
            translatedById.put(widgetId, translated);
            translationSetMs.put(widgetId, System.currentTimeMillis());
            // Invalidate any existing cached image so we regenerate next draw
            translationImageById.remove(widgetId);
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
            translationSetMs.remove(id);
            translationImageById.remove(id);
        }
    }

    public void clearAllTranslations()
    {
        sourceWidgets = Collections.emptyList();
        translatedById.clear();
        lastSeenTickById.clear();
        translationSetMs.clear();
        translationImageById.clear();
        log.debug("clearing all translations");
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (DEBUG) log.debug("[ChatOverlay.render] sourceWidgets={} translatedIds={}", sourceWidgets.size(), translatedById.size());
        Widget chatBgParent = client.getWidget(162, 36);
        if (chatBgParent == null || chatBgParent.isHidden())
        {
            if (DEBUG) log.debug("[ChatOverlay.render] chatBgParent missing/hidden");
            return null;
        }
        Widget chatBg = chatBgParent.getChild(0);
        if (chatBg == null || chatBg.isHidden())
        {
            if (DEBUG) log.debug("[ChatOverlay.render] chatBg missing/hidden");
            return null;
        }

        BufferedImage bg = spriteManager.getSprite(chatBg.getSpriteId(), 0);
        if (bg == null)
        {
            return null;
        }

        // If options are visible, build a special clip: (text rows) minus (icon rects)
        Widget optContainer = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
        if (optContainer != null && !optContainer.isHidden())
        {
            Widget[] rows = getOptionRows(optContainer);
            if (DEBUG) log.debug("[ChatOverlay.render] options visible containerId={} rows={} hasCombined={}",
                    optContainer.getId(), (rows == null ? 0 : rows.length), translatedById.containsKey(optContainer.getId()));
            if (rows != null && rows.length > 0)
            {
                java.util.List<Rectangle> textRowRects = new java.util.ArrayList<>();
                java.util.List<Widget> ordered = new java.util.ArrayList<>();
                for (Widget w : rows) if (w != null && !w.isHidden()) ordered.add(w);
                ordered.sort((a, b) -> {
                    Rectangle ra = a.getBounds(), rb = b.getBounds();
                    int ay = (ra == null ? Integer.MAX_VALUE : ra.y);
                    int by = (rb == null ? Integer.MAX_VALUE : rb.y);
                    if (ay != by) return Integer.compare(ay, by);
                    int ax = (ra == null ? Integer.MAX_VALUE : ra.x);
                    int bx = (rb == null ? Integer.MAX_VALUE : rb.x);
                    return Integer.compare(ax, bx);
                });

                int taken = 0;
                for (Widget r : ordered)
                {
                    String raw = r.getText();
                    String plain = stripTags(raw);
                    if (plain.isEmpty()) continue; // skip icons/empty slots
                    Rectangle b = r.getBounds();
                    if (b != null && b.width > 0 && b.height > 0)
                    {
                        textRowRects.add(b);
                        if (++taken >= 6) break;
                    }
                }

                java.util.List<Rectangle> iconRects = getOptionIconRects(optContainer);

                if (!textRowRects.isEmpty())
                {
                    Shape oldClip = g.getClip();
                    Area clipArea = new Area();
                    for (Rectangle r : textRowRects) clipArea.add(new Area(r));
                    for (Rectangle ir : iconRects) clipArea.subtract(new Area(ir)); // remove icon areas

                    g.setClip(clipArea);

                    Point bgLoc = chatBg.getCanvasLocation();
                    g.drawImage(bg, bgLoc.getX(), bgLoc.getY(), null);

                    g.setClip(oldClip);
                }
            }
        }
        else
        {
            if (DEBUG) log.debug("[ChatOverlay.render] options not visible");
            // Non-option dialogues
            java.util.List<Rectangle> rects = collectTextBounds();
            if (!rects.isEmpty())
            {
                Shape oldClip = g.getClip();
                Area clipArea = new Area();
                for (Rectangle r : rects) clipArea.add(new Area(r));
                g.setClip(clipArea);

                Point bgLoc = chatBg.getCanvasLocation();
                g.drawImage(bg, bgLoc.getX(), bgLoc.getY(), null);

                g.setClip(oldClip);
            }
            else if (DEBUG) {
                log.debug("[ChatOverlay.render] no dialogue rects to clip");
            }
        }

        // Draw per-widget translations (including those inside grace window)
        if (!translatedById.isEmpty())
        {
            drawPerWidget(g);
        }
        else if (DEBUG) {
            log.debug("[ChatOverlay.render] no translated ids to draw");
        }

        return null;
    }

    // Dialogue fallback text bounds (non-options)
    private java.util.List<Rectangle> collectTextBounds()
    {
        java.util.List<Rectangle> rects = new java.util.ArrayList<>();
        addWidgetBounds(rects, client.getWidget(WidgetInfo.DIALOG_NPC_TEXT));
        addWidgetBounds(rects, client.getWidget(WidgetInfo.DIALOG_PLAYER_TEXT));
        addWidgetBounds(rects, client.getWidget(WidgetInfo.DIALOG_NPC_NAME));
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

    private Widget[] getOptionRows(Widget container)
    {
        Widget[] rows = container.getDynamicChildren();
        if (rows == null || rows.length == 0) rows = container.getChildren();
        if (rows == null || rows.length == 0) rows = container.getStaticChildren();
        return rows;
    }

    private void drawPerWidget(Graphics2D g)
    {
        Font rsBase = FontManager.getRunescapeFont();
        Font customBase = getCustomBaseFontOrFallback(rsBase.getSize());
        Font rsFont = rsBase.deriveFont(rsBase.getSize2D() * SCALE);
        Font customFont = customBase.deriveFont(customBase.getSize2D() * SCALE);
        FontMetrics rsFM = g.getFontMetrics(rsFont);
        FontMetrics cyrFM = g.getFontMetrics(customFont);
        int rsAscent = rsFM.getAscent();
        int lineHeight = rsAscent + rsFM.getDescent();

        // 1) Options container special-case: draw combined translation split across TEXT rows only
        Widget optContainer = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
        if (optContainer != null && !optContainer.isHidden())
        {
            String combined = translatedById.get(optContainer.getId());
            BufferedImage optImg = translationImageById.get(optContainer.getId());
            if (optImg != null)
            {
                Point loc = optContainer.getCanvasLocation();
                if (loc != null)
                {
                    g.drawImage(optImg, loc.getX(), loc.getY(), null);

                    if (DEBUG)
                    {
                        Long setMs = translationSetMs.get(optContainer.getId());
                        long drawMs = System.currentTimeMillis();
                        long delta = setMs == null ? -1L : drawMs - setMs;
                        log.debug("[DRAW] optContainer id={} setMs={} drawMs={} deltaMs={} preview='{}'",
                                optContainer.getId(), setMs, drawMs, delta, (combined == null ? "" : (combined.length() > 80 ? combined.substring(0, 77) + "..." : combined)));
                    }
                }
                // already drew pre-rendered image
            }
            else
            {
                // Attempt to lazily create pre-rendered image for the options combined text so subsequent draws are faster.
                if (combined != null && !combined.isEmpty())
                {
                    BufferedImage created = createOptionsImage(optContainer, combined, rsFont, customFont, rsFM, cyrFM, lineHeight);
                    if (created != null)
                    {
                        translationImageById.put(optContainer.getId(), created);
                        // draw immediately
                        Point loc = optContainer.getCanvasLocation();
                        if (loc != null) g.drawImage(created, loc.getX(), loc.getY(), null);
                    }
                    else
                    {
                        // fallback to previous per-row direct drawing if image creation failed
                        Widget[] rowArr = getOptionRows(optContainer);
                        if (rowArr != null && rowArr.length > 0)
                        {
                            List<Widget> textRows = new ArrayList<>();
                            for (Widget w : rowArr)
                            {
                                if (w == null || w.isHidden()) continue;
                                String raw = w.getText();
                                if (raw == null || stripTags(raw).trim().isEmpty()) continue; // skip icon rows
                                textRows.add(w);
                            }
                            textRows.sort((a, b) -> {
                                Rectangle ra = a.getBounds(), rb = b.getBounds();
                                int ay = (ra == null ? Integer.MAX_VALUE : ra.y);
                                int by = (rb == null ? Integer.MAX_VALUE : rb.y);
                                if (ay != by) return Integer.compare(ay, by);
                                int ax = (ra == null ? Integer.MAX_VALUE : ra.x);
                                int bx = (rb == null ? Integer.MAX_VALUE : rb.x);
                                return Integer.compare(ax, bx);
                            });

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
                                for (int i = 0; i < Math.min(count, 6); i++)
                                {
                                    Widget r = textRows.get(i);
                                    Rectangle rb = r.getBounds();
                                    if (rb == null || rb.isEmpty()) continue;

                                    java.awt.Color color;
                                    try { color = new java.awt.Color(r.getTextColor()); } catch (Exception e) { color = java.awt.Color.WHITE; }
                                    g.setColor(color);

                                    String text = lines[i].trim();
                                    int wpx = measureMixedWidth(text, rsFM, cyrFM);
                                    int x = rb.x + Math.max(0, (rb.width - wpx) / 2);
                                    int y = rb.y + Math.max(0, (rb.height - lineHeight) / 2) + rsAscent;

                                    drawMixed(g, text, x, y, rsFont, customFont, rsFM, cyrFM);
                                }

                                g.setClip(oldClip);
                            }
                        }
                    }
                }
            }
        }

        // 2) Regular per-widget drawing for everything else
        for (Widget w : sourceWidgets)
        {
            if (w == null || w.isHidden()) continue;

            int id = w.getId();
            int grp = (id >>> 16), child = (id & 0xFFFF);

            // Skip the options container here to avoid double drawing; it was handled above
            if (grp == 219 && child == 1) continue;

            String translated = translatedById.get(id);
            if (translated == null || translated.isEmpty()) continue;

            Rectangle wb = w.getBounds();
            if (wb == null || wb.isEmpty()) continue;

            // Fast path: if we have a pre-rendered image, blit it and continue
            BufferedImage img = translationImageById.get(id);
            if (img != null)
            {
                Point loc = w.getCanvasLocation();
                if (loc != null)
                {
                    g.drawImage(img, loc.getX(), loc.getY(), null);

                    if (DEBUG)
                    {
                        Long setMs = translationSetMs.get(id);
                        long drawMs = System.currentTimeMillis();
                        long delta = setMs == null ? -1L : drawMs - setMs;
                        String preview = translated.length() > 80 ? translated.substring(0, 77) + "..." : translated;
                        log.debug("[DRAW] id={} setMs={} drawMs={} deltaMs={} preview='{}' (blit image)", id, setMs, drawMs, delta, preview);
                    }
                }

                continue; // already drawn via image
            }

            // No cached image yet — create one lazily and cache it
            BufferedImage created = createPerWidgetImage(w, translated, rsFont, customFont, rsFM, cyrFM, lineHeight);
            if (created != null)
            {
                translationImageById.put(id, created);
                Point loc = w.getCanvasLocation();
                if (loc != null)
                {
                    g.drawImage(created, loc.getX(), loc.getY(), null);
                }

                if (DEBUG)
                {
                    Long setMs = translationSetMs.get(id);
                    long drawMs = System.currentTimeMillis();
                    long delta = setMs == null ? -1L : drawMs - setMs;
                    String preview = translated.length() > 80 ? translated.substring(0, 77) + "..." : translated;
                    log.debug("[DRAW] id={} setMs={} drawMs={} deltaMs={} preview='{}' (created image)", id, setMs, drawMs, delta, preview);
                }

                continue;
            }

            // Fallback (shouldn't normally be hit because createPerWidgetImage tries to handle everything)
            Rectangle clipRect = new Rectangle(wb.x, wb.y, wb.width, wb.height + 2);
            Shape oldClip = g.getClip();
            g.setClip(clipRect);

            java.awt.Color color;
            try { color = new java.awt.Color(w.getTextColor()); } catch (Exception e) { color = java.awt.Color.WHITE; }
            g.setColor(color);

            int rsAscentLocal = rsFM.getAscent();

            boolean isLeftName     = grp == 231 && child == 4;
            boolean isLeftText     = grp == 231 && child == 6;
            boolean isLeftContinue = grp == 231 && child == 5;
            boolean isRightName     = grp == 217 && child == 4;
            boolean isRightText     = grp == 217 && child == 6;
            boolean isRightContinue = grp == 217 && child == 5;

            boolean isName = isLeftName || isRightName;
            boolean isText = isLeftText || isRightText;
            boolean isContinue = isLeftContinue || isRightContinue;

            if (isName || isContinue)
            {
                String row = translated.trim();
                int wpx = measureMixedWidth(row, rsFM, cyrFM);
                int x = wb.x + Math.max(0, (wb.width - wpx) / 2);
                int y = wb.y + Math.max(0, (wb.height - lineHeight) / 2) + rsAscentLocal;
                drawMixed(g, row, x, y, rsFont, customFont, rsFM, cyrFM);
            }
            else if (isText)
            {
                List<String> rows = wrapToWidth(translated, Math.max(1, wb.width), rsFM, cyrFM);
                int n = rows.size();
                int totalBlockH = n * lineHeight + Math.max(0, (n - 1) * CENTERED_LINE_GAP);
                int y = wb.y + Math.max(0, (wb.height - totalBlockH) / 2) + rsAscentLocal;

                for (int i = 0; i < rows.size(); i++)
                {
                    String row = rows.get(i);
                    int wpx = measureMixedWidth(row, rsFM, cyrFM);
                    int x = wb.x + Math.max(0, (wb.width - wpx) / 2);
                    drawMixed(g, row, x, y, rsFont, customFont, rsFM, cyrFM);
                    y += lineHeight + CENTERED_LINE_GAP;
                    if (y > wb.y + wb.height + 1) break;
                }
            }
            else
            {
                List<String> rows = wrapToWidth(translated, Math.max(1, wb.width), rsFM, cyrFM);
                int n = rows.size();
                int totalBlockH = n * lineHeight;
                int y = wb.y + Math.max(0, (wb.height - totalBlockH) / 2) + rsAscentLocal;
                int x = (w.getCanvasLocation() != null ? w.getCanvasLocation().getX() : wb.x);
                for (String row : rows)
                {
                    drawMixed(g, row, x, y, rsFont, customFont, rsFM, cyrFM);
                    y += lineHeight;
                    if (y > wb.y + wb.height + 1) break;
                }
            }

            if (DEBUG)
            {
                Long setMs = translationSetMs.get(id);
                long drawMs = System.currentTimeMillis();
                long delta = setMs == null ? -1L : drawMs - setMs;
                String preview = translated.length() > 80 ? translated.substring(0, 77) + "..." : translated;
                log.debug("[DRAW] id={} setMs={} drawMs={} deltaMs={} preview='{}'", id, setMs, drawMs, delta, preview);
            }

            g.setClip(oldClip);
        }
    }

    // Create a pre-rendered image for a single widget's translated text.
    // Returns the created BufferedImage or null on failure.
    private BufferedImage createPerWidgetImage(Widget w, String translated, Font rsFont, Font cyrFont, FontMetrics rsFM, FontMetrics cyrFM, int lineHeight)
    {
        if (w == null || translated == null) return null;
        Rectangle wb = w.getBounds();
        if (wb == null || wb.width <= 0 || wb.height <= 0) return null;

        int width = Math.max(1, wb.width);
        int height = Math.max(1, wb.height + 2);

        try
        {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D ig = img.createGraphics();
            try
            {
                // decent quality
                ig.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                // Determine text layout based on widget role (centered names / multi-line body / fallback)
                int id = w.getId();
                int grp = (id >>> 16), child = (id & 0xFFFF);

                boolean isLeftName     = grp == 231 && child == 4;
                boolean isLeftText     = grp == 231 && child == 6;
                boolean isLeftContinue = grp == 231 && child == 5;
                boolean isRightName     = grp == 217 && child == 4;
                boolean isRightText     = grp == 217 && child == 6;
                boolean isRightContinue = grp == 217 && child == 5;

                boolean isName = isLeftName || isRightName;
                boolean isText = isLeftText || isRightText;
                boolean isContinue = isLeftContinue || isRightContinue;

                // Use the image's graphics font metrics to draw (more accurate inside image)
                FontMetrics rsFMImg = ig.getFontMetrics(rsFont);
                FontMetrics cyrFMImg = ig.getFontMetrics(cyrFont);
                int rsAscentImg = rsFMImg.getAscent();
                int localLineHeight = rsAscentImg + rsFMImg.getDescent();

                ig.setColor(new java.awt.Color(0,0,0,0)); // transparent background
                ig.fillRect(0, 0, width, height);

                // prepare color; use white text by default (actual widget color will be used when drawing over image)
                ig.setColor(java.awt.Color.WHITE);

                if (isName || isContinue)
                {
                    String row = translated.trim();
                    int wpx = measureMixedWidth(row, rsFMImg, cyrFMImg);
                    int x = Math.max(0, (width - wpx) / 2);
                    int y = Math.max(0, (height - localLineHeight) / 2) + rsAscentImg;
                    drawMixed(ig, row, x, y, rsFont, cyrFont, rsFMImg, cyrFMImg);
                }
                else if (isText)
                {
                    List<String> rows = wrapToWidth(translated, width, rsFMImg, cyrFMImg);
                    int n = rows.size();
                    int totalBlockH = n * localLineHeight + Math.max(0, (n - 1) * CENTERED_LINE_GAP);
                    int y = Math.max(0, (height - totalBlockH) / 2) + rsAscentImg;

                    for (int i = 0; i < rows.size(); i++)
                    {
                        String row = rows.get(i);
                        int wpx = measureMixedWidth(row, rsFMImg, cyrFMImg);
                        int x = Math.max(0, (width - wpx) / 2);
                        drawMixed(ig, row, x, y, rsFont, cyrFont, rsFMImg, cyrFMImg);
                        y += localLineHeight + CENTERED_LINE_GAP;
                        if (y > height + 1) break;
                    }
                }
                else
                {
                    List<String> rows = wrapToWidth(translated, width, rsFMImg, cyrFMImg);
                    int n = rows.size();
                    int totalBlockH = n * localLineHeight;
                    int y = Math.max(0, (height - totalBlockH) / 2) + rsAscentImg;
                    int x = 0;
                    for (String row : rows)
                    {
                        drawMixed(ig, row, x, y, rsFont, cyrFont, rsFMImg, cyrFMImg);
                        y += localLineHeight;
                        if (y > height + 1) break;
                    }
                }

                return img;
            }
            finally
            {
                ig.dispose();
            }
        }
        catch (Throwable t)
        {
            if (DEBUG) log.debug("Failed to create per-widget image id={} err={}", w.getId(), t.toString());
            return null;
        }
    }

    // Create an image that covers the union of option TEXT row rects and draws the combined translation lines into it.
    private BufferedImage createOptionsImage(Widget optContainer, String combined, Font rsFont, Font cyrFont, FontMetrics rsFM, FontMetrics cyrFM, int lineHeight)
    {
        Widget[] rowArr = getOptionRows(optContainer);
        if (rowArr == null || rowArr.length == 0) return null;

        List<Widget> textRows = new ArrayList<>();
        for (Widget r : rowArr)
        {
            if (r == null || r.isHidden()) continue;
            String raw = r.getText();
            if (raw == null || stripTags(raw).trim().isEmpty()) continue;
            textRows.add(r);
        }
        if (textRows.isEmpty()) return null;

        // Determine union bounds of the text rows (limit to 6 rows like before)
        Rectangle union = null;
        List<Rectangle> rowRects = new ArrayList<>();
        for (int i = 0; i < Math.min(textRows.size(), 6); i++)
        {
            Rectangle rb = textRows.get(i).getBounds();
            if (rb == null || rb.width <= 0 || rb.height <= 0) continue;
            rowRects.add(rb);
            if (union == null) union = new Rectangle(rb);
            else union = union.union(rb);
        }
        if (union == null) return null;

        // Subtract icons (we will keep transparency in icon areas; not drawing them)
        List<Rectangle> icons = getOptionIconRects(optContainer);

        int width = Math.max(1, union.width);
        int height = Math.max(1, union.height);

        try
        {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D ig = img.createGraphics();
            try
            {
                ig.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                ig.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                FontMetrics rsFMImg = ig.getFontMetrics(rsFont);
                FontMetrics cyrFMImg = ig.getFontMetrics(cyrFont);
                int rsAscentImg = rsFMImg.getAscent();
                int localLineHeight = rsAscentImg + rsFMImg.getDescent();

                // Transparent background
                ig.setColor(new java.awt.Color(0,0,0,0));
                ig.fillRect(0, 0, width, height);

                String[] lines = combined.split("\\R", -1);
                int count = Math.min(lines.length, rowRects.size());
                for (int i = 0; i < count; i++)
                {
                    Rectangle rb = rowRects.get(i);
                    if (rb == null) continue;
                    // compute local coords inside union
                    int localX = rb.x - union.x;
                    int localY = rb.y - union.y;

                    String text = lines[i].trim();
                    int wpx = measureMixedWidth(text, rsFMImg, cyrFMImg);
                    int x = localX + Math.max(0, (rb.width - wpx) / 2);
                    int y = localY + Math.max(0, (rb.height - localLineHeight) / 2) + rsAscentImg;

                    drawMixed(ig, text, x, y, rsFont, cyrFont, rsFMImg, cyrFMImg);
                }

                // We leave icon areas transparent; caller will blit this image at union.x/union.y
                return img;
            }
            finally
            {
                ig.dispose();
            }
        }
        catch (Throwable t)
        {
            if (DEBUG) log.debug("Failed to create options image id={} err={}", optContainer.getId(), t.toString());
            return null;
        }
    }

    // Collect rectangles of icon widgets inside the options container
    private java.util.List<Rectangle> getOptionIconRects(Widget optContainer)
    {
        java.util.List<Rectangle> icons = new java.util.ArrayList<>();
        Widget[] all = getOptionRows(optContainer);
        if (all == null) return icons;

        for (Widget w : all)
        {
            if (w == null || w.isHidden()) continue;

            boolean looksLikeIcon = false;
            try
            {
                int spriteId = w.getSpriteId();
                looksLikeIcon = spriteId != -1;
            }
            catch (Throwable ignored)
            {
                String raw = w.getText();
                looksLikeIcon = (raw == null || stripTags(raw).trim().isEmpty());
            }

            if (!looksLikeIcon) continue;

            Rectangle b = w.getBounds();
            if (b != null && b.width > 0 && b.height > 0)
            {
                icons.add(b);
            }
        }
        return icons;
    }

    private String stripTags(String str)
    {
        if (str == null) return "";
        return str.replaceAll("<[^>]*>", "").trim();
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

    /**
     * Measure width of mixed text using cyrillic font metrics for Cyrillic letters and punctuation,
     * and the runescape font metrics for (Latin) letters and digits.
     */
    private int measureMixedWidth(String text, FontMetrics rsFM, FontMetrics cyrFM)
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
                // default to runescape font for latin/other letters
                w += rsFM.charWidth(c);
            }
        }
        return w;
    }

    private static boolean isCyrillic(char c)
    {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CYRILLIC
                || b == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY;
    }

    /**
     * Return true for punctuation characters (so we render them using the custom/ Cyrillic font).
     * Uses Character.getType to cover all Unicode punctuation categories.
     */
    private static boolean isPunctuation(char c)
    {
        int t = Character.getType(c);
        return t == Character.CONNECTOR_PUNCTUATION
                || t == Character.DASH_PUNCTUATION
                || t == Character.START_PUNCTUATION
                || t == Character.END_PUNCTUATION
                || t == Character.INITIAL_QUOTE_PUNCTUATION
                || t == Character.FINAL_QUOTE_PUNCTUATION
                || t == Character.OTHER_PUNCTUATION;
    }
    /**
     * Keep digits detection separate (digits will still be drawn with rsFont).
     */
    private static boolean isDigitChar(char c)
    {
        return Character.isDigit(c);
    }

    private void drawMixed(Graphics2D g, String text, int x, int y,
                           Font rsFont, Font cyrFont, FontMetrics rsFM, FontMetrics cyrFM)
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
                // default: latin/other -> runescape font
                g.setFont(rsFont);
                g.drawString(String.valueOf(c), x, y);
                x += rsFM.charWidth(c);
            }
        }
    }

    private Font getCustomBaseFontOrFallback(int targetSize)
    {
        if (customBaseFont != null) return customBaseFont;

        // Try load bundled font
        try (InputStream is = ChatOverlay.class.getResourceAsStream(CUSTOM_FONT_RESOURCE))
        {
            if (is != null)
            {
                Font raw = Font.createFont(Font.TRUETYPE_FONT, is);
                customBaseFont = raw.deriveFont(Font.PLAIN, (float) targetSize);
                if (DEBUG) log.info("Loaded custom font from resource: {}", CUSTOM_FONT_RESOURCE);
                return customBaseFont;
            }
            else
            {
                if (DEBUG) log.warn("Custom font resource not found: {}", CUSTOM_FONT_RESOURCE);
            }
        }
        catch (Exception e)
        {
            if (DEBUG) log.warn("Failed to load custom font '{}': {}", CUSTOM_FONT_RESOURCE, e.toString());
        }

        // Fallback
        customBaseFont = new Font("Arial", Font.PLAIN, targetSize);
        return customBaseFont;
    }
}
