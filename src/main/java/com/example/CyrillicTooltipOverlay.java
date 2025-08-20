package com.example;

import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;

public class CyrillicTooltipOverlay extends Overlay
{
    private static final Logger log = LoggerFactory.getLogger(CyrillicTooltipOverlay.class);

    // Appearance
    private static final int PADDING_LEFT   = 2;
    private static final int PADDING_RIGHT  = 2;
    private static final int PADDING_TOP    = 1;
    private static final int PADDING_BOTTOM = 6;

    private static final int LINE_HEIGHT_TWEAK = -2; // keep tight like vanilla
    private static final int LINE_GAP          = -2;
    private static final int MIN_COL_GAP       = 6;

    // Fonts / metrics tweaks
    private static final String CYR_FAMILY = "Arial";
    private static final float  CYR_SCALE  = 0.80f;  // Cyrillic 10% smaller
    private static final int    CYR_BASELINE_UP = 2; // lift Cyrillic a bit
    private static final int    DIGIT_BASELINE_DOWN = 0; // lower numbers/punct a touch
    private static final int    CYR_TRACK = 0; // +1px letter-spacing for Cyrillic

    private String text = "";
    private boolean visible = false;

    // Anchor is the top-left of vanilla tooltip (320,28)
    private int anchorX, anchorY;

    // Bounding rectangle from (320,0)
    private int boundX, boundY, boundW, boundH;

    public CyrillicTooltipOverlay()
    {
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    public void setText(String text) { this.text = text != null ? text : ""; }
    public boolean isVisible() { return visible; }
    public void setVisible(boolean v) { this.visible = v; }

    public void updateAnchor(int x, int y)
    {
        this.anchorX = x;
        this.anchorY = y;
    }

    public void updateBounds(int x, int y, int w, int h)
    {
        this.boundX = x;
        this.boundY = y;
        this.boundW = w;
        this.boundH = h;
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!visible || text == null || text.isEmpty())
            return null;

        // Fonts
        Font rsFont  = FontManager.getRunescapeFont();
        Font cyrFont = new Font(CYR_FAMILY, Font.PLAIN, Math.round(rsFont.getSize() * CYR_SCALE));

        FontMetrics rsFM  = g.getFontMetrics(rsFont);
        FontMetrics cyrFM = g.getFontMetrics(cyrFont);

        int rsAscent  = rsFM.getAscent();
        int rsDescent = rsFM.getDescent();
        int lineHeight = rsAscent + rsDescent + LINE_HEIGHT_TWEAK;
        if (lineHeight < 1) lineHeight = rsAscent + rsDescent;

        // Parse & pair: left label lines + numeric lines -> rows
        String[] rawLines = text.split("<br>");
        Row[] rows = pairRows(rawLines);
        if (rows.length == 0) return null;

        // Measure widths
        int maxContentWidth = 0;
        for (Row r : rows)
        {
            r.leftW  = measureMixedWidth(g, r.left, rsFM, cyrFM);
            r.rightW = measureMixedWidth(g, r.right, rsFM, cyrFM);
            int total = r.leftW + (r.rightW == 0 ? 0 : MIN_COL_GAP + r.rightW);
            if (total > maxContentWidth) maxContentWidth = total;
        }

        // Initial placement (aligned to vanilla tooltip 320,28)
        int contentX      = anchorX;
        int contentTopY   = anchorY;
        int textBaselineY = contentTopY + rsAscent;

        int bgX = contentX - PADDING_LEFT;
        int bgY = contentTopY - PADDING_TOP;
        int bgW = PADDING_LEFT + maxContentWidth + PADDING_RIGHT;
        int bgH = PADDING_TOP + (lineHeight + LINE_GAP) * rows.length + PADDING_BOTTOM;

        // Clamp into boundary (320,0)
        int shiftX = 0, shiftY = 0;

        if (bgX < boundX) { shiftX = boundX - bgX; }
        else if (bgX + bgW > boundX + boundW) { shiftX = (boundX + boundW) - (bgX + bgW); }

        if (bgY < boundY) { shiftY = boundY - bgY; }
        else if (bgY + bgH > boundY + boundH) { shiftY = (boundY + boundH) - (bgY + bgH); }

        if (shiftX != 0 || shiftY != 0)
        {
            bgX += shiftX; bgY += shiftY;
            contentX += shiftX; contentTopY += shiftY; textBaselineY += shiftY;
            log.info("Tooltip clamped by ({}, {}) to fit bounds", shiftX, shiftY);
        }

        // Background
        g.setColor(new Color(255, 255, 160));
        g.fillRect(bgX, bgY, bgW, bgH);

        // Border
        g.setColor(Color.BLACK);
        g.drawRect(bgX, bgY, bgW - 1, bgH - 1);

        // Text
        int y = textBaselineY;
        for (Row r : rows)
        {
            int leftStart  = contentX;
            int leftEnd    = leftStart + r.leftW;
            int rightStart = bgX + bgW - PADDING_RIGHT - r.rightW;

            if (r.rightW > 0 && rightStart < leftEnd + MIN_COL_GAP)
                rightStart = leftEnd + MIN_COL_GAP;

            drawMixed(g, r.left, leftStart,  y, rsFont, cyrFont, rsFM, cyrFM);
            if (!r.right.isEmpty())
                drawMixed(g, r.right, rightStart, y, rsFont, cyrFont, rsFM, cyrFM);

            y += lineHeight + LINE_GAP;
        }

        return new Dimension(bgW, bgH);
    }

    // ---------- layout helpers ----------

    private static class Row {
        String left = "";
        String right = "";
        int leftW, rightW;
    }

    private Row[] pairRows(String[] lines)
    {
        // The skills tooltip typically lists labels (ending with :) followed by numeric lines.
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<String> values = new java.util.ArrayList<>();

        for (String raw : lines)
        {
            if (raw == null) continue;
            String s = raw.trim();
            if (s.isEmpty()) continue;

            if (isNumericLike(s))
            {
                values.add(s);
            }
            else
            {
                // Keep colon if present; otherwise treat whole as label
                labels.add(s);
            }
        }

        int n = Math.max(labels.size(), values.size());
        java.util.List<Row> out = new java.util.ArrayList<>(n);

        for (int i = 0; i < n; i++)
        {
            Row r = new Row();
            if (i < labels.size()) r.left  = labels.get(i);
            if (i < values.size()) r.right = values.get(i);
            out.add(r);
        }
        return out.toArray(new Row[0]);
    }

    private boolean isCyrillic(char c)
    {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CYRILLIC
                || b == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY;
    }

    private boolean isDigitOrPunct(char c)
    {
        if (Character.isDigit(c)) return true;
        // ASCII punctuation
        return "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~".indexOf(c) >= 0;
    }

    private static boolean isNumericLike(String s)
    {
        if (s == null) return false;
        String compact = s.replace(",", "").replace(" ", "");
        return compact.matches("^[-+]?\\d+(\\.\\d+)?%?$");
    }

    private int measureMixedWidth(Graphics2D g, String text, FontMetrics rsFM, FontMetrics cyrFM)
    {
        if (text == null || text.isEmpty()) return 0;
        int w = 0;
        for (char c : text.toCharArray())
        {
            if (isCyrillic(c)) {
                w += cyrFM.charWidth(c) + CYR_TRACK;
            } else {
                w += rsFM.charWidth(c);
            }
        }
        return w;
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
                g.drawString(String.valueOf(c), x, y - CYR_BASELINE_UP);
                x += cyrFM.charWidth(c) + CYR_TRACK;
            }
            else if (isDigitOrPunct(c))
            {
                g.setFont(rsFont);
                g.drawString(String.valueOf(c), x, y + DIGIT_BASELINE_DOWN);
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