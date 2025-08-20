package com.example;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.List;

import static com.example.Utils.*;

public class TranslationManager
{
    private static final Logger log = LoggerFactory.getLogger(TranslationManager.class);
    private static final int GRACE_TICKS = 3;

    private final Client client;
    private final ClientThread clientThread;
    private final AITranslatorConfig config;
    private final ChatOverlay chatOverlay;
    private final DeepLTranslator translator;
    private final LocalGlossary localGlossary;
    private final CacheManager cache;
    private final WidgetCollector widgets;
    private final boolean debug;

    private final Map<Integer, String> lastPlainById = new HashMap<>();
    private Set<Integer> prevPresentIds = new HashSet<>();
    private int tickCounter = 0;

    public TranslationManager(
            Client client,
            ClientThread clientThread,
            AITranslatorConfig config,
            ChatOverlay chatOverlay,
            DeepLTranslator translator,
            LocalGlossary localGlossary,
            CacheManager cache,
            WidgetCollector widgets,
            boolean debug)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.config = config;
        this.chatOverlay = chatOverlay;
        this.translator = translator;
        this.localGlossary = localGlossary;
        this.cache = cache;
        this.widgets = widgets;
        this.debug = debug;
    }

    public void clearState()
    {
        lastPlainById.clear();
        prevPresentIds.clear();
        tickCounter = 0;
        chatOverlay.clearAllTranslations();
    }

    public void tick()
    {
        tickCounter++;

        Widget optionsContainer = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
        boolean optionsVisible = optionsContainer != null && !optionsContainer.isHidden();

        List<Widget> list = widgets.getRelevantWidgetsAsList(optionsVisible);
        chatOverlay.updateSourceWidgets(list);

        Set<Integer> presentIds = new HashSet<>();
        for (Widget w : list) presentIds.add(w.getId());

        chatOverlay.markSeen(presentIds, tickCounter);
        chatOverlay.pruneExpired(tickCounter, GRACE_TICKS);

        if (optionsVisible && optionsContainer != null)
        {
            handleOptionsContainer(optionsContainer);
        }

        Set<Integer> appeared = new HashSet<>(presentIds);
        appeared.removeAll(prevPresentIds);
        handleReappearedWidgets(list, appeared);

        handleRegularWidgets(list);

        prevPresentIds = presentIds;
    }

    private void handleOptionsContainer(Widget optionsContainer)
    {
        String combinedPlain = widgets.collectOptionsPlain(optionsContainer);
        int cid = optionsContainer.getId();

        if (combinedPlain == null || combinedPlain.isEmpty()) return;

        String last = lastPlainById.get(cid);
        if (!combinedPlain.equals(last))
        {
            lastPlainById.put(cid, combinedPlain);

            String cached = cache.get(combinedPlain);
            if (cached != null)
            {
                cache.seedPerLineCache(combinedPlain, cached);
                chatOverlay.setTranslation(cid, cached);
            }
            else
            {
                final String lang = config.targetLang();
                log.info("[OPT] Translate request: '{}'", combinedPlain);

                // Use correct enum value (ACTION)
                String manual = localGlossary.lookup(combinedPlain, LocalGlossary.GlossaryType.ACTION);
                if (manual != null)
                {
                    cache.put(combinedPlain, manual);
                    cache.seedPerLineCache(combinedPlain, manual);
                    chatOverlay.setTranslation(cid, manual);
                    log.info("[OPT] Local glossary hit: '{}' -> '{}'", combinedPlain, manual);
                }
                else
                {
                    translator.translateAsync(combinedPlain, lang, LocalGlossary.GlossaryType.ACTION, translated -> {
                        String out = (translated == null || translated.trim().isEmpty()) ? combinedPlain : translated;
                        cache.put(combinedPlain, out);
                        cache.seedPerLineCache(combinedPlain, out);
                        log.info("[OPT] Translated: '{}' -> '{}'", combinedPlain, out);

                        clientThread.invokeLater(() -> {
                            String stillCombined = widgets.collectOptionsPlain(optionsContainer);
                            if (combinedPlain.equals(stillCombined))
                            {
                                chatOverlay.setTranslation(cid, out);
                            }
                        });
                    });
                }
            }
        }
        else
        {
            String cached = cache.get(combinedPlain);
            if (cached != null)
            {
                cache.seedPerLineCache(combinedPlain, cached);
                chatOverlay.setTranslation(cid, cached);
            }
        }
    }

    private void handleReappearedWidgets(List<Widget> widgetsList, Set<Integer> appeared)
    {
        for (Widget w : widgetsList)
        {
            if (w == null || w.isHidden()) continue;
            int id = w.getId();
            if (!appeared.contains(id)) continue;

            String raw = w.getText();
            String plain = stripTags(raw == null ? "" : raw).trim();
            if (plain == null || plain.isEmpty()) continue;

            if (Utils.isNameWidget(w) && isLocalPlayerName(plain))
            {
                chatOverlay.setTranslation(id, "");
                continue;
            }

            String cached = cache.get(plain);
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

            // Use correct enum value (NPC)
            String manual = localGlossary.lookup(currentPlain, LocalGlossary.GlossaryType.NPC);
            if (manual != null)
            {
                cache.put(currentPlain, manual);
                log.info("[WIDGET reappear] Local glossary hit: '{}' -> '{}'", currentPlain, manual);
                clientThread.invokeLater(() -> {
                    String still = stripTags(Optional.ofNullable(w.getText()).orElse("")).trim();
                    if (currentPlain.equals(still))
                    {
                        chatOverlay.setTranslation(wid, manual);
                        lastPlainById.put(wid, currentPlain);
                    }
                });
            }
            else
            {
                translator.translateAsync(currentPlain, lang, LocalGlossary.GlossaryType.NPC, translated -> {
                    String out = (translated == null || translated.trim().isEmpty()) ? currentPlain : translated;
                    cache.put(currentPlain, out);
                    log.info("[WIDGET reappear] Translated: '{}' -> '{}'", currentPlain, out);
                    clientThread.invokeLater(() -> {
                        String still = stripTags(Optional.ofNullable(w.getText()).orElse("")).trim();
                        if (currentPlain.equals(still))
                        {
                            chatOverlay.setTranslation(wid, out);
                            lastPlainById.put(wid, currentPlain);
                        }
                    });
                });
            }
        }
    }

    private void handleRegularWidgets(List<Widget> widgetsList)
    {
        for (Widget w : widgetsList)
        {
            if (w == null || w.isHidden()) continue;

            int id = w.getId();
            int grp = (id >>> 16), child = (id & 0xFFFF);
            if (grp == 219 && child == 1) continue;

            String raw = w.getText();
            String plain = stripTags(raw == null ? "" : raw).trim();
            if (plain == null || plain.isEmpty()) continue;

            if (Utils.isNameWidget(w) && isLocalPlayerName(plain))
            {
                lastPlainById.put(id, plain);
                chatOverlay.setTranslation(id, "");
                continue;
            }

            String last = lastPlainById.get(id);
            if (plain.equals(last))
            {
                String cached = cache.get(plain);
                if (cached != null) chatOverlay.setTranslation(id, cached);
                continue;
            }

            lastPlainById.put(id, plain);

            String cached = cache.get(plain);
            if (cached != null)
            {
                chatOverlay.setTranslation(id, cached);
                continue;
            }

            final String lang = config.targetLang();
            final int wid = id;
            final String currentPlain = plain;
            log.info("[WIDGET] Translate request: '{}'", currentPlain);

            // Use correct enum value (NPC)
            String manual = localGlossary.lookup(currentPlain, LocalGlossary.GlossaryType.NPC);
            if (manual != null)
            {
                cache.put(currentPlain, manual);
                log.info("[WIDGET] Local glossary hit: '{}' -> '{}'", currentPlain, manual);
                clientThread.invokeLater(() -> {
                    String still = stripTags(Optional.ofNullable(w.getText()).orElse("")).trim();
                    if (currentPlain.equals(still))
                    {
                        chatOverlay.setTranslation(wid, manual);
                        lastPlainById.put(wid, currentPlain);
                    }
                });
            }
            else
            {
                translator.translateAsync(currentPlain, lang, LocalGlossary.GlossaryType.NPC, translated -> {
                    String out = (translated == null || translated.trim().isEmpty()) ? currentPlain : translated;
                    cache.put(currentPlain, out);
                    log.info("[WIDGET] Translated: '{}' -> '{}'", currentPlain, out);
                    clientThread.invokeLater(() -> {
                        String still = stripTags(Optional.ofNullable(w.getText()).orElse("")).trim();
                        if (currentPlain.equals(still))
                        {
                            chatOverlay.setTranslation(wid, out);
                            lastPlainById.put(wid, currentPlain);
                        }
                    });
                });
            }
        }
    }

    private boolean isLocalPlayerName(String plain)
    {
        String lp = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
        if (lp == null) return false;
        String a = normalizeName(plain);
        String b = normalizeName(lp);
        return a != null && !a.isEmpty() && a.equals(b);
    }
}
