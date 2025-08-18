package com.example;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.ClientTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Overlay that renders translated right-click tooltip text ON TOP of the vanilla tooltip.
 * - Action text is prefixed with "To " before translation.
 * - Action is white (yellow on hover); target is white.
 * - Player targets are never sent for translation.
 * - Translations use AITranslatorConfig.targetLang.
 * - Includes log.info statements for debugging flow.
 */
@Slf4j
public class ContextMenuOverlay extends Overlay
{
    private final Client client;
    private final DeepLTranslator translator;
    private final AITranslatorConfig config;

    // Per-source caches (keyed by EXACT text sent to translation)
    private final Map<String, String> actionCache = new ConcurrentHashMap<>();
    private final Map<String, String> targetCache = new ConcurrentHashMap<>();

    // Cache for translating only the "(level-XX)" tail on player targets
    private final Map<String, String> levelCache = new ConcurrentHashMap<>();
    private static final Pattern LEVEL_TAIL = Pattern.compile("(?i)\\(\\s*level\\s*-\\s*\\d+\\s*\\)");

    // Track texts currently being translated to avoid duplicate parallel requests
    private final java.util.Set<String> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Tiny token bucket to rate-limit requests (prevents 429 bursts)
    private static final int TOKENS_MAX = 4;      // up to ~4 requests in a burst
    private static final long REFILL_MS = 300L;   // ~3.3 req/s steady
    private int tokens = TOKENS_MAX;
    private long lastRefillMs = System.currentTimeMillis();

    // Last snapshot of menu entries (preserving order)
    private volatile MenuEntry[] lastEntries = new MenuEntry[0];

    @Inject
    public ContextMenuOverlay(Client client, DeepLTranslator translator, AITranslatorConfig config)
    {
        this.client = client;
        this.translator = translator;
        this.config = config;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
        setPriority(OverlayPriority.HIGHEST);
    }


    // Track open/close transitions
    private boolean menuWasOpen = false;

    @Subscribe
    public void onClientTick(ClientTick tick)
    {
        boolean open = client.isMenuOpen();
        if (!open && menuWasOpen)
        {
            // Menu has just closed — clear the snapshot so we don't render stale data
            lastEntries = new MenuEntry[0];
            // Keep caches; they'll speed up next openings
            log.info("[CM] Menu closed: cleared snapshot");
        }
        menuWasOpen = open;
    }

    @Subscribe
    public void onMenuEntryAdded(net.runelite.api.events.MenuEntryAdded event)
    {
        // Let the menu finish building; we snapshot on MenuOpened.
    }

    @Subscribe
    public void onMenuOpened(MenuOpened event)
    {
        // Stable list at open-time → queue translations once
        MenuEntry[] all = client.getMenuEntries();
        if (all == null) all = new MenuEntry[0];
        lastEntries = Arrays.stream(all).filter(Objects::nonNull).toArray(MenuEntry[]::new);
        requestTranslationsFor(lastEntries);
        menuWasOpen = true; // record open state
        log.info("[CM] onMenuOpened: snap entries={}", lastEntries.length);
    }
    // Queue translations for action (option) and target (skip players). No "To " prefix.
    private void requestTranslationsFor(MenuEntry[] entries)
    {
        if (entries == null || entries.length == 0) return;

        final String lang = safeLang(config.targetLang());

        for (MenuEntry e : entries)
        {
            if (e == null) continue;

            String option = safe(e.getOption()).trim();
            String target = safe(e.getTarget()).trim();
            MenuAction type = e.getType();

            // ACTION: translate the normalized option as-is
            String optionPlain = normalizeAction(option);
            if (!optionPlain.isEmpty())
            {
                String actionSrc = optionPlain;
                if (!actionCache.containsKey(actionSrc))
                {
                    guardedTranslate(actionSrc, lang, true);
                }
            }

            boolean isPlayer = isPlayerAction(type);
            if (isPlayer)
            {
                // For players: don't translate the name; translate only the "(level-XX)" tail
                String levelPlain = extractLevelPlain(target);
                if (levelPlain != null && !levelCache.containsKey(levelPlain))
                {
                    String inFlightKey = "L|" + levelPlain;
                    if (inFlight.add(inFlightKey))
                    {
                        if (tryAcquireToken())
                        {
                            translator.translateAsync(levelPlain, lang, out -> {
                                try {
                                    String res = (out == null || out.isEmpty()) ? levelPlain : out;
                                    levelCache.put(levelPlain, res);
                                    log.info("[CM] LEVEL translated: '{}' -> '{}'", levelPlain, res);
                                } finally {
                                    inFlight.remove(inFlightKey);
                                }
                            });
                        }
                        else
                        {
                            inFlight.remove(inFlightKey);
                        }
                    }
                }
            }
            else
            {
                // Non-player targets: translate whole (clean) target, color locally
                if (!target.isEmpty() && !targetCache.containsKey(target))
                {
                    translateTargetWithoutTags(target, lang);
                }
            }
        }
    }

    // Strip <col> tags and extract "(level-XX)" tail if present
    private static String extractLevelPlain(String originalTagged)
    {
        String clean = stripColTags(originalTagged);
        if (clean.isEmpty()) return null;
        Matcher m = LEVEL_TAIL.matcher(clean);
        return m.find() ? m.group() : null;
    }

    // Send target text without <col> tags to DeepL and then store PLAIN result.
    // We will apply colors locally at render time based on the original target's tags.
    private void translateTargetWithoutTags(String originalTagged, String lang)
    {
        String clean = stripColTags(originalTagged);
        if (clean.isEmpty())
        {
            targetCache.put(originalTagged, clean);
            return;
        }

        String inFlightKey = "T|" + clean;
        if (!inFlight.add(inFlightKey))
        {
            log.info("[CM] piggyback in-flight(clean target): '{}'", clean);
            return;
        }

        if (!tryAcquireToken())
        {
            log.info("[CM] rate-limited; deferring target this tick: '{}'", clean);
            inFlight.remove(inFlightKey);
            return;
        }

        translator.translateAsync(clean, lang, out -> {
            try
            {
                String plain = (out == null || out.isEmpty()) ? clean : out;
                // Store PLAIN text (no <col>), colors will be applied locally in render()
                targetCache.put(originalTagged, plain);
                log.info("[CM] TARGET translated(clean->plain): '{}' -> '{}'", originalTagged, plain);
            }
            finally
            {
                inFlight.remove(inFlightKey);
            }
        });
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!client.isMenuOpen())
        {
            return null;
        }

        final int x = client.getMenuX();
        final int y = client.getMenuY();
        final int w = client.getMenuWidth();
        final int h = client.getMenuHeight();

        if (w <= 0 || h <= 0)
        {
            return null;
        }

        // Opaque background
        g.setColor(new Color(0, 0, 0, 255));
        g.fillRect(x, y, w, h);

        Font font = FontManager.getRunescapeFont();
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics(font);
        int lineHeight = fm.getHeight();

        Point mp = client.getMouseCanvasPosition();
        int hoveredIndex = -1;
        if (mp != null)
        {
            int relY = mp.getY() - y - 19;
            if (relY >= 0)
            {
                hoveredIndex = relY / lineHeight;
            }
        }

        int baseY = y + 19 + fm.getAscent();

        // Render in the same visual order as the tooltip:
        for (int line = 0; line < lastEntries.length; line++)
        {
            int idx = lastEntries.length - 1 - line;
            MenuEntry e = lastEntries[idx];
            if (e == null) continue;

            String option = safe(e.getOption()).trim();
            String optionPlain = normalizeAction(option);
            String actionSrc = optionPlain;

            String targetTagged = safe(e.getTarget()).trim();
            MenuAction type = e.getType();
            boolean isPlayer = isPlayerAction(type);

            String actionTranslated = actionSrc.isEmpty() ? "" : actionCache.getOrDefault(actionSrc, actionSrc);

            int lineY = baseY + (line * lineHeight);
            boolean hovered = (line == hoveredIndex);
            Color actionColor = hovered ? new Color(255, 255, 0) : Color.WHITE;
            int cursorX = x + 6;

            if (!actionTranslated.isEmpty())
            {
                g.setColor(actionColor);
                g.drawString(actionTranslated, cursorX, lineY);
                cursorX += fm.stringWidth(actionTranslated);
            }

            if (isPlayer)
            {
                // Build runs from original tags (so name keeps its colors)
                java.util.List<Run> runs = parseTaggedRuns(targetTagged, Color.WHITE);

                // Translate only the "(level-XX)" substring(s) inside the runs
                for (Run r : runs)
                {
                    if (r.text == null || r.text.isEmpty()) continue;
                    Matcher m = LEVEL_TAIL.matcher(r.text);
                    if (m.find())
                    {
                        String lvl = m.group();
                        String tr = levelCache.get(lvl);
                        if (tr != null && !tr.isEmpty())
                        {
                            // Replace all occurrences of the level tail in this run
                            r = new Run(m.replaceAll(tr), r.color);
                        }
                    }
                    g.setColor(r.color != null ? r.color : Color.WHITE);
                    g.drawString(r.text, cursorX, lineY);
                    cursorX += fm.stringWidth(r.text);
                }
            }
            else
            {
                // Non-player: use plain translated target and color from first tag
                String targetPlain = targetCache.getOrDefault(targetTagged, stripColTags(targetTagged));
                if (targetPlain != null && !targetPlain.isEmpty())
                {
                    Color firstColor = firstColorFromTagged(targetTagged, Color.WHITE);
                    g.setColor(firstColor);
                    g.drawString(targetPlain, cursorX, lineY);
                    cursorX += fm.stringWidth(targetPlain);
                }
            }
        }

        return new Dimension(w, h);
    }

    // A text+color run extracted from the target’s color tags
    private static class Run
    {
        final String text;
        final Color color;
        Run(String text, Color color) { this.text = text; this.color = color; }
    }

    // Parse <col=...> runs; tolerates quotes, #, 0x prefixes, and whitespace.
    private static java.util.List<Run> parseTaggedRuns(String input, Color defaultColor)
    {
        java.util.List<Run> runs = new ArrayList<>();
        if (input == null || input.isEmpty())
        {
            return runs;
        }

        Color cur = defaultColor != null ? defaultColor : Color.WHITE;
        String s = input;

        int idx = 0;
        StringBuilder buf = new StringBuilder();

        while (idx < s.length())
        {
            int open = s.indexOf('<', idx);
            if (open < 0)
            {
                buf.append(s, idx, s.length());
                break;
            }

            if (open > idx) buf.append(s, idx, open);

            int close = s.indexOf('>', open + 1);
            if (close < 0)
            {
                buf.append(s.substring(open));
                break;
            }

            String tagRaw = s.substring(open + 1, close); // e.g., "col=ffff00" or "col='#00ffff'" or "/col"
            String low = tagRaw.trim().toLowerCase(Locale.ROOT);

            // Flush text collected up to this tag
            if (buf.length() > 0)
            {
                runs.add(new Run(buf.toString(), cur));
                buf.setLength(0);
            }

            if (low.startsWith("col"))
            {
                // Handle: "col", "col=", "col=...", with optional quotes and prefixes
                int eq = low.indexOf('=');
                if (eq < 0)
                {
                    // "<col>" without value – treat as reset to default
                    cur = defaultColor != null ? defaultColor : Color.WHITE;
                }
                else
                {
                    String val = tagRaw.substring(eq + 1).trim(); // original case to preserve hex chars
                    // Remove wrapping quotes if present
                    if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'")))
                    {
                        val = val.substring(1, val.length() - 1).trim();
                    }
                    // Strip 0x or # prefixes if present
                    if (val.startsWith("0x") || val.startsWith("0X"))
                    {
                        val = val.substring(2);
                    }
                    if (val.startsWith("#"))
                    {
                        val = val.substring(1);
                    }
                    // Remove any non-hex characters defensively
                    val = val.replaceAll("[^0-9a-fA-F]", "");
                    Color parsed = parseHexColor(val, cur);
                    cur = parsed != null ? parsed : (defaultColor != null ? defaultColor : Color.WHITE);
                }
            }
            else if (low.equals("/col") || low.equalsIgnoreCase("/col"))
            {
                cur = defaultColor != null ? defaultColor : Color.WHITE;
            }
            // ignore other tags

            idx = close + 1;
        }

        if (buf.length() > 0)
        {
            runs.add(new Run(buf.toString(), cur));
        }

        // Merge adjacent same-color runs
        java.util.List<Run> merged = new ArrayList<>();
        for (Run r : runs)
        {
            if (!merged.isEmpty() && colorsEqual(merged.get(merged.size() - 1).color, r.color))
            {
                Run last = merged.remove(merged.size() - 1);
                merged.add(new Run(last.text + r.text, r.color));
            }
            else
            {
                merged.add(r);
            }
        }
        return merged;
    }

    // Robust hex parser for rrggbb/aarrggbb and shorthands.
    private static Color parseHexColor(String hex, Color fallback)
    {
        if (hex == null) return fallback;
        String h = hex.trim();
        if (h.isEmpty()) return fallback;

        h = h.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-f]", "");
        if (h.isEmpty()) return fallback;

        try
        {
            if (h.length() >= 8)
            {
                // aarrggbb – take rrggbb
                h = h.substring(h.length() - 6);
            }
            else if (h.length() == 6)
            {
                // rrggbb as-is
            }
            else if (h.length() == 3)
            {
                // rgb -> rrggbb
                h = "" + h.charAt(0) + h.charAt(0)
                   + h.charAt(1) + h.charAt(1)
                   + h.charAt(2) + h.charAt(2);
            }
            else if (h.length() == 4)
            {
                // Treat as lower 4 hex digits of rrggbb (pad LEFT to 6)
                // Example: "ffff" -> "00ffff" (cyan), which matches what we want.
                h = String.format("%6s", h).replace(' ', '0'); // left-pad to 6
            }
            else if (h.length() == 2)
            {
                int v = Integer.parseInt(h, 16) & 0xFF;
                return new Color(v, v, v);
            }
            else
            {
                // Any other short length: left-pad to 6
                h = String.format("%6s", h).replace(' ', '0');
            }

            int val = Integer.parseInt(h.substring(0, 6), 16);
            return new Color((val >> 16) & 0xFF, (val >> 8) & 0xFF, val & 0xFF);
        }
        catch (Exception ignored)
        {
            return fallback != null ? fallback : Color.WHITE;
        }
    }

    // Detect any player menu action across RuneLite versions
    private static boolean isPlayerAction(MenuAction type)
    {
        if (type == null) return false;
        String name = type.name();
        if ("RUNELITE_PLAYER".equals(name)) return true;
        // Covers PLAYER_FIRST_OPTION .. PLAYER_EIGHTH_OPTION (and legacy EIGTH spelling)
        if (name.startsWith("PLAYER_") && name.endsWith("_OPTION")) return true;
        return false;
    }

    private synchronized boolean tryAcquireToken()
    {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefillMs;
        if (elapsed >= REFILL_MS)
        {
            long add = elapsed / REFILL_MS;
            if (add > 0)
            {
                tokens = (int)Math.min(TOKENS_MAX, tokens + add);
                lastRefillMs = now - (elapsed % REFILL_MS);
            }
        }
        if (tokens > 0)
        {
            tokens--;
            return true;
        }
        return false;
    }

    private void guardedTranslate(String src, String lang, boolean isAction)
    {
        if (src == null || src.isEmpty()) return;

        // De-dup in-flight
        if (!inFlight.add(src))
        {
            log.info("[CM] piggyback in-flight: '{}'", src);
            return;
        }

        // Rate limit
        if (!tryAcquireToken())
        {
            log.info("[CM] rate-limited; deferring translation this tick: '{}'", src);
            inFlight.remove(src);
            return;
        }

        log.info("[CM] {} translate request: src='{}'", (isAction ? "ACTION" : "TARGET"), src);
        translator.translateAsync(src, lang, out -> {
            try
            {
                String result = (out == null || out.isEmpty()) ? src : out;
                result = sanitizeColTags(result); // fix broken/malformed color tags from translator

                if (isAction)
                {
                    actionCache.put(src, result);
                }
                else
                {
                    targetCache.put(src, result);
                }
                log.info("[CM] {} translated: '{}' -> '{}'", (isAction ? "ACTION" : "TARGET"), src, result);
            }
            finally
            {
                inFlight.remove(src);
            }
        });
    }

    // Strip only <col=...> and </col> tags; keep all other characters
    private static String stripColTags(String s)
    {
        if (s == null || s.isEmpty()) return "";
        String noTags = s.replaceAll("(?i)</\\s*col\\s*>", "");
        noTags = noTags.replaceAll("(?i)<\\s*col\\s*=\\s*[^>]*>", "");
        return noTags;
    }

    // Extract the first <col=...> color from a tagged string; fallback if absent.
    private static Color firstColorFromTagged(String tagged, Color fallback)
    {
        if (tagged == null) return fallback != null ? fallback : Color.WHITE;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)<\\s*col\\s*=\\s*([^>]+)>")
                .matcher(tagged);
        if (m.find())
        {
            String v = m.group(1);
            // Normalize value
            String hex = v.trim();
            if ((hex.startsWith("\"") && hex.endsWith("\"")) || (hex.startsWith("'") && hex.endsWith("'")))
                hex = hex.substring(1, hex.length() - 1).trim();
            if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
            if (hex.startsWith("#")) hex = hex.substring(1);
            hex = hex.replaceAll("[^0-9a-fA-F]", "");
            if (hex.length() > 6) hex = hex.substring(hex.length() - 6);
            if (hex.length() == 3)
                hex = "" + hex.charAt(0) + hex.charAt(0)
                        + hex.charAt(1) + hex.charAt(1)
                        + hex.charAt(2) + hex.charAt(2);
            if (hex.length() == 4)
                hex = String.format("%6s", hex).replace(' ', '0');
            try
            {
                int val = Integer.parseInt(hex, 16);
                return new Color((val >> 16) & 0xFF, (val >> 8) & 0xFF, val & 0xFF);
            }
            catch (Exception ignored) {}
        }
        return fallback != null ? fallback : Color.WHITE;
    }

    // Normalize/fix common color-tag issues that sometimes come back from the translator:
    // - trailing "</col" without '>'
    // - weird spacing or duplicated closers
    // - unbalanced <col=...> ... without closing </col> (we append missing closers at the end)
    private static String sanitizeColTags(String s)
    {
        if (s == null || s.isEmpty()) return s;

        String fixed = s;

        // 1) Normalize common variants/spaces in closing tags and ensure '>'
        fixed = fixed.replaceAll("(?i)<\\s*/\\s*col\\s*>", "</col>");
        if (fixed.matches("(?i).*</\\s*col\\s*$")) {
            fixed = fixed + ">";
        }

        // 2) Collapse any duplicated closers like </col></col> (keep one)
        fixed = fixed.replaceAll("(?i)(</col>)+", "</col>");

        // 3) Count open vs close; if opens > closes, append missing closers at end
        int opens = 0, closes = 0;

        java.util.regex.Matcher mOpen = java.util.regex.Pattern.compile("(?i)<\\s*col\\s*=").matcher(fixed);
        while (mOpen.find()) opens++;

        java.util.regex.Matcher mClose = java.util.regex.Pattern.compile("(?i)</\\s*col\\s*>").matcher(fixed);
        while (mClose.find()) closes++;

        int missing = opens - closes;
        if (missing > 0) {
            StringBuilder sb = new StringBuilder(fixed);
            for (int i = 0; i < missing; i++) sb.append("</col>");
            fixed = sb.toString();
        }

        // 4) Guard against stray '<' fragments like trailing "<" or "</co"
        //    If an unmatched '<' remains at the end, drop it.
        if (fixed.endsWith("<")) {
            fixed = fixed.substring(0, fixed.length() - 1);
        }

        return fixed;
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
        // No space before ) , . : ; ! ? and similar
        if (")],.:;!?".indexOf(right) >= 0) return false;
        // No space after (
        if ("([{\u00AB".indexOf(left) >= 0) return false;
        return true;
    }

    // Normalize action option into a plain, consistent form
    private static String normalizeAction(String s)
    {
        String t = safe(s).trim();
        if (t.isEmpty()) return t;
        // Remove tags if any leaked in (defensive)
        t = t.replaceAll("<[^>]*>", "");
        // Normalize separators: "Talk-to" -> "Talk to", collapse spaces
        t = t.replace('\u00A0', ' ');
        t = t.replace('-', ' ');
        t = t.replaceAll("\\s+", " ");
        return t;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String safeLang(String lang)
    {
        if (lang == null || lang.trim().isEmpty()) return "RU";
        return lang.trim().toUpperCase(Locale.ROOT);
    }

    // Helper: first non-empty character from runs to decide spacing
    private static char firstNonEmptyChar(java.util.List<Run> runs)
    {
        if (runs == null) return 0;
        for (Run r : runs)
        {
            if (r != null && r.text != null && !r.text.isEmpty())
            {
                return r.text.charAt(0);
            }
        }
        return 0;
    }

    private static boolean colorsEqual(Color a, Color b)
    {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.getRGB() == b.getRGB();
    }
}
