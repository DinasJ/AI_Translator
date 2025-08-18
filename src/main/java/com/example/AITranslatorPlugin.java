package com.example;

import com.google.inject.Provides;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.runelite.client.RuneLite;

@PluginDescriptor(
        name = "AI Translator",
        description = "Translates chat/dialogue text using DeepL and overlays it on screen",
        enabledByDefault = false
)
public class AITranslatorPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(AITranslatorPlugin.class);
    private static final boolean DEBUG = false;

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private OverlayManager overlayManager;
    @Inject private ChatOverlay chatOverlay;
    @Inject private CyrillicTooltipOverlay tooltipOverlay;
    @Inject private AITranslatorConfig config;
    @Inject private DeepLTranslator translator;
    @Inject private ContextMenuOverlay contextMenuOverlay;
    @Inject private net.runelite.client.eventbus.EventBus eventBus;

    // Cache per original text
    private static final int MAX_CACHE_ENTRIES = 10_000;
    private static final long SAVE_PERIOD_SECONDS = 30;

    private final Gson gson = new Gson();

    // LRU cache (evicts oldest when exceeding MAX_CACHE_ENTRIES)
    private final Map<String, String> translationCache =
            java.util.Collections.synchronizedMap(new LinkedHashMap<String, String>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });
    // Track last seen plain text per widget id to only retranslate changes
    private final Map<Integer, String> lastPlainById = new HashMap<>();

    private volatile boolean cacheDirty = false;
    private Path cacheFile = null;
    private java.util.concurrent.ScheduledFuture<?> periodicSaveTask;

    private ScheduledExecutorService scheduler;
    private int tickCounter = 0;
    private static final int GRACE_TICKS = 3; // keep translations for N ticks after widget disappears

    // Track previous tick's visible ids to detect reappearing widgets
    private Set<Integer> prevPresentIds = new HashSet<>();

    @Provides
    AITranslatorConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(AITranslatorConfig.class);
    }

    @Override
    protected void startUp()
    {
        overlayManager.add(chatOverlay);
        overlayManager.add(tooltipOverlay);
        overlayManager.add(contextMenuOverlay);
        eventBus.register(contextMenuOverlay);
        log.info("AI Translator started");

        // Prepare cache file path per target language
        initCacheFile();

        // Load cache from disk
        loadCacheFromDisk();

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::tick, 0, 400, TimeUnit.MILLISECONDS);

        // Periodic cache saver
        periodicSaveTask = scheduler.scheduleWithFixedDelay(this::saveCacheIfDirty, SAVE_PERIOD_SECONDS, SAVE_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    protected void shutDown()
    {
        // Persist cache synchronously before shutdown
        saveCacheNow();

        if (periodicSaveTask != null)
        {
            periodicSaveTask.cancel(false);
            periodicSaveTask = null;
        }
        if (scheduler != null)
        {
            scheduler.shutdown();
            scheduler = null;
        }
        overlayManager.remove(chatOverlay);
        overlayManager.remove(tooltipOverlay);
        overlayManager.remove(contextMenuOverlay);
        eventBus.unregister(contextMenuOverlay);
        translationCache.clear();
        lastPlainById.clear();
        prevPresentIds.clear();
        clientThread.invokeLater(chatOverlay::clearAllTranslations);
        log.info("AI Translator stopped");
    }

    // Wherever you add to cache (after a successful translation), mark dirty:
    // translationCache.put(currentPlain, translated);
    // cacheDirty = true;

    // And in the options special-case too (if you cache that text), mark dirty similarly.

    private void tick()
    {
        clientThread.invokeLater(() ->
        {
            tickCounter++;

            // Detect options visibility first
            Widget optionsContainer = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
            boolean optionsVisible = optionsContainer != null && !optionsContainer.isHidden();

            List<Widget> widgets = getRelevantWidgetsAsList(optionsVisible);
            chatOverlay.updateSourceWidgets(widgets);

            if (DEBUG)
            {
                StringBuilder sb = new StringBuilder("Tick ").append(tickCounter).append(" widgets: ");
                for (Widget w : widgets)
                {
                    if (w == null) continue;
                    int id = w.getId();
                    String raw = w.getText();
                    boolean hidden = w.isHidden();
                    sb.append(String.format("[id=%d grp=%d child=%d hidden=%s len=%d] ",
                            id, (id >>> 16), (id & 0xFFFF), hidden, raw == null ? -1 : raw.length()));
                }
                log.info(sb.toString());
            }

            // Current visible ids
            Set<Integer> presentIds = new HashSet<>();
            for (Widget w : widgets) { presentIds.add(w.getId()); }

            // Mark seen + prune expired (sticky grace)
            chatOverlay.markSeen(presentIds, tickCounter);
            chatOverlay.pruneExpired(tickCounter, GRACE_TICKS);

            // Special-case: options container as a single block to avoid id-collision
            if (optionsVisible && optionsContainer != null)
            {
                String combinedPlain = collectOptionsPlain(optionsContainer);
                int cid = optionsContainer.getId();

                if (!combinedPlain.isEmpty())
                {
                    String last = lastPlainById.get(cid);
                    if (!combinedPlain.equals(last))
                    {
                        lastPlainById.put(cid, combinedPlain);

                        String cached = translationCache.get(combinedPlain);
                        if (cached != null)
                        {
                            // Seed per-line cache from cached combined text as well
                            seedPerLineCache(combinedPlain, cached);
                            chatOverlay.setTranslation(cid, cached);
                        }
                        else
                        {
                            final String lang = config.targetLang();
                            log.info("[OPT] Translate request: '{}'", combinedPlain);
                            translator.translateAsync(combinedPlain, lang, translated ->
                            {
                                String out = translated == null || translated.isEmpty() ? combinedPlain : translated;
                                translationCache.put(combinedPlain, out);
                                seedPerLineCache(combinedPlain, out);
                                cacheDirty = true;
                                log.info("[OPT] Translated: '{}' -> '{}'", combinedPlain, out);
                                clientThread.invokeLater(() -> chatOverlay.setTranslation(cid, out));
                            });
                        }
                    }
                    else
                    {
                        String cached = translationCache.get(combinedPlain);
                        if (cached != null)
                        {
                            seedPerLineCache(combinedPlain, cached);
                            chatOverlay.setTranslation(cid, cached);
                        }
                    }
                }
            }

            // Detect reappeared ids (present now, not present previously)
            Set<Integer> appeared = new HashSet<>(presentIds);
            appeared.removeAll(prevPresentIds);

            // Re-assert translations for all newly appeared widgets (not only options)
            for (Widget w : widgets)
            {
                if (w == null || w.isHidden()) continue;
                int id = w.getId();
                if (!appeared.contains(id)) continue;

                String raw = w.getText();
                if (raw == null) raw = "";
                String plain = stripTags(raw).trim();

                // Skip the player's own name on name widgets
                if (isNameWidget(w) && isLocalPlayerName(plain))
                {
                    chatOverlay.setTranslation(id, "");
                    continue;
                }

                if (plain.isEmpty())
                {
                    continue;
                }

                String cached = translationCache.get(plain);
                if (cached != null)
                {
                    chatOverlay.setTranslation(id, cached);
                    lastPlainById.put(id, plain);
                    continue;
                }

                final String lang = config.targetLang();
                final int wid = id;
                final String currentPlain = plain;
                log.info("[WIDGET reappear] Translate request: '{}'", currentPlain);
                translator.translateAsync(currentPlain, lang, translated ->
                {
                    String out = translated == null || translated.isEmpty() ? currentPlain : translated;
                    translationCache.put(currentPlain, out);
                    cacheDirty = true;
                    log.info("[WIDGET reappear] Translated: '{}' -> '{}'", currentPlain, out);
                    clientThread.invokeLater(() ->
                    {
                        String still = stripTags(w.getText() == null ? "" : w.getText()).trim();
                        if (!currentPlain.equals(still))
                        {
                            return;
                        }
                        chatOverlay.setTranslation(wid, out);
                        lastPlainById.put(wid, currentPlain);
                    });
                });
            }

            for (Widget w : widgets)
            {
                if (w == null || w.isHidden()) continue;

                int id = w.getId();
                int grp = (id >>> 16), child = (id & 0xFFFF);
                if (grp == 219 && child == 1) continue;

                String raw = w.getText();
                if (raw == null) raw = "";
                String plain = stripTags(raw).trim();

                if (isNameWidget(w) && isLocalPlayerName(plain))
                {
                    lastPlainById.put(id, plain);
                    chatOverlay.setTranslation(id, "");
                    continue;
                }

                if (plain.isEmpty())
                {
                    continue;
                }

                String last = lastPlainById.get(id);
                if (plain.equals(last))
                {
                    String cached = translationCache.get(plain);
                    if (cached != null)
                    {
                        chatOverlay.setTranslation(id, cached);
                    }
                    continue;
                }

                lastPlainById.put(id, plain);

                String cached = translationCache.get(plain);
                if (cached != null)
                {
                    chatOverlay.setTranslation(id, cached);
                    continue;
                }

                final String lang = config.targetLang();
                final int wid = id;
                final String currentPlain = plain;
                log.info("[WIDGET] Translate request: '{}'", currentPlain);
                translator.translateAsync(plain, lang, translated ->
                {
                    String out = translated == null || translated.isEmpty() ? currentPlain : translated;
                    translationCache.put(currentPlain, out);
                    cacheDirty = true;
                    log.info("[WIDGET] Translated: '{}' -> '{}'", currentPlain, out);
                    clientThread.invokeLater(() ->
                    {
                        String still = stripTags(w.getText() == null ? "" : w.getText()).trim();
                        if (!currentPlain.equals(still))
                        {
                            return;
                        }
                        chatOverlay.setTranslation(wid, out);
                    });
                });
            }

            // Update previous set for next tick
            prevPresentIds = presentIds;
        });
    }

    // Collect all visible option row texts into a single plain string separated by newlines, sorted by Y then X
    private String collectOptionsPlain(Widget optionsContainer)
    {
        Widget[] rows = optionsContainer.getDynamicChildren();
        if (rows == null || rows.length == 0) rows = optionsContainer.getChildren();
        if (rows == null || rows.length == 0) rows = optionsContainer.getStaticChildren();

        if (rows == null || rows.length == 0) return "";

        List<Widget> ordered = new ArrayList<>(rows.length);
        for (Widget r : rows) if (r != null && !r.isHidden()) ordered.add(r);
        ordered.sort((a, b) -> {
            Rectangle ra = a.getBounds(), rb = b.getBounds();
            int ay = ra == null ? Integer.MAX_VALUE : ra.y;
            int by = rb == null ? Integer.MAX_VALUE : rb.y;
            if (ay != by) return Integer.compare(ay, by);
            int ax = ra == null ? Integer.MAX_VALUE : ra.x;
            int bx = rb == null ? Integer.MAX_VALUE : rb.x;
            return Integer.compare(ax, bx);
        });

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Widget w : ordered)
        {
            if (count >= 6) break;
            String raw = w.getText();
            String plain = stripTags(raw).trim();
            if (plain.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(plain);
            count++;
        }
        return sb.toString();
    }

    // Overload: when options are visible, return only the container to avoid per-row id collisions
    private List<Widget> getRelevantWidgetsAsList(boolean optionsVisible)
    {
        if (optionsVisible)
        {
            Widget container = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
            if (container != null && !container.isHidden())
            {
                if (DEBUG)
                {
                    Rectangle b = container.getBounds();
                    log.info("getRelevant: options container only id={} bounds={}",
                            container.getId(), (b == null ? "null" : (b.x + "," + b.y + " " + b.width + "x" + b.height)));
                }
                List<Widget> single = new ArrayList<>(1);
                single.add(container);
                return single;
            }
        }
        return getRelevantWidgetsAsList(); // fallback to generic path
    }

    // ---------- helpers ----------

    private static String stripTags(String s)
    {
        return s == null ? "" : s.replaceAll("<[^>]*>", "").replace('\u00A0', ' ');
    }

    private boolean isNameWidget(Widget w)
    {
        int id = w.getId();
        int group = (id >>> 16);
        int child = (id & 0xFFFF);
        // ChatLeft/ChatRight name line
        return (group == 231 || group == 217) && child == 4;
    }

    private boolean isLocalPlayerName(String plain)
    {
        String lp = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        if (lp == null) return false;
        String a = normalizeName(plain);
        String b = normalizeName(lp);
        return !a.isEmpty() && a.equals(b);
    }

    private static String normalizeName(String s)
    {
        if (s == null) return "";
        String t = s.replace('\u00A0', ' ').trim();
        return t.toLowerCase();
    }

    private static String truncate(String s)
    {
        if (s == null) return "null";
        s = s.replace("\n", "\\n");
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    private List<Widget> getRelevantWidgetsAsList()
    {
        // Use insertion-ordered map to deduplicate by widget id while preserving order
        LinkedHashMap<Integer, Widget> byId = new LinkedHashMap<>();

        java.util.function.Consumer<Widget> addIfVisible = w -> {
            if (w != null && !w.isHidden())
            {
                byId.put(w.getId(), w); // dedupe by id
            }
        };

        // 1) Chatbox scan mode: if chatbox container 162:566 is visible, scan EVERYTHING under it
        Widget chatboxContainer = client.getWidget(162, 566);
        if (chatboxContainer != null && !chatboxContainer.isHidden())
        {
            List<Widget> texts = collectDescendantTextWidgets(chatboxContainer, 200); // limit to avoid runaway
            if (!texts.isEmpty())
            {
                // Sort by y,x reading order and add to map
                texts.sort((a, b) -> {
                    Rectangle ra = a.getBounds(), rb = b.getBounds();
                    int ay = ra == null ? Integer.MAX_VALUE : ra.y;
                    int by = rb == null ? Integer.MAX_VALUE : rb.y;
                    if (ay != by) return Integer.compare(ay, by);
                    int ax = ra == null ? Integer.MAX_VALUE : ra.x;
                    int bx = rb == null ? Integer.MAX_VALUE : rb.x;
                    return Integer.compare(ax, bx);
                });
                for (Widget w : texts) byId.put(w.getId(), w);

                if (DEBUG)
                {
                    log.info("Chatbox scan: picked {} text widgets under 162:566", texts.size());
                    for (Widget w : texts)
                    {
                        Rectangle b = w.getBounds();
                        String raw = w.getText();
                        log.info("  cbx id={} grp={} child={} len={} bounds={}",
                                w.getId(), (w.getId() >>> 16), (w.getId() & 0xFFFF),
                                raw == null ? -1 : stripTags(raw).trim().length(),
                                (b == null ? "null" : (b.x + "," + b.y + " " + b.width + "x" + b.height)));
                    }
                }
                return new ArrayList<>(byId.values());
            }
            else if (DEBUG)
            {
                log.info("Chatbox scan: 162:566 visible but found no text widgets");
            }
        }

        // 2) Dialogue options (if visible) — keep this fast-path
        Widget dialogOptions = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
        if (dialogOptions != null && !dialogOptions.isHidden())
        {
            Widget[] rows = dialogOptions.getDynamicChildren();
            if (rows == null || rows.length == 0) rows = dialogOptions.getChildren();
            if (rows == null || rows.length == 0) rows = dialogOptions.getStaticChildren();

            if (rows != null)
            {
                // Keep order as on-screen by sorting on Y, then X
                List<Widget> ordered = new ArrayList<>(rows.length);
                for (Widget r : rows) if (r != null && !r.isHidden()) ordered.add(r);
                ordered.sort((a, b) -> {
                    Rectangle ra = a.getBounds(), rb = b.getBounds();
                    int ay = ra == null ? Integer.MAX_VALUE : ra.y;
                    int by = rb == null ? Integer.MAX_VALUE : rb.y;
                    if (ay != by) return Integer.compare(ay, by);
                    int ax = ra == null ? Integer.MAX_VALUE : ra.x;
                    int bx = rb == null ? Integer.MAX_VALUE : rb.x;
                    return Integer.compare(ax, bx);
                });

                int count = 0;
                for (Widget opt : ordered)
                {
                    String t = opt.getText();
                    if (t == null || stripTags(t).trim().isEmpty()) continue;
                    byId.put(opt.getId(), opt);
                    count++;
                    if (count >= 6) break; // usually up to 6
                }
                if (DEBUG) log.info("Options: picked {} row widgets", count);
            }
            return new ArrayList<>(byId.values());
        }

        // 3) Otherwise: normal dialogue detection (left/right chat if present)
        addIfVisible.accept(client.getWidget(231, 4));    // ChatLeft.Name
        addIfVisible.accept(client.getWidget(231, 6));    // ChatLeft.Text
        addIfVisible.accept(client.getWidget(231, 5));    // ChatLeft.Continue

        addIfVisible.accept(client.getWidget(217, 4));    // ChatRight.Name
        addIfVisible.accept(client.getWidget(217, 6));    // ChatRight.Text
        addIfVisible.accept(client.getWidget(217, 5));    // ChatRight.Continue

        // Fallback generic NPC/PLAYER text
        addIfVisible.accept(client.getWidget(WidgetInfo.DIALOG_NPC_TEXT));
        addIfVisible.accept(client.getWidget(WidgetInfo.DIALOG_PLAYER_TEXT));

        return new ArrayList<>(byId.values());
    }

    // Collect visible, text-like widgets under a container (BFS), skipping sprites/icons.
    // Stops after 'limit' found to avoid pathological cases.
    private List<Widget> collectDescendantTextWidgets(Widget root, int limit)
    {
        List<Widget> out = new ArrayList<>();
        ArrayDeque<Widget> q = new ArrayDeque<>();
        q.add(root);

        while (!q.isEmpty() && out.size() < limit)
        {
            Widget cur = q.poll();
            if (cur == null || cur.isHidden()) continue;

            // Enqueue children (all three kinds)
            Widget[] dyn = cur.getDynamicChildren();
            Widget[] ch  = cur.getChildren();
            Widget[] st  = cur.getStaticChildren();
            if (dyn != null) for (Widget w : dyn) if (w != null) q.add(w);
            if (ch  != null) for (Widget w : ch)  if (w != null) q.add(w);
            if (st  != null) for (Widget w : st)  if (w != null) q.add(w);

            // Skip the root itself unless it has text
            if (cur == root) continue;

            // Filter by text presence and bounds (exclude icons/sprites)
            String raw = cur.getText();
            if (raw == null) continue;
            String plain = stripTags(raw).trim();
            if (plain.isEmpty()) continue;

            Rectangle b = cur.getBounds();
            if (b == null || b.width <= 0 || b.height <= 0) continue;

            out.add(cur);
        }
        return out;
    }

    // ---------- cache helpers ----------

    private void initCacheFile()
    {
        try
        {
            String lang = safeLang(config.targetLang());
            Path baseDir = RuneLite.RUNELITE_DIR.toPath().resolve("ai-translator");
            Files.createDirectories(baseDir);
            cacheFile = baseDir.resolve("cache-" + lang + ".json");
            log.info("Cache file: {}", cacheFile.toAbsolutePath());
        }
        catch (Exception e)
        {
            log.error("Failed to prepare cache directory/file: {}", e.getMessage());
            cacheFile = null;
        }
    }

    private String safeLang(String lang)
    {
        if (lang == null || lang.trim().isEmpty()) return "RU";
        return lang.trim().toUpperCase();
    }

    private void loadCacheFromDisk()
    {
        if (cacheFile == null) return;
        try
        {
            if (!Files.exists(cacheFile))
            {
                log.info("No existing cache file found (cold start)");
                return;
            }
            byte[] bytes = Files.readAllBytes(cacheFile);
            String json = new String(bytes, StandardCharsets.UTF_8);
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> loaded = gson.fromJson(json, type);
            if (loaded != null && !loaded.isEmpty())
            {
                // Fill LRU map
                for (Map.Entry<String, String> e : loaded.entrySet())
                {
                    if (e.getKey() != null && e.getValue() != null)
                    {
                        translationCache.put(e.getKey(), e.getValue());
                    }
                }
                log.info("Loaded {} cached translations", translationCache.size());
            }
            else
            {
                log.info("Cache file present but empty");
            }
            cacheDirty = false;
        }
        catch (Exception e)
        {
            log.error("Failed to load cache: {}", e.getMessage());
        }
    }

    private void saveCacheIfDirty()
    {
        if (!cacheDirty) return;
        saveCacheNow();
    }

    private void saveCacheNow()
    {
        if (cacheFile == null) return;
        try
        {
            // Snapshot to minimize time under synchronization
            Map<String, String> snapshot;
            synchronized (translationCache)
            {
                snapshot = new LinkedHashMap<>(translationCache);
            }
            String json = gson.toJson(snapshot);
            Files.write(cacheFile, json.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            cacheDirty = false;
            if (DEBUG) log.info("Saved {} translations to cache", snapshot.size());
        }
        catch (Exception e)
        {
            log.error("Failed to save cache: {}", e.getMessage());
        }
    }

    // Seed per-line cache from the combined options strings (keeps click-through panels consistent)
    private void seedPerLineCache(String combinedPlain, String combinedTranslated)
    {
        if (combinedPlain == null || combinedTranslated == null) return;
        String[] src = combinedPlain.split("\\R");
        String[] dst = combinedTranslated.split("\\R");
        if (src.length != dst.length) return; // mismatch: avoid wrong mappings

        boolean any = false;
        for (int i = 0; i < src.length; i++)
        {
            String s = src[i].trim();
            String t = dst[i].trim();
            if (s.isEmpty() || t.isEmpty()) continue;
            if (!t.equals(translationCache.get(s)))
            {
                translationCache.put(s, t);
                any = true;
            }
        }
        if (any) cacheDirty = true;
    }

    // Update all places where you put items into translationCache to set cacheDirty = true
    // Example inside translateAsync callback:
    // translationCache.put(currentPlain, translated);
    // cacheDirty = true;
}