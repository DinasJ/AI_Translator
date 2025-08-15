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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final boolean DEBUG = true;

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private OverlayManager overlayManager;
    @Inject private ChatOverlay chatOverlay;
    @Inject private CyrillicTooltipOverlay tooltipOverlay;
    @Inject private AITranslatorConfig config;
    @Inject private DeepLTranslator translator;

    // Cache per original text
    private final Map<String, String> translationCache = new HashMap<>();
    // Track last seen plain text per widget id to only retranslate changes
    private final Map<Integer, String> lastPlainById = new HashMap<>();

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
        log.info("AI Translator started");

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::tick, 0, 400, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void shutDown()
    {
        if (scheduler != null)
        {
            scheduler.shutdown();
            scheduler = null;
        }
        overlayManager.remove(chatOverlay);
        overlayManager.remove(tooltipOverlay);
        translationCache.clear();
        lastPlainById.clear();
        prevPresentIds.clear();
        clientThread.invokeLater(chatOverlay::clearAllTranslations);
        log.info("AI Translator stopped");
    }

    private void tick()
    {
        clientThread.invokeLater(() ->
        {
            tickCounter++;
            List<Widget> widgets = getRelevantWidgetsAsList();
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

            // Detect reappeared ids (present now, not present previously)
            Set<Integer> appeared = new HashSet<>(presentIds);
            appeared.removeAll(prevPresentIds);

            // Proactively reapply translations for reappeared widgets,
            // even if their text hasn't changed (skip player name)
            for (Widget w : widgets)
            {
                if (w == null || w.isHidden()) continue;
                int id = w.getId();
                if (!appeared.contains(id)) continue;

                String raw = w.getText();
                if (raw == null) raw = "";
                String plain = stripTags(raw).trim();

                // Skip translating the player's own name on name widgets
                if (isNameWidget(w) && isLocalPlayerName(plain))
                {
                    if (DEBUG) log.info("Reappeared player name id={} -> skip translation", id);
                    chatOverlay.setTranslation(id, ""); // ensure we don't draw over it
                    continue;
                }

                if (plain.isEmpty())
                {
                    if (DEBUG) log.info("Reappeared id={} but plain text empty; defer redraw", id);
                    continue;
                }

                String cached = translationCache.get(plain);
                if (cached != null)
                {
                    if (DEBUG) log.info("Reappeared id={} -> push cached translation '{}'", id, truncate(cached));
                    chatOverlay.setTranslation(id, cached);
                }
                else
                {
                    if (DEBUG) log.info("Reappeared id={} -> no cache, request translate for '{}'", id, truncate(plain));
                    final String lang = config.targetLang();
                    final int wid = id;
                    final String currentPlain = plain;

                    translator.translateAsync(plain, lang, translated ->
                    {
                        translationCache.put(currentPlain, translated);
                        clientThread.invokeLater(() -> chatOverlay.setTranslation(wid, translated));
                    });
                }
            }

            // Main loop: translate new/changed text as before (skip player name)
            for (Widget w : widgets)
            {
                if (w == null || w.isHidden()) continue;

                String raw = w.getText();
                if (raw == null) raw = "";
                String plain = stripTags(raw).trim();
                int id = w.getId();

                // Skip translating the player's own name on name widgets
                if (isNameWidget(w) && isLocalPlayerName(plain))
                {
                    if (DEBUG) log.info("Skip player name id={} grp={} child={}", id, (id >>> 16), (id & 0xFFFF));
                    lastPlainById.put(id, plain);        // mark as current so we don't trigger requests
                    chatOverlay.setTranslation(id, "");   // ensure overlay doesn't draw over it
                    continue;
                }

                // Do NOT clear translation on empty (widget flicker); keep previous translation
                if (plain.isEmpty())
                {
                    if (DEBUG)
                    {
                        log.info("Empty text but keep last translation (grace) id={} grp={} child={}",
                                id, (id >>> 16), (id & 0xFFFF));
                    }
                    continue;
                }

                String last = lastPlainById.get(id);
                if (plain.equals(last))
                {
                    if (DEBUG)
                    {
                        log.info("No change for id={} grp={} child={}, keep existing translation",
                                id, (id >>> 16), (id & 0xFFFF));
                    }
                    continue;
                }

                lastPlainById.put(id, plain);

                // Serve from cache
                String cached = translationCache.get(plain);
                if (cached != null)
                {
                    if (DEBUG)
                    {
                        log.info("Cache hit id={} grp={} child={} -> '{}'",
                                id, (id >>> 16), (id & 0xFFFF), truncate(cached));
                    }
                    chatOverlay.setTranslation(id, cached);
                    continue;
                }

                if (DEBUG)
                {
                    log.info("Request translate id={} grp={} child={} text='{}'",
                            id, (id >>> 16), (id & 0xFFFF), truncate(plain));
                }

                final String lang = config.targetLang();
                final int wid = id;
                final String currentPlain = plain;

                translator.translateAsync(plain, lang, translated ->
                {
                    if (DEBUG)
                    {
                        log.info("Translate done id={} grp={} child={} -> '{}'",
                                wid, (wid >>> 16), (wid & 0xFFFF), truncate(translated));
                    }
                    translationCache.put(currentPlain, translated);
                    clientThread.invokeLater(() ->
                    {
                        String still = lastPlainById.get(wid);
                        if (!currentPlain.equals(still))
                        {
                            if (DEBUG)
                            {
                                log.info("Drop stale translation id={} (expected='{}', current='{}')",
                                        wid, truncate(currentPlain), truncate(still));
                            }
                            return;
                        }
                        chatOverlay.setTranslation(wid, translated);
                    });
                });
            }

            // Update previous set for next tick
            prevPresentIds = presentIds;
        });
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

        // Left chat (portrait on left)
        addIfVisible.accept(client.getWidget(231, 4));    // ChatLeft.Name
        addIfVisible.accept(client.getWidget(231, 6));    // ChatLeft.Text
        addIfVisible.accept(client.getWidget(231, 5));    // ChatLeft.Continue

        // Right chat (portrait on right)
        addIfVisible.accept(client.getWidget(217, 4));    // ChatRight.Name
        addIfVisible.accept(client.getWidget(217, 6));    // ChatRight.Text
        addIfVisible.accept(client.getWidget(217, 5));    // ChatRight.Continue

        // Dialogue options (each option is a child with text)
        Widget dialogOptions = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
        if (dialogOptions != null && !dialogOptions.isHidden())
        {
            for (Widget child : dialogOptions.getDynamicChildren())
            {
                if (!child.isHidden() && child.getText() != null && !child.getText().trim().isEmpty())
                {
                    byId.put(child.getId(), child);
                }
            }
        }

        // Optional generic NPC/PLAYER text; dedupe prevents duplicates if they alias 217:6/231:6
        addIfVisible.accept(client.getWidget(WidgetInfo.DIALOG_NPC_TEXT));
        addIfVisible.accept(client.getWidget(WidgetInfo.DIALOG_PLAYER_TEXT));

        return new ArrayList<>(byId.values());
    }
}