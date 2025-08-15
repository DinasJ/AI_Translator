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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@PluginDescriptor(
        name = "AI Translator",
        description = "Translates chat/dialogue text using DeepL and overlays it on screen",
        enabledByDefault = false
)
public class AITranslatorPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(AITranslatorPlugin.class);

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ChatOverlay chatOverlay;

    @Inject
    private CyrillicTooltipOverlay tooltipOverlay;

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
        log.info("AI Translator started");
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(chatOverlay);
        overlayManager.remove(tooltipOverlay);
        log.info("AI Translator stopped");
    }

    // All translation-driven text pushing and widget scanning has been removed because
    // ChatOverlay now computes its own clip based on visible chat widgets during render.
}
