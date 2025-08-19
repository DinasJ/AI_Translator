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
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Overlay that renders translated right-click tooltip text ON TOP of the vanilla tooltip.
 *
 * Behavior:
 *  - Uses LocalGlossary first (supports case-sensitive & normalized lookups).
 *  - If LocalGlossary contains a combined phrase (e.g. "Light Logs" -> "Поджечь Бревна"),
 *    the plugin will attempt to split that combined translation into action and target pieces
 *    and store them separately so UI looks like: action + target (with colors preserved).
 *  - Falls back to DeepL for missing translations.
 *  - Persistent cache stored at ~/.runelite/ai-translator/cache-<LANG>.
 */
@Slf4j
public class ContextMenuOverlay extends Overlay
{
    private final Client client;
    private final DeepLTranslator translator;
    private final AITranslatorConfig config;
    private final LocalGlossary localGlossary;

    // Per-source runtime caches (in-memory)
    private final Map<String, String> actionCache = new ConcurrentHashMap<>();
    private final Map<String, String> targetCache = new ConcurrentHashMap<>();
    // Use key "level" in levelCache to store translation of the single word "level"
    private final Map<String, String> levelCache = new ConcurrentHashMap<>();

    private static final Pattern LEVEL_TAIL = Pattern.compile("(?i)\\(\\s*level\\s*-\\s*\\d+\\s*\\)");
    private static final Pattern LEVEL_DIGITS = Pattern.compile("(?i)\\(\\s*level\\s*-\\s*(\\d+)\\s*\\)");

    // Persistent cache map (loaded from file). Keys are namespaced (A|, T|, L|)
    private final Map<String, String> persistentCache = new ConcurrentHashMap<>();
    private final Path persistentCachePath;

    // Track texts currently being translated to avoid duplicate parallel requests
    private final java.util.Set<String> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Tiny token bucket to rate-limit requests (prevents 429 bursts)
    private static final int TOKENS_MAX = 4;      // up to ~4 requests in a burst
    private static final long REFILL_MS = 300L;   // ~3.3 req/s steady
    private int tokens = TOKENS_MAX;
    private long lastRefillMs = System.currentTimeMillis();

    // Last snapshot of menu entries (preserving order)
    private volatile MenuEntry[] lastEntries = new MenuEntry[0];

    // ------------------ CUSTOM FONT ------------------
    private static final String CUSTOM_FONT_RESOURCE = "/fonts/RuneScapeBold-ru.ttf";
    private volatile Font customRawFont = null;
    private static final boolean DEBUG_FONT = false;
    // -------------------------------------------------

    // Custom menu skin colors
    private static final Color HDR_BG     = new Color(0x00, 0x00, 0x00);     // black
    private static final Color HDR_TEXT   = new Color(0x64, 0x5B, 0x4D);     // #645B4D
    private static final Color ROW_BG     = new Color(0x64, 0x5B, 0x4D);     // #645B4D
    private static final Color BORDER_COL = new Color(0x00, 0x00, 0x00);     // black
    private static final Color HOVER_YELLOW = new Color(255, 255, 0);

    private static final int PAD_X = 6;      // horizontal padding inside box
    private static final int PAD_Y = 2;      // compact vertical padding around rows
    private static final int HDR_PAD = 1;    // header box inner padding (as requested)
    private static final String HDR_RU = "Выберите действие"; // "Choose Option" in Russian

    private static final float FONT_SCALE = 1f;

    // Track open/close transitions
    private boolean menuWasOpen = false;

    @Inject
    public ContextMenuOverlay(Client client, DeepLTranslator translator, AITranslatorConfig config, LocalGlossary localGlossary)
    {
        this.client = client;
        this.translator = translator;
        this.config = config;
        this.localGlossary = localGlossary;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
        setPriority(OverlayPriority.HIGHEST);

        // Debug: confirm we see the SAME LocalGlossary instance as the plugin
        if (log.isDebugEnabled()) {
            log.debug("[CM] LocalGlossary instance id={} size={}", System.identityHashCode(this.localGlossary), this.localGlossary.size());
        }

        // Prepare persistent cache path (per-language file)
        String lang = safeLang(config.targetLang()).toUpperCase(Locale.ROOT);
        this.persistentCachePath = Paths.get(System.getProperty("user.home"), ".runelite", "ai-translator", "cache-" + lang);

        // Load persistent cache (best-effort; failures are logged)
        try
        {
            loadPersistentCache();
        }
        catch (Exception ex)
        {
            log.warn("[CM] Failed to load persistent cache '{}': {}", persistentCachePath, ex.toString());
        }

        // If the persistent cache already contains a localized "level" word, populate runtime cache
        String lvl = persistentCache.get("L|level");
        if (lvl != null && !lvl.isEmpty()) levelCache.put("level", lvl);

        // Also allow LocalGlossary to pre-populate "level" if present there
        String manualLevel = lookupLocalFlexible("level");
        if (manualLevel != null && !manualLevel.isEmpty())
        {
            levelCache.put("level", manualLevel);
            persistentPut("L|level", manualLevel);
        }
    }

    // Helper: get from persistent cache with fallback to raw source (handles older files)
    private String persistentGet(String key)
    {
        String v = persistentCache.get(key);
        if (v != null) return v;
        // fallback: if key is namespaced like "A|Take", try "Take" as raw key
        int sep = key.indexOf('|');
        if (sep >= 0 && sep + 1 < key.length())
        {
            String raw = key.substring(sep + 1);
            v = persistentCache.get(raw);
            if (v != null) return v;
        }
        return null;
    }

    // Put & persist exactly under the provided namespaced key
    private void persistentPut(String key, String value)
    {
        if (key == null || value == null) return;
        persistentCache.put(key, value);
        savePersistentCache();
    }

    // Load persistent cache — supports a JSON object file where each property is "english":"russian"
    private synchronized void loadPersistentCache() throws IOException
    {
        persistentCache.clear();
        Path p = persistentCachePath;
        if (!Files.exists(p)) return;

        String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
        if (content.isEmpty()) return;

        if (content.startsWith("{"))
        {
            Pattern pair = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", Pattern.DOTALL);
            Matcher m = pair.matcher(content);
            while (m.find())
            {
                String rawKey = m.group(1);
                String rawVal = m.group(2);
                String key = unescapeJson(rawKey);
                String val = unescapeJson(rawVal);
                persistentCache.put(key, val);
            }
        }
        else
        {
            Properties props = new Properties();
            try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8))
            {
                props.load(r);
                for (String k : props.stringPropertyNames())
                {
                    persistentCache.put(k, props.getProperty(k));
                }
            }
        }

        log.info("[CM] Loaded persistent cache {} entries from {}", persistentCache.size(), p);
    }

    // Save persistent cache as JSON (atomic)
    private synchronized void savePersistentCache()
    {
        try
        {
            Files.createDirectories(persistentCachePath.getParent());
            Path tmp = persistentCachePath.resolveSibling(persistentCachePath.getFileName().toString() + ".tmp");

            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
            {
                w.write("{");
                boolean first = true;
                for (Map.Entry<String, String> e : persistentCache.entrySet())
                {
                    if (!first) w.write(",");
                    first = false;
                    String k = escapeJson(e.getKey());
                    String v = escapeJson(e.getValue());
                    w.write("\n  \"");
                    w.write(k);
                    w.write("\": \"");
                    w.write(v);
                    w.write("\"");
                }
                if (!persistentCache.isEmpty()) w.write("\n");
                w.write("}");
                w.flush();
            }

            Files.move(tmp, persistentCachePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (Exception ex)
        {
            log.warn("[CM] Failed to save persistent cache to {}: {}", persistentCachePath, ex.toString());
        }
    }

    // Minimal JSON unescape
    private static String unescapeJson(String s)
    {
        if (s == null || s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); )
        {
            char c = s.charAt(i++);
            if (c != '\\')
            {
                sb.append(c);
                continue;
            }
            if (i >= s.length()) break;
            char esc = s.charAt(i++);
            switch (esc)
            {
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case '\"': sb.append('\"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'u':
                    if (i + 4 <= s.length())
                    {
                        String hx = s.substring(i, i + 4);
                        try
                        {
                            int code = Integer.parseInt(hx, 16);
                            sb.append((char) code);
                            i += 4;
                        }
                        catch (NumberFormatException ex)
                        {
                            sb.append("\\u");
                        }
                    }
                    else
                    {
                        sb.append("\\u");
                    }
                    break;
                default:
                    sb.append(esc);
                    break;
            }
        }
        return sb.toString();
    }

    // Minimal JSON escape
    private static String escapeJson(String s)
    {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            switch (c)
            {
                case '\\': sb.append("\\\\"); break;
                case '\"': sb.append("\\\""); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c > 0x7E)
                    {
                        sb.append(String.format("\\u%04x", (int) c));
                    }
                    else
                    {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    @Subscribe
    public void onClientTick(ClientTick tick)
    {
        boolean open = client.isMenuOpen();
        if (!open && menuWasOpen)
        {
            // Menu has just closed — clear the snapshot so we don't render stale data
            lastEntries = new MenuEntry[0];
            log.info("[CM] Menu closed: cleared snapshot");
        }
        menuWasOpen = open;
    }

    @Subscribe
    public void onMenuOpened(MenuOpened event)
    {
        MenuEntry[] all = client.getMenuEntries();
        if (all == null) all = new MenuEntry[0];
        lastEntries = Arrays.stream(all).filter(Objects::nonNull).toArray(MenuEntry[]::new);
        requestTranslationsFor(lastEntries);
        menuWasOpen = true;
        log.info("[CM] onMenuOpened: snap entries={}", lastEntries.length);
    }

    /**
     * Main logic for deciding translations for each menu entry.
     * Tries combined phrase first (explicit only), then action-only, then target-only.
     */
    private void requestTranslationsFor(MenuEntry[] entries)
    {
        if (entries == null || entries.length == 0) return;
        final String lang = safeLang(config.targetLang());

        // Ensure the "level" word is available for player-target rendering
        ensureLevelWord(lang);

        for (MenuEntry e : entries)
        {
            if (e == null) continue;

            final String actionTagged = safe(e.getOption()); // "Light"
            final String targetTagged = safe(e.getTarget()); // "<col=ff9040>Logs</col>"
            final MenuAction type = e.getType();

            // normalized action (no tags, collapsed spacing)
            final String actionPlain = normalizeAction(stripColTags(actionTagged));
            final String cleanTarget = stripColTags(targetTagged);

            // DEBUG: show normalized forms for tracing
            if (log.isDebugEnabled())
            {
                log.debug("[CM] entry raw option='{}' target='{}' => actionPlain='{}' cleanTarget='{}'",
                        actionTagged, targetTagged, actionPlain, cleanTarget);
            }

            // 1) Phrase-level check: "actionPlain + ' ' + cleanTarget"
            if (!actionPlain.isEmpty() && !cleanTarget.isEmpty() && !isPlayerAction(type))
            {
                String combinedKey = actionPlain + " " + cleanTarget;

                // IMPORTANT: use lookupExact so we only match an explicit combined phrase entry.
                String combinedManual = localGlossary.lookupExact(combinedKey);

                if (combinedManual != null)
                {
                    log.info("[CM] local COMBINED hit: '{}' -> '{}'", combinedKey, combinedManual);

                    // Try to split the combined translation into action / target pieces.
                    Pair parts = splitCombinedTranslation(combinedManual, actionPlain, cleanTarget);

                    if (parts != null)
                    {
                        // store both parts separately where possible
                        if (parts.action != null && !parts.action.isEmpty())
                        {
                            actionCache.put(actionPlain, parts.action);
                            persistentPut("A|" + actionPlain, parts.action);
                            log.info("[CM] local COMBINED -> ACTION: '{}' -> '{}'", actionPlain, parts.action);
                        }
                        if (parts.target != null)
                        {
                            // store target keyed by original tagged string so rendering preserves colors
                            targetCache.put(targetTagged, parts.target);
                            persistentPut("T|" + targetTagged, parts.target);
                            log.info("[CM] local COMBINED -> TARGET: '{}' -> '{}'", targetTagged, parts.target);
                        }
                        continue; // done for this entry
                    }
                    else
                    {
                        // fallback: store as whole-line on action side (previous behavior)
                        actionCache.put(actionPlain, combinedManual);
                        persistentPut("A|" + actionPlain, combinedManual);
                        log.info("[CM] local COMBINED (no-split) hit: '{}' -> '{}'", combinedKey, combinedManual);
                        continue;
                    }
                }
            }

            // ACTION: translate the normalized option as-is
            if (!actionPlain.isEmpty())
            {
                String actionSrc = actionPlain;

                // 1) Check local glossary first (flexible; allows substring target matches for single tokens)
                String manualAction = lookupLocalFlexible(actionSrc);
                if (manualAction != null)
                {
                    actionCache.put(actionSrc, manualAction);
                    persistentPut("A|" + actionSrc, manualAction);
                    log.info("[CM] local ACTION hit: {} -> {}", actionSrc, manualAction);
                }
                else
                {
                    // Check runtime cache first
                    if (!actionCache.containsKey(actionSrc))
                    {
                        // Check persistent cache
                        String pKey = "A|" + actionSrc;
                        String pVal = persistentGet(pKey);
                        if (pVal != null)
                        {
                            actionCache.put(actionSrc, pVal);
                            log.info("[CM] persistent ACTION hit: {} -> {}", pKey, pVal);
                        }
                        else
                        {
                            guardedTranslate(actionSrc, lang, true);
                        }
                    }
                }
            }

            boolean isPlayer = isPlayerAction(type);
            if (isPlayer)
            {
                // For players: don't translate the name; ensure "level" word is available
            }
            else
            {
                // Non-player targets: translate whole (clean) target, color locally
                if (!targetTagged.isEmpty() && !targetCache.containsKey(targetTagged))
                {
                    // 1) Check local glossary: try original tagged first (in case user included tags), then bare text
                    String manualTarget = lookupLocalFlexible(targetTagged);
                    if (manualTarget == null)
                    {
                        manualTarget = lookupLocalFlexible(cleanTarget);
                    }

                    if (manualTarget != null)
                    {
                        // manual translation found — store as plain text (no tags)
                        targetCache.put(targetTagged, manualTarget);
                        persistentPut("T|" + targetTagged, manualTarget);
                        log.info("[CM] local TARGET hit: {} -> {}", targetTagged, manualTarget);
                    }
                    else
                    {
                        // Check persistent cache using original tagged string to preserve colors
                        String pKey = "T|" + targetTagged;
                        String pVal = persistentGet(pKey);
                        if (pVal != null)
                        {
                            targetCache.put(targetTagged, pVal);
                            log.info("[CM] persistent TARGET hit: {} -> {}", pKey, pVal);
                        }
                        else
                        {
                            translateTargetWithoutTags(targetTagged, lang);
                        }
                    }
                }
            }
        }
    }

    // Ensure the "level" word is translated (from persistent, local glossary or request)
    private void ensureLevelWord(String lang)
    {
        if (levelCache.containsKey("level")) return;

        // 1) Check persistent
        String persisted = persistentGet("L|level");
        if (persisted != null)
        {
            levelCache.put("level", persisted);
            return;
        }

        // 2) Check local glossary
        String manualLevel = lookupLocalFlexible("level");
        if (manualLevel != null)
        {
            levelCache.put("level", manualLevel);
            persistentPut("L|level", manualLevel);
            log.info("[CM] local LEVEL hit: level -> {}", manualLevel);
            return;
        }

        // 3) enqueue translation for "level"
        String levelKey = "L|level";
        if (!inFlight.add(levelKey)) return;

        if (!tryAcquireToken())
        {
            inFlight.remove(levelKey);
            return;
        }

        translator.translateAsync("level", lang, out -> {
            try
            {
                String res = (out == null || out.isEmpty()) ? "level" : out.trim();
                levelCache.put("level", res);
                persistentPut(levelKey, res);
                log.info("[CM] LEVEL word translated: '{}' -> '{}'", "level", res);
            }
            finally
            {
                inFlight.remove(levelKey);
            }
        });
    }

    // Send target text without <col> tags to DeepL and then store PLAIN result.
    private void translateTargetWithoutTags(String originalTagged, String lang)
    {
        String clean = stripColTags(originalTagged);
        if (clean.isEmpty())
        {
            targetCache.put(originalTagged, clean);
            return;
        }

        final String cacheKey = "T|" + originalTagged;

        // in-memory guard
        if (!inFlight.add(cacheKey))
        {
            log.info("[CM] piggyback in-flight(clean target): '{}'", clean);
            return;
        }

        if (!tryAcquireToken())
        {
            log.info("[CM] rate-limited; deferring target this tick: '{}'", clean);
            inFlight.remove(cacheKey);
            return;
        }

        // Check local glossary one more time on the plain clean text before calling DeepL
        String manual = lookupLocalFlexible(clean);
        if (manual != null)
        {
            try
            {
                targetCache.put(originalTagged, manual);
                persistentPut(cacheKey, manual);
                log.info("[CM] local TARGET (clean) hit: {} -> {}", clean, manual);
            }
            finally
            {
                inFlight.remove(cacheKey);
            }
            return;
        }

        translator.translateAsync(clean, lang, out -> {
            try
            {
                String plain = (out == null || out.isEmpty()) ? clean : out;
                // store plain text in runtime cache keyed by original tagged string
                targetCache.put(originalTagged, plain);
                persistentPut(cacheKey, plain);
                log.info("[CM] TARGET translated(clean->plain): '{}' -> '{}'", originalTagged, plain);
            }
            finally
            {
                inFlight.remove(cacheKey);
            }
        });
    }

    private void guardedTranslate(String src, String lang, boolean isAction)
    {
        if (src == null || src.isEmpty()) return;

        String prefix = isAction ? "A|" : "T|";
        String pKey = prefix + src;

        // 1) local glossary check (flexible)
        String manual = lookupLocalFlexible(src);
        if (manual != null)
        {
            if (isAction) actionCache.put(src, manual);
            else targetCache.put(src, manual);

            persistentPut(pKey, manual);
            log.info("[CM] local cache hit: {} -> {}", pKey, manual);
            return;
        }

        // 2) Check persistent cache first
        String pVal = persistentGet(pKey);
        if (pVal != null)
        {
            if (isAction) actionCache.put(src, pVal);
            else targetCache.put(src, pVal);
            log.info("[CM] persistent cache hit: {} -> {}", pKey, pVal);
            return;
        }

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

                // persist
                persistentPut(pKey, result);

                log.info("[CM] {} translated: '{}' -> '{}'", (isAction ? "ACTION" : "TARGET"), src, result);
            }
            finally
            {
                inFlight.remove(src);
            }
        });
    }

    // ------------------ RENDERING & UTILITIES (unchanged) ------------------

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!client.isMenuOpen())
        {
            return null;
        }

        // Read vanilla tooltip rectangle
        final int x = client.getMenuX();
        final int y = client.getMenuY();
        final int w = client.getMenuWidth();
        final int h = client.getMenuHeight();
        if (w <= 0 || h <= 0) return null;

        // --- CUSTOM FONT USAGE ---
        Font rlBase = FontManager.getRunescapeFont();
        float targetSize = rlBase.getSize2D() * FONT_SCALE;
        Font menuFont = getMenuFont(targetSize);
        g.setFont(menuFont);
        FontMetrics fm = g.getFontMetrics(menuFont);
        // -------------------------

        final int hdrH = 19;
        final int ROW_H = 15;

        final int bodyY = y + hdrH;
        final int bodyH = Math.max(0, h - hdrH);

        final int ascent = fm.getAscent();
        final int descent = fm.getDescent();
        final int textHeight = ascent + descent;
        final int rowTopPadding = Math.max(0, (ROW_H - textHeight) / 2);

        int maxTextWidth = 0;
        for (int line = 0; line < lastEntries.length; line++)
        {
            int idx = lastEntries.length - 1 - line;
            MenuEntry e = lastEntries[idx];
            if (e == null) continue;

            String option = safe(e.getOption()).trim();
            String optionPlain = normalizeAction(option);
            String actionSrc = optionPlain;
            String actionTranslated = actionSrc.isEmpty() ? "" : actionCache.getOrDefault(actionSrc, actionSrc);

            String targetTagged = safe(e.getTarget()).trim();
            MenuAction type = e.getType();
            boolean looksLikePlayer = isPlayerAction(type) || containsLevelTail(targetTagged);

            String targetDisplay = "";
            if (looksLikePlayer)
            {
                java.util.List<Run> runs = parseTaggedRuns(targetTagged, Color.WHITE);
                StringBuilder sb = new StringBuilder();
                for (Run r : runs)
                {
                    if (r == null || r.text == null) continue;
                    String drawText = r.text;
                    Matcher m = LEVEL_DIGITS.matcher(r.text);
                    if (m.find())
                    {
                        String digits = m.group(1);
                        String lvlWord = levelCache.getOrDefault("level", "level");
                        drawText = "(" + lvlWord + "-" + digits + ")";
                    }
                    sb.append(drawText);
                }
                targetDisplay = sb.toString();
            }
            else
            {
                targetDisplay = targetCache.getOrDefault(targetTagged, stripColTags(targetTagged));
                if (targetDisplay == null) targetDisplay = "";
            }

            String full = composeLine(actionTranslated, targetDisplay);
            int textW = fm.stringWidth(full);
            if (textW > maxTextWidth) maxTextWidth = textW;
        }

        int neededW = PAD_X * 2 + maxTextWidth;
        final int effectiveW = Math.max(w, neededW);

        Shape menuClipOld = g.getClip();
        g.setClip(new Rectangle(x, y, effectiveW, h));

        g.setColor(HDR_BG);
        g.fillRect(x, y, effectiveW, hdrH);
        g.setColor(HDR_TEXT);
        int hdrTextY = y + ((hdrH - textHeight) / 2) + ascent;
        int hdrTextX = x + PAD_X;
        g.drawString(HDR_RU, hdrTextX, hdrTextY);

        g.setColor(ROW_BG);
        g.fillRect(x, bodyY, effectiveW, bodyH);
        g.setColor(BORDER_COL);
        g.drawRect(x, y, effectiveW - 1, h - 1);

        Point mp = client.getMouseCanvasPosition();
        int hoveredIndex = -1;
        if (mp != null)
        {
            int relY = mp.getY() - y - hdrH;
            if (relY >= 0) {
                hoveredIndex = relY / ROW_H;
                if (hoveredIndex < 0 || hoveredIndex >= lastEntries.length) hoveredIndex = -1;
            }
        }

        int baseY = y + hdrH + rowTopPadding + ascent;

        for (int line = 0; line < lastEntries.length; line++)
        {
            int idx = lastEntries.length - 1 - line;
            MenuEntry e = lastEntries[idx];
            if (e == null) continue;

            int lineY = baseY + line * ROW_H;
            int cxLine = x + PAD_X;

            String option = safe(e.getOption()).trim();
            String optionPlain = normalizeAction(option);
            String actionSrc = optionPlain;
            String actionTranslated = actionSrc.isEmpty() ? "" : actionCache.getOrDefault(actionSrc, actionSrc);

            String targetTagged = safe(e.getTarget()).trim();
            MenuAction type = e.getType();
            boolean looksLikePlayer = isPlayerAction(type) || containsLevelTail(targetTagged);

            java.util.List<Seg> segs = new java.util.ArrayList<>(4);

            if (!actionTranslated.isEmpty())
            {
                segs.add(new Seg(actionTranslated, Color.WHITE));
                char right = 0;
                if (looksLikePlayer)
                {
                    java.util.List<Run> preview = parseTaggedRuns(targetTagged, Color.WHITE);
                    right = firstNonEmptyChar(preview);
                }
                else
                {
                    String pl = stripColTags(targetTagged);
                    if (pl != null && !pl.isEmpty()) right = pl.charAt(0);
                }
                char left = actionTranslated.isEmpty() ? 0 : actionTranslated.charAt(actionTranslated.length() - 1);
                if (right != 0 && needsSpaceBetween(left, right)) segs.add(new Seg(" ", Color.WHITE));
            }

            if (looksLikePlayer)
            {
                java.util.List<Run> runs = parseTaggedRuns(targetTagged, Color.WHITE);
                for (Run r : runs)
                {
                    if (r.text == null || r.text.isEmpty()) continue;
                    String drawText = r.text;
                    Matcher m = LEVEL_DIGITS.matcher(r.text);
                    if (m.find())
                    {
                        String digits = m.group(1);
                        String lvlWord = levelCache.getOrDefault("level", "level");
                        drawText = "(" + lvlWord + "-" + digits + ")";
                    }
                    segs.add(new Seg(drawText, (r.color != null ? r.color : Color.WHITE)));
                }
            }
            else
            {
                String targetPlain = targetCache.getOrDefault(targetTagged, stripColTags(targetTagged));
                if (targetPlain != null && !targetPlain.isEmpty())
                {
                    Color firstColor = firstColorFromTagged(targetTagged, Color.WHITE);
                    segs.add(new Seg(targetPlain, firstColor));
                }
            }

            boolean hovered = (line == hoveredIndex);
            for (Seg s : segs)
            {
                if (s.text == null || s.text.isEmpty()) continue;

                g.setColor(Color.BLACK);
                g.drawString(s.text, cxLine + 1, lineY + 1);

                Color col = s.color;
                boolean isDefaultWhite = (col == null) || (col.getRGB() == Color.WHITE.getRGB());
                if (hovered && isDefaultWhite) col = HOVER_YELLOW;

                g.setColor(col != null ? col : Color.WHITE);
                g.drawString(s.text, cxLine, lineY);
                cxLine += fm.stringWidth(s.text);
            }
        }

        g.setClip(menuClipOld);

        return new Dimension(w, h);
    }

    // Loads the custom font resource once (raw), returns a derived Font of requested size.
    private Font getMenuFont(float targetSize)
    {
        if (customRawFont == null)
        {
            synchronized (this)
            {
                if (customRawFont == null)
                {
                    try (InputStream is = ContextMenuOverlay.class.getResourceAsStream(CUSTOM_FONT_RESOURCE))
                    {
                        if (is != null)
                        {
                            Font raw = Font.createFont(Font.TRUETYPE_FONT, is);
                            customRawFont = raw;
                            if (DEBUG_FONT) log.info("[CM] Loaded custom font resource: {}", CUSTOM_FONT_RESOURCE);
                        }
                        else
                        {
                            if (DEBUG_FONT) log.warn("[CM] Custom font resource not found: {}", CUSTOM_FONT_RESOURCE);
                        }
                    }
                    catch (Exception ex)
                    {
                        if (DEBUG_FONT) log.warn("[CM] Failed to load custom font '{}': {}", CUSTOM_FONT_RESOURCE, ex.toString());
                        customRawFont = null;
                    }
                }
            }
        }

        if (customRawFont != null)
        {
            try
            {
                return customRawFont.deriveFont(Font.BOLD, targetSize);
            }
            catch (Exception ex)
            {
                if (DEBUG_FONT) log.warn("[CM] Failed to derive custom font size {}, falling back: {}", targetSize, ex.toString());
            }
        }

        return FontManager.getRunescapeFont().deriveFont(Font.BOLD, targetSize);
    }

    private static class Seg { final String text; final Color color; Seg(String t, Color c) { this.text = t; this.color = c; } }
    private static class Run { final String text; final Color color; Run(String text, Color color) { this.text = text; this.color = color; } }

    private static java.util.List<Run> parseTaggedRuns(String input, Color defaultColor)
    {
        java.util.List<Run> runs = new ArrayList<>();
        if (input == null || input.isEmpty()) return runs;

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
                int eq = low.indexOf('=');
                if (eq < 0)
                {
                    cur = defaultColor != null ? defaultColor : Color.WHITE;
                }
                else
                {
                    String val = tagRaw.substring(eq + 1).trim();
                    if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'")))
                    {
                        val = val.substring(1, val.length() - 1).trim();
                    }
                    if (val.startsWith("0x") || val.startsWith("0X")) val = val.substring(2);
                    if (val.startsWith("#")) val = val.substring(1);
                    val = val.replaceAll("[^0-9a-fA-F]", "");
                    Color parsed = parseHexColor(val, cur);
                    cur = parsed != null ? parsed : (defaultColor != null ? defaultColor : Color.WHITE);
                }
            }
            else if (low.equals("/col") || low.equalsIgnoreCase("/col"))
            {
                cur = defaultColor != null ? defaultColor : Color.WHITE;
            }

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
                h = String.format("%6s", h).replace(' ', '0');
            }
            else if (h.length() == 2)
            {
                int v = Integer.parseInt(h, 16) & 0xFF;
                return new Color(v, v, v);
            }
            else
            {
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

    private static boolean isPlayerAction(MenuAction type)
    {
        if (type == null) return false;
        String name = type.name();
        if ("RUNELITE_PLAYER".equals(name)) return true;
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

    // Strip only <col=...> and </col> tags; keep all other characters
    private static String stripColTags(String s)
    {
        if (s == null || s.isEmpty()) return "";
        String noTags = s.replaceAll("(?i)</\\s*col\\s*>", "");
        noTags = noTags.replaceAll("(?i)<\\s*col\\s*=\\s*[^>]*>", "");
        return noTags;
    }

    // Flexible local glossary lookup wrapper:
    // tries exact, stripped tags, normalized action form, and combined normalized forms.
    // Note: this method still calls localGlossary.lookup which may perform substring matching,
    // which is desired for single-token lookups (targets).
    private String lookupLocalFlexible(String src)
    {
        if (src == null) return null;
        String trimmed = src.trim();
        if (trimmed.isEmpty()) return null;

        // 1) direct lookup (LocalGlossary will handle case-sensitive & normalized inside)
        String v = localGlossary.lookup(trimmed);
        if (v != null)
        {
            log.debug("lookupLocalFlexible: direct '{}' -> '{}'", trimmed, v);
            return v;
        }

        // 2) strip color tags and retry
        String stripped = stripColTags(trimmed);
        if (!stripped.equals(trimmed))
        {
            v = localGlossary.lookup(stripped);
            if (v != null)
            {
                log.debug("lookupLocalFlexible: stripped '{}' -> '{}'", stripped, v);
                return v;
            }
        }

        // 3) normalized action form (remove tags, replace '-' with space, collapse spaces)
        String normalizedAction = normalizeAction(stripped);
        if (!normalizedAction.equals(stripped))
        {
            v = localGlossary.lookup(normalizedAction);
            if (v != null)
            {
                log.debug("lookupLocalFlexible: normalized '{}' -> '{}'", normalizedAction, v);
                return v;
            }
        }

        return null;
    }

    // tiny pair holder
    private static class Pair {
        final String action;
        final String target;
        Pair(String a, String t) { this.action = a; this.target = t; }
    }

    /**
     * Try to split a combined translation string into (action, target).
     * Heuristics:
     *  - If the combinedManual contains '|' (author delimiter), split on first '|'
     *  - Else if it contains a tab, split on first tab
     *  - Else split tokens: use number of words in actionPlain (if >0 and < tokens-1),
     *    otherwise take the first token as action and rest as target.
     *
     * Returns null when splitting isn't possible or combinedManual is empty.
     */
    private Pair splitCombinedTranslation(String combinedManual, String actionPlain, String cleanTarget)
    {
        if (combinedManual == null) return null;
        String s = combinedManual.trim();
        if (s.isEmpty()) return null;

        // explicit delimiter preferred (author-friendly)
        if (s.contains("|"))
        {
            String[] p = s.split("\\|", 2);
            String a = p[0].trim();
            String t = p.length > 1 ? p[1].trim() : "";
            return new Pair(a, t);
        }
        if (s.contains("\t"))
        {
            String[] p = s.split("\\t", 2);
            String a = p[0].trim();
            String t = p.length > 1 ? p[1].trim() : "";
            return new Pair(a, t);
        }

        // token heuristic: split on word count of source action if available
        String[] tokens = s.split("\\s+");
        if (tokens.length == 1)
        {
            // only a single token — treat as action, empty target
            return new Pair(s, "");
        }

        int actionWords = 0;
        if (actionPlain != null && !actionPlain.isEmpty())
        {
            actionWords = actionPlain.trim().split("\\s+").length;
        }

        int n = (actionWords > 0 && actionWords < tokens.length) ? actionWords : 1;
        if (n >= tokens.length) n = Math.max(1, tokens.length - 1);

        StringBuilder aBuilder = new StringBuilder();
        for (int i = 0; i < n; i++)
        {
            if (i > 0) aBuilder.append(' ');
            aBuilder.append(tokens[i]);
        }
        StringBuilder tBuilder = new StringBuilder();
        for (int i = n; i < tokens.length; i++)
        {
            if (i > n) tBuilder.append(' ');
            tBuilder.append(tokens[i]);
        }

        String a = aBuilder.toString().trim();
        String t = tBuilder.toString().trim();

        // sanity: avoid weird empty splits
        if (a.isEmpty() && !t.isEmpty()) {
            // flip if target-only (unlikely) -> action = first token of t
            String[] tt = t.split("\\s+", 2);
            a = tt[0];
            t = tt.length > 1 ? tt[1] : "";
        }

        return new Pair(a, t);
    }

    // (other utility methods unchanged from your previous file)
    private static String sanitizeColTags(String s)
    {
        if (s == null || s.isEmpty()) return s;

        String fixed = s;

        fixed = fixed.replaceAll("(?i)<\\s*/\\s*col\\s*>", "</col>");
        if (fixed.matches("(?i).*</\\s*col\\s*$")) fixed = fixed + ">";

        fixed = fixed.replaceAll("(?i)(</col>)+", "</col>");

        int opens = 0, closes = 0;
        java.util.regex.Matcher mOpen = java.util.regex.Pattern.compile("(?i)<\\s*col\\s*=").matcher(fixed);
        while (mOpen.find()) opens++;
        java.util.regex.Matcher mClose = java.util.regex.Pattern.compile("(?i)</\\s*col\\s*>").matcher(fixed);
        while (mClose.find()) closes++;

        int missing = opens - closes;
        if (missing > 0)
        {
            StringBuilder sb = new StringBuilder(fixed);
            for (int i = 0; i < missing; i++) sb.append("</col>");
            fixed = sb.toString();
        }

        if (fixed.endsWith("<")) fixed = fixed.substring(0, fixed.length() - 1);

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

    private static String safeLang(String lang)
    {
        if (lang == null || lang.trim().isEmpty()) return "RU";
        return lang.trim().toUpperCase(Locale.ROOT);
    }

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

    private static boolean containsLevelTail(String text)
    {
        return text != null && LEVEL_TAIL.matcher(text).find();
    }

    private static Color firstColorFromTagged(String tagged, Color fallback)
    {
        if (tagged == null) return fallback != null ? fallback : Color.WHITE;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)<\\s*col\\s*=\\s*([^>]+)>")
                .matcher(tagged);
        if (m.find())
        {
            String v = m.group(1);
            String hex = v.trim();
            if ((hex.startsWith("\"") && hex.endsWith("\"")) || (hex.startsWith("'") && hex.endsWith("'")))
                hex = hex.substring(1, hex.length() - 1).trim();
            if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
            if (hex.startsWith("#")) hex = hex.substring(1);
            hex = hex.replaceAll("[^0-9a-fA-F]", "");
            if (hex.length() > 6) hex = hex.substring(hex.length() - 6);
            if (hex.length() == 3) hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
            if (hex.length() == 4) hex = String.format("%6s", hex).replace(' ', '0');
            try
            {
                int val = Integer.parseInt(hex, 16);
                return new Color((val >> 16) & 0xFF, (val >> 8) & 0xFF, val & 0xFF);
            }
            catch (Exception ignored) {}
        }
        return fallback != null ? fallback : Color.WHITE;
    }

    private static String stripTags(String s)
    {
        return s == null ? "" : s.replaceAll("<[^>]*>", "").replace('\u00A0', ' ');
    }

    // (rest of helpers left unchanged)
}
