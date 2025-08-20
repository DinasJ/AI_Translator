package com.example;

import com.google.inject.Provides;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.runelite.api.Client;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AITranslatorPlugin — patched final for GE widget mutation test.
 * - Annotates GE results by appending a small gray Russian name if available in ruIndex.
 * - Safe: all widget reads/writes happen on the client thread and the annotation is throttled.
 * - Restores original texts on shutdown.
 * Notes:
 *  - This is an exploratory convenience tool. The game/client may overwrite widget text
 *    at any time; we re-apply annotations on GE script callbacks and when the GE widgets load.
 *  - If you want to try setting widget itemId or injecting menu entries, that can be added later.
 */
@PluginDescriptor(
        name = "AI Translator (GE-annotate test)",
        description = "Annotates Grand Exchange results with Russian names (experimental)",
        enabledByDefault = false
)
public class AITranslatorPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(AITranslatorPlugin.class);
    private static final boolean DEBUG = true;
    private static final long SAVE_PERIOD_SECONDS = 30;

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private OverlayManager overlayManager;
    @Inject private ChatOverlay chatOverlay;
    @Inject private CyrillicTooltipOverlay tooltipOverlay;
    @Inject private AITranslatorConfig config;
    @Inject private DeepLTranslator translator;
    @Inject private ContextMenuOverlay contextMenuOverlay;
    @Inject private net.runelite.client.eventbus.EventBus eventBus;
    @Inject private LocalGlossary localGlossary;

    // Managers (may be present or null depending on your project)
    private GlossaryManager glossary;
    private CacheManager cacheManager;
    private WidgetCollector widgetCollector;
    private TranslationManager translationManager;

    private ScheduledExecutorService scheduler;
    private java.util.concurrent.ScheduledFuture<?> periodicSaveTask;

    private volatile int tickCounter = 0;

    // RU index (optional): if available, used to map gameId -> Russian name
    private RuIndexLoader.RuIndex ruIndex = null;

    // Cached GE container widget (the parent that holds the results rows)
    private volatile Widget cachedGeContainer = null;

    // Original texts we changed: widgetId -> originalText (we restore on shutdown)
    private final Map<Integer, String> geOriginalText = new HashMap<>();

    // Annotation throttling / guard
    private volatile long lastAnnotateMs = 0L;
    private static final long ANNOTATE_THROTTLE_MS = 180L; // milliseconds

    @Provides
    AITranslatorConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(AITranslatorConfig.class);
    }

    @Provides
    @Singleton
    LocalGlossary provideLocalGlossary()
    {
        return new LocalGlossary();
    }

    @Override
    protected void startUp()
    {
        // Set package-level logger to debug during development
        ch.qos.logback.classic.Logger pkg =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.example");
        pkg.setLevel(ch.qos.logback.classic.Level.DEBUG);

        overlayManager.add(chatOverlay);
        overlayManager.add(tooltipOverlay);
        overlayManager.add(contextMenuOverlay);
        eventBus.register(contextMenuOverlay);
        eventBus.register(this);

        // Ensure tooltip overlay starts hidden
        try { tooltipOverlay.setVisible(false); } catch (Throwable ignored) {}

        log.info("AI Translator (GE-annotate) starting up...");

        // Try to load optional ru_index_merged.json from resources (best-effort)
        try
        {
            String resourcePath = "/item-indexes/ru_index_merged.json";
            try (InputStream is = AITranslatorPlugin.class.getResourceAsStream(resourcePath))
            {
                if (is != null)
                {
                    Path tmp = Files.createTempFile("ru_index_merged-", ".json");
                    tmp.toFile().deleteOnExit();
                    Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
                    RuIndexLoader loader = new RuIndexLoader();
                    try
                    {
                        ruIndex = loader.loadIndex(tmp);
                        if (DEBUG) log.debug("Loaded RU index (items={})", ruIndex.itemsByIndex == null ? 0 : ruIndex.itemsByIndex.size());
                    }
                    catch (IOException ex)
                    {
                        log.warn("Failed to parse RU index resource: {}", ex.toString());
                        ruIndex = null;
                    }
                }
                else
                {
                    if (DEBUG) log.debug("RU index resource not found at {}", resourcePath);
                }
            }
        }
        catch (Throwable t)
        {
            log.warn("Could not load ru_index resource: {}", t.toString());
            ruIndex = null;
        }

        // Managers + scheduler kept minimal for this test; existing components may be initialized elsewhere
        try
        {
            glossary = new GlossaryManager(localGlossary);

            // Load action glossary
            localGlossary.loadFromTSV(
                    getClass().getResourceAsStream("/glossary/osrs_action_glossary.tsv"),
                    LocalGlossary.GlossaryType.ACTION
            );

            // Load NPC glossary
            localGlossary.loadFromTSV(
                    getClass().getResourceAsStream("/glossary/osrs_npc_glossary.tsv"),
                    LocalGlossary.GlossaryType.NPC
            );

            // (optional: add ITEM / CHAT TSVs if you make them later)
        }
        catch (Exception e)
        {
            if (DEBUG) log.debug("Glossary load failed: {}", e.toString());
        }


        cacheManager = new CacheManager();
        cacheManager.init(config.targetLang());
        cacheManager.load();

        widgetCollector = new WidgetCollector(client, DEBUG);
        translationManager = new TranslationManager(
                client,
                clientThread,
                config,
                chatOverlay,
                translator,
                localGlossary,
                cacheManager,
                widgetCollector,
                DEBUG
        );

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> clientThread.invokeLater(translationManager::tick), 0, 700, TimeUnit.MILLISECONDS);
        periodicSaveTask = scheduler.scheduleWithFixedDelay(cacheManager::saveIfDirty, SAVE_PERIOD_SECONDS, SAVE_PERIOD_SECONDS, TimeUnit.SECONDS);

        log.info("AI Translator (GE-annotate) started");
    }

    @Override
    protected void shutDown()
    {
        // Restore any modified widget texts before quitting
        try
        {
            clientThread.invokeLater(this::restoreOriginalGeAnnotations);
        }
        catch (Exception ignored) {}

        // Hide tooltip overlay immediately
        try { tooltipOverlay.setVisible(false); } catch (Throwable ignored) {}

        if (periodicSaveTask != null) { periodicSaveTask.cancel(false); periodicSaveTask = null; }
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }

        overlayManager.remove(chatOverlay);
        overlayManager.remove(tooltipOverlay);
        overlayManager.remove(contextMenuOverlay);
        eventBus.unregister(contextMenuOverlay);
        eventBus.unregister(this);

        if (translationManager != null) translationManager.clearState();

        log.info("AI Translator (GE-annotate) stopped");
    }

    // ------------------- GE container discovery -------------------

    // Heuristic to find the GE results container: looks for text "What would you like to buy?"
    // Must be called on client thread.
    private Widget findGeResultsContainer()
    {
        final String headerEng = "What would you like to buy?";
        final String headerShort = "What would"; // short fallback

        // scan a reasonable range of root groups
        for (int group = 0; group < 300; group++)
        {
            Widget root = client.getWidget(group, 0);
            if (root == null) continue;

            Deque<Widget> stack = new ArrayDeque<>();
            stack.push(root);

            while (!stack.isEmpty())
            {
                Widget w = stack.pop();
                if (w == null) continue;

                try
                {
                    String text = w.getText();
                    if (text != null && (text.contains(headerEng) || text.contains(headerShort)))
                    {
                        Widget parent = w.getParent();
                        if (parent != null)
                        {
                            Widget grand = parent.getParent();
                            if (grand != null) return grand;
                            return parent;
                        }
                        return w;
                    }
                }
                catch (Exception ignored) { }

                Widget[] dyn = w.getDynamicChildren();
                Widget[] ch  = w.getChildren();
                Widget[] st  = w.getStaticChildren();
                if (dyn != null) for (Widget c : dyn) if (c != null) stack.push(c);
                if (ch  != null) for (Widget c : ch)  if (c != null) stack.push(c);
                if (st  != null) for (Widget c : st)  if (c != null) stack.push(c);
            }
        }
        return null;
    }

    // ------------------- GE annotation logic -------------------

    /**
     * Annotate GE result rows by appending " <col=aaaaaa>RU_NAME</col>" to visible text.
     * Must be invoked on the client thread. Throttled internally.
     */
    private void annotateGeResults()
    {
        // Ensure running on client thread; if not, schedule and return
        if (!client.isClientThread())
        {
            clientThread.invokeLater(this::annotateGeResults);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastAnnotateMs < ANNOTATE_THROTTLE_MS)
        {
            return;
        }
        lastAnnotateMs = now;

        Widget ge = findGeResultsContainer();
        if (ge != null)
        {
            Widget[] rows = ge.getDynamicChildren();
            if (rows != null && rows.length > 0)
            {
                rows[0].setText("<col=ff0000>ТЕ</col>");
                rows[0].revalidate();
                log.debug("Mutated first GE row text to ТЕСТ");
            }
        }

        Widget[] rows = ge.getDynamicChildren();
        if (rows == null || rows.length == 0) rows = ge.getChildren();
        if (rows == null || rows.length == 0) rows = ge.getStaticChildren();
        if (rows == null) return;

        for (Widget row : rows)
        {
            try
            {
                if (row == null || row.isHidden()) continue;

                // Determine gameId for this row (look at row or its children)
                int itemId = row.getItemId();
                if (itemId <= 0)
                {
                    Widget[] dyn = row.getDynamicChildren();
                    if (dyn != null)
                    {
                        for (Widget w : dyn)
                        {
                            if (w == null) continue;
                            if (w.getItemId() > 0)
                            {
                                itemId = w.getItemId();
                                break;
                            }
                        }
                    }
                }

                String ruName = "";
                if (itemId > 0 && ruIndex != null)
                {
                    ruName = ruIndex.getRuName(itemId);
                }

                if (ruName == null) ruName = "";
                if (ruName.isEmpty()) continue;

                String orig = row.getText();
                if (orig == null) orig = "";

                // Skip if already annotated
                String annMarker = "<col=aaaaaa>" + ruName + "</col>";
                if (orig.contains(annMarker)) continue;

                // Save original only once
                geOriginalText.putIfAbsent(row.getId(), orig);

                String newText = orig + " " + annMarker;

                // Apply if changed
                if (!newText.equals(orig))
                {
                    try
                    {
                        row.setText(newText);
                        row.revalidate(); // hint the client to redraw
                        if (DEBUG) log.debug("Annotated GE row id={} itemId={} -> '{}'", row.getId(), itemId, ruName);
                    }
                    catch (Exception ex)
                    {
                        if (DEBUG) log.debug("Failed to setText on widget {}: {}", row.getId(), ex.toString());
                    }
                }
            }
            catch (Exception ex)
            {
                if (DEBUG) log.debug("annotateGeResults row error: {}", ex.toString());
            }
        }
    }

    /**
     * Restore original widget texts that we changed in annotateGeResults.
     * Must be called on client thread (we wrap invocation where needed).
     */
    private void restoreOriginalGeAnnotations()
    {
        if (!client.isClientThread())
        {
            clientThread.invokeLater(this::restoreOriginalGeAnnotations);
            return;
        }

        if (geOriginalText.isEmpty()) return;

        // Iterate over a copy to avoid concurrent modifications
        for (Integer wid : geOriginalText.keySet().toArray(new Integer[0]))
        {
            try
            {
                String orig = geOriginalText.get(wid);
                int group = wid >>> 16;
                int child = wid & 0xFFFF;
                Widget w = client.getWidget(group, child);
                if (w != null)
                {
                    try
                    {
                        String curr = w.getText();
                        if (curr != null && !curr.equals(orig))
                        {
                            w.setText(orig);
                            w.revalidate();
                            if (DEBUG) log.debug("Restored widget id={} to original text", wid);
                        }
                    }
                    catch (Exception ex)
                    {
                        if (DEBUG) log.debug("Failed to restore widget {}: {}", wid, ex.toString());
                    }
                }
            }
            catch (Exception ignored) {}
            finally
            {
                geOriginalText.remove(wid);
            }
        }
    }

    // ------------------- Event handlers -------------------

    @Subscribe
    public void onScriptCallbackEvent(ScriptCallbackEvent ev)
    {
        String name = ev.getEventName();
        if (name == null) return;

        // Trigger annotation attempts on GE script updates
        if (name.contains("GeOffers") || name.contains("geOffers") || name.contains("GeOffersSide"))
        {
            clientThread.invokeLater(() -> {
                try { annotateGeResults(); } catch (Exception ignored) {}
            });
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded ev)
    {
        // When widgets are loaded, try to cache GE container and annotate once
        clientThread.invokeLater(() ->
        {
            try
            {
                Widget found = findGeResultsContainer();
                if (found != null)
                {
                    cachedGeContainer = found;
                    if (DEBUG) log.debug("Cached GE container id={}", found.getId());
                    annotateGeResults();
                }
            }
            catch (Exception ignored) {}
        });
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed ev)
    {
        // If the GE container closed, clear cache & restore annotations (best-effort)
        clientThread.invokeLater(() ->
        {
            try
            {
                Widget cached = cachedGeContainer;
                if (cached != null)
                {
                    int closedGroupId = ev.getGroupId();
                    int cachedGroupId = cached.getId() >>> 16; // extract group id from full widget id

                    if (closedGroupId == cachedGroupId)
                    {
                        if (DEBUG) log.debug("Cached GE container closed -> restoring annotations");
                        restoreOriginalGeAnnotations();
                        cachedGeContainer = null;
                    }
                }
            }
            catch (Exception ignored) {}
        });
    }

    @Subscribe
    public void onClientTick(ClientTick evt)
    {
        // Light-weight: if we have a cached GE container, attempt periodic re-annotation (throttled)
        Widget ge = cachedGeContainer;
        if (ge != null && !ge.isHidden())
        {
            // schedule annotate on client thread (annotateGeResults will early-exit if too soon)
            clientThread.invokeLater(() -> {
                try { annotateGeResults(); } catch (Exception ignored) {}
            });
        }

        // Opportunistic rescan: if cached container is null, occasionally try to find it
        tickCounter++;
        if (cachedGeContainer == null && (tickCounter % 40 == 0))
        {
            clientThread.invokeLater(() ->
            {
                try
                {
                    Widget found = findGeResultsContainer();
                    if (found != null)
                    {
                        cachedGeContainer = found;
                        if (DEBUG) log.debug("Found & cached GE container id={}", found.getId());
                        annotateGeResults();
                    }
                }
                catch (Exception ignored) {}
            });
        }

        // Update Cyrillic tooltip overlay from the current vanilla tooltip state
        try
        {
            updateCyrillicTooltipOverlay();
        }
        catch (Exception ignored) {}
    }

    // ------------------- Utilities -------------------

    private static String stripTags(String s)
    {
        return s == null ? "" : s.replaceAll("<[^>]*>", "").replace('\u00A0', ' ').trim();
    }

    /**
     * Reads the current vanilla tooltip text and wires it into CyrillicTooltipOverlay.
     * - Anchors at the standard tooltip origin (320, 28).
     * - Clamps into a boundary starting at x=320 to avoid covering left UI.
     * - Hides when no tooltip is present or when the right-click menu is open.
     */
    private void updateCyrillicTooltipOverlay()
    {
        // Hide overlay if the context menu is open (to avoid overlap)
        if (client.isMenuOpen())
        {
            if (tooltipOverlay.isVisible()) tooltipOverlay.setVisible(false);
            return;
        }

        // Best-effort pull of the current tooltip text (may be null/empty)
        String tip = null;
        try
        {
            tip = getVanillaTooltipReflective();
        }
        catch (Throwable ignored)
        {
            // If unavailable on this client, keep overlay hidden
            if (tooltipOverlay.isVisible()) tooltipOverlay.setVisible(false);
            return;
        }

        if (tip == null || tip.trim().isEmpty())
        {
            if (tooltipOverlay.isVisible()) tooltipOverlay.setVisible(false);
            return;
        }

        // Prepare anchor and bounds. The overlay expects:
        // - anchor at the vanilla tooltip origin (approx 320,28).
        // - bounds starting at x=320 spanning to the right edge of the canvas.
        final int canvasW = client.getCanvasWidth();
        final int canvasH = client.getCanvasHeight();
        final int anchorX = 320;
        final int anchorY = 28;

        int boundX = 320;
        int boundY = 0;
        int boundW = Math.max(1, canvasW - boundX);
        int boundH = Math.max(1, canvasH - boundY);

        tooltipOverlay.updateAnchor(anchorX, anchorY);
        tooltipOverlay.updateBounds(boundX, boundY, boundW, boundH);
        tooltipOverlay.setText(tip);
        tooltipOverlay.setVisible(true);
    }

    /**
     * Attempt to read the game's current tooltip text via reflection to support multiple client API variants.
     * Tries a small set of known method names and returns the first non-empty String.
     */
    private String getVanillaTooltipReflective()
    {
        final String[] candidates = {
                "getTooltipText",
                "getTooltip",
                "getMouseTooltip",
                "getMouseOverText",
                "getMouseoverText"
        };
        for (String name : candidates)
        {
            try
            {
                java.lang.reflect.Method m = client.getClass().getMethod(name);
                if (m.getReturnType() == String.class)
                {
                    Object val = m.invoke(client);
                    if (val instanceof String)
                    {
                        String s = (String) val;
                        if (s != null && !s.trim().isEmpty())
                        {
                            return s;
                        }
                    }
                }
            }
            catch (Throwable ignored) { }
        }
        return null;
    }
}
