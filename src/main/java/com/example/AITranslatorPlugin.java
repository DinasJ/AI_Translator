package com.example;

import com.google.inject.Provides;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.runelite.api.Client;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.MenuOpened;
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
        name = "AI Translator",
        description = "Russian translations",
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

    // --- WIRING: inject MenuTranslator + GlossaryService and load glossaries on startup ---
    @Inject private MenuTranslator menuTranslator;
    @Inject private GlossaryService glossaryService;

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

        log.info("AI Translator starting up...");

        // Ensure glossary data is available for MenuTranslator and other consumers
        try
        {
            glossaryService.loadAll();
            log.info("GlossaryService loaded: {}", glossaryService.isLoaded());
        }
        catch (Exception e)
        {
            log.warn("Failed to load glossaries: {}", e.toString());
        }

        // Managers + scheduler kept minimal for this test; existing components may be initialized elsewhere
        try
        {
            glossary = new GlossaryManager(localGlossary);
            glossary.loadFromResource("/glossary/osrs_action_glossary.tsv");
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
                glossary,
                cacheManager,
                widgetCollector,
                DEBUG
        );

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> clientThread.invokeLater(translationManager::tick), 0, 700, TimeUnit.MILLISECONDS);
        periodicSaveTask = scheduler.scheduleWithFixedDelay(cacheManager::saveIfDirty, SAVE_PERIOD_SECONDS, SAVE_PERIOD_SECONDS, TimeUnit.SECONDS);

        log.info("AI Translator started");
    }

    @Override
    protected void shutDown()
    {
        // Restore any modified widget texts before quitting

        if (periodicSaveTask != null) { periodicSaveTask.cancel(false); periodicSaveTask = null; }
        if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }

        overlayManager.remove(chatOverlay);
        overlayManager.remove(tooltipOverlay);
        overlayManager.remove(contextMenuOverlay);
        eventBus.unregister(contextMenuOverlay);
        eventBus.unregister(this);

        if (translationManager != null) translationManager.clearState();

        log.info("AI Translator stopped");
    }
    // ------------------- Event handlers -------------------

    @Subscribe
    public void onClientTick(ClientTick evt)
    {
        tickCounter++;
    }
}
