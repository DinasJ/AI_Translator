package com.example;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.runelite.api.Client;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AITranslatorPlugin — main entry point.
 *
 * This variant uses @Provides to construct several supporting managers so they can be injected.
 */
@PluginDescriptor(
        name = "AI Translator",
        description = "Russian translations",
        enabledByDefault = false
)
public class AITranslatorPlugin extends Plugin {
    private static final Logger log = LoggerFactory.getLogger(AITranslatorPlugin.class);
    private static final boolean DEBUG = true;
    private static final long SAVE_PERIOD_SECONDS = 30;
    private final Map<Integer, Long> lastScriptAtMs = new HashMap<>();
    private static final long SCRIPT_DEDUPE_MS = 50L; // ignore duplicates within 50ms

    // Core runtime-injected things from RuneLite / Guice
    @Inject
    private Client client;
    @Inject
    private ClientThread clientThread;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private TranslationOverlay translationOverlay;
    @Inject
    private ChatOverlay chatOverlay;
    @Inject
    private CyrillicTooltipOverlay tooltipOverlay;
    @Inject
    private AITranslatorConfig config;
    @Inject
    private ContextMenuOverlay contextMenuOverlay;
    @Inject
    private net.runelite.client.eventbus.EventBus eventBus;
    @Inject
    private LocalGlossary localGlossary;
    @Inject
    private MenuTranslator menuTranslator;
    @Inject
    private GlossaryService glossaryService;

    // Provided / injected via the @Provides methods below
    @Inject
    private CacheManager cacheManager;
    @Inject
    private WidgetCollector widgetCollector;
    @Inject
    private DeepLTranslator translator;
    @Inject
    private TranslationManager translationManager;

    // Back-compat / optional local GlossaryService instance (kept for compatibility)
    private GlossaryService glossary;

    private ScheduledExecutorService scheduler;
    private java.util.concurrent.ScheduledFuture<?> periodicSaveTask;

    @Provides
    AITranslatorConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(AITranslatorConfig.class);
    }

    @Provides
    @Named("translator.debug")
    boolean provideTranslatorDebug() {
        return DEBUG;
    }

    @Provides
    @Singleton
    LocalGlossary provideLocalGlossary() {
        return new LocalGlossary();
    }

    @Provides
    @Singleton
    CacheManager provideCacheManager(AITranslatorConfig cfg) {
        CacheManager cm = new CacheManager();
        try {
            cm.init(cfg.targetLang());
            cm.load();
        } catch (Exception ex) {
            if (DEBUG) log.debug("CacheManager init/load failed: {}", ex.toString());
        }
        return cm;
    }

    @Provides
    @Singleton
    WidgetCollector provideWidgetCollector(Client client, @Named("translator.debug") boolean debug) {
        return new WidgetCollector(client, debug);
    }

    @Provides
    @Singleton
    DeepLTranslator provideDeepLTranslator(AITranslatorConfig cfg, GlossaryService glossaryService, CacheManager cacheManager) {
        return new DeepLTranslator(cfg, glossaryService, cacheManager);
    }

    @Provides
    @Singleton
    TranslationManager provideTranslationManager(
            Client client,
            ClientThread clientThread,
            AITranslatorConfig cfg,
            ChatOverlay chatOverlay,
            DeepLTranslator translator,
            GlossaryService glossaryService,
            CacheManager cacheManager,
            WidgetCollector widgetCollector,
            TranslationOverlay translationOverlay,      // <-- inject overlay too
            @Named("translator.debug") boolean debug) {
        return new TranslationManager(
                client,
                clientThread,
                translator,
                glossaryService,
                cacheManager,
                translationOverlay,   // <-- pass overlay into manager
                debug
        );
    }

    @Override
    protected void startUp() {
        ch.qos.logback.classic.Logger pkg =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.example");
        pkg.setLevel(ch.qos.logback.classic.Level.DEBUG);

        overlayManager.add(translationOverlay);
        overlayManager.add(chatOverlay);
        overlayManager.add(tooltipOverlay);
        overlayManager.add(contextMenuOverlay);
        eventBus.register(contextMenuOverlay);
        eventBus.register(this);

        log.info("AI Translator starting up...");

        try {
            glossaryService.loadAll();
            log.info("GlossaryService loaded: {}", glossaryService.isLoaded());
        } catch (Exception e) {
            log.warn("Failed to load glossaries: {}", e.toString());
        }

        try {
            glossary = new GlossaryService();
            log.info("Glossaries loaded into GlossaryService");
        } catch (Exception e) {
            if (DEBUG) log.debug("Glossary load failed: {}", e.toString());
        }

        scheduler = Executors.newSingleThreadScheduledExecutor();

        final long translationTickMs = 50L;
        scheduler.scheduleAtFixedRate(
                () -> clientThread.invokeLater(translationManager::tick),
                0, translationTickMs, TimeUnit.MILLISECONDS
        );

        periodicSaveTask = scheduler.scheduleWithFixedDelay(
                cacheManager::saveIfDirty,
                SAVE_PERIOD_SECONDS,
                SAVE_PERIOD_SECONDS,
                TimeUnit.SECONDS
        );

        log.info("AI Translator started (translationTickMs={}ms)", translationTickMs);
    }

    @Override
    protected void shutDown() {
        if (periodicSaveTask != null) {
            periodicSaveTask.cancel(false);
            periodicSaveTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        overlayManager.remove(translationOverlay);
        overlayManager.remove(chatOverlay);
        overlayManager.remove(tooltipOverlay);
        overlayManager.remove(contextMenuOverlay);
        eventBus.unregister(contextMenuOverlay);
        eventBus.unregister(this);

        if (translationManager != null) translationManager.clearState();
        if (cacheManager != null) cacheManager.saveNow();

        log.info("AI Translator stopped");
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event) {
        int scriptId = event.getScriptId();

        // Keep your dedupe logic here if needed
        if (!translationManager.shouldProcessScript(scriptId)) {
            return;
        }
        if (DEBUG) log.debug("Script {} fired, running widget collector → translation manager", scriptId);

        translationManager.processRelevantWidgets(widgetCollector, scriptId);
    }
}
