package com.example;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static com.example.Utils.*;

/**
 * Central manager for collecting, translating and caching RuneLite widget texts.
 */
@Singleton
public class TranslationManager
{
    private static final Logger log = LoggerFactory.getLogger(TranslationManager.class);

    private final Client client;
    private final ClientThread clientThread;
    private final DeepLTranslator deepl;
    private final GlossaryService glossary;
    private final CacheManager cacheManager;
    private final boolean debug;

    // text → ongoing request timestamp
    private final Map<String, Long> inFlight = new ConcurrentHashMap<>();
    // widgetId → last plain text
    private final Map<Integer, String> lastSeen = new ConcurrentHashMap<>();

    private final TranslationOverlay translationOverlay;

    @Inject
    public TranslationManager(
            Client client,
            ClientThread clientThread,
            DeepLTranslator deepl,
            GlossaryService glossary,
            CacheManager cacheManager,
            TranslationOverlay translationOverlay,
            boolean debug)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.deepl = deepl;
        this.glossary = glossary;
        this.cacheManager = cacheManager;
        this.translationOverlay = translationOverlay;
        this.debug = debug;
    }

    /** Called every scheduled tick from AITranslatorPlugin. */
    public void tick()
    {
        if (debug)
            log.debug("[TM.tick] inFlight={} lastSeen={} cacheDirtySaveCheck", inFlight.size(), lastSeen.size());
        cacheManager.saveIfDirty();
    }

    /**
     * Translate a single widget, apply from cache immediately if possible,
     * otherwise send async request.
     */
    public void updateWidgetTranslation(WidgetCollector.CollectedWidget cw)
    {
        if (cw == null) return;

        if (debug)
        {
            log.debug("[TM.updateWidgetTranslation] widgetId={} type={} plain='{}'",
                    cw.id(), cw.type(), truncate(cw.plainText()));
        }

        applyCachedOrQueue(cw.widget(), cw.plainText(), cw.type());
    }

    public void updateWidgetTranslation(Widget widget, GlossaryService.Type type)
    {
        if (widget == null) return;

        // Special-case: dialog options container — iterate children instead of combining
        if (isOptionsContainer(widget))
        {
            // Descend to find actual text-bearing descendants (rows)
            List<Widget> textRows = new ArrayList<>(6);
            ArrayDeque<Widget> q = new ArrayDeque<>();
            q.add(widget);

            while (!q.isEmpty() && textRows.size() < 6)
            {
                Widget cur = q.poll();
                if (cur == null || cur.isHidden()) continue;

                // Enqueue both dynamic and static children
                Widget[] dyn = cur.getDynamicChildren();
                if (dyn != null) for (Widget c : dyn) if (c != null) q.add(c);
                Widget[] stat = cur.getChildren();
                if (stat != null) for (Widget c : stat) if (c != null) q.add(c);

                if (cur == widget) continue;

                String plain = Utils.toPlainText(cur);
                Rectangle b = cur.getBounds();
                if (plain != null && !plain.trim().isEmpty() &&
                        b != null && b.width > 0 && b.height > 0)
                {
                    textRows.add(cur);
                }
            }

            // Sort visually to keep order stable
            textRows.sort((a, b) -> {
                Rectangle ra = a.getBounds(), rb = b.getBounds();
                if (ra == null || rb == null) return 0;
                int dy = Integer.compare(ra.y, rb.y);
                return dy != 0 ? dy : Integer.compare(ra.x, rb.x);
            });

            if (!textRows.isEmpty())
            {
                for (Widget r : textRows)
                {
                    String plain = Utils.toPlainText(r);
                    if (plain == null || plain.trim().isEmpty()) {
                        translationOverlay.updateTranslation(r, null);
                        lastSeen.remove(r.getId());
                        continue;
                    }

                    if (debug)
                        log.debug("[TM.updateWidgetTranslation][OPTIONS:row] widgetId={} plain='{}'",
                                r.getId(), truncate(plain));

                    applyCachedOrQueue(r, plain, GlossaryService.Type.ACTION);
                }
            }
            else
            {
                // Fallback: some clients render all lines in container text
                String combined = Utils.toPlainText(widget);
                if (combined == null || combined.trim().isEmpty())
                {
                    translationOverlay.updateTranslation(widget, null);
                    lastSeen.remove(widget.getId());
                }
                else
                {
                    if (debug)
                        log.debug("[TM.updateWidgetTranslation][OPTIONS:combined] widgetId={} plain='{}'",
                                widget.getId(), truncate(combined));

                    applyCachedOrQueue(widget, combined, GlossaryService.Type.ACTION);
                }
            }
            return;
        }

        // Normal widgets
        String plain = Utils.toPlainText(widget);

        // PATCH: if empty → clear overlay instead of skipping silently
        if (plain == null || plain.isEmpty())
        {
            if (debug)
                log.debug("[TM.updateWidgetTranslation] clear empty widgetId={} type={}",
                        (widget != null ? widget.getId() : -1), type);

            if (widget != null)
            {
                translationOverlay.updateTranslation(widget, null);
                lastSeen.remove(widget.getId());
            }
            return;
        }

        if (debug)
            log.debug("[TM.updateWidgetTranslation] widgetId={} type={} plain='{}'",
                    widget.getId(), type, truncate(plain));

        applyCachedOrQueue(widget, plain, type);
    }

    /**
     * Called from the WidgetCollector results each game tick.
     */
    // Helpers for options container handling

    private boolean isOptionsContainer(Widget w)
    {
        if (w == null) return false;
        int id = w.getId();
        // WidgetInfo.DIALOG_OPTION_OPTIONS (219:1) — guard by both id and group/child for safety
        int group = (id >>> 16), child = (id & 0xFFFF);
        return id == net.runelite.api.widgets.WidgetInfo.DIALOG_OPTION_OPTIONS.getId()
                || (group == 219 && child == 1);
    }
    /**
     * Applies cached translation to the overlay or queues an async translation by widgetId+text.
     * This path is used when we don't hold a strong Widget reference but know its id and text.
     */
    public void applyCachedOrQueue(Widget widget, String plain, GlossaryService.Type type)
    {
        if (plain == null || plain.isEmpty())
        {
            if (debug) log.debug("[TM.applyCachedOrQueue] clear empty id={} type={}", widget, type);
            translationOverlay.updateTranslation(widget, null); // PATCH: actively clear
            lastSeen.remove(widget.getId());
            return;
        }

        int wid = widget.getId();
        String prev = lastSeen.get(wid);
        if (prev != null && !prev.equals(plain))
        {
            if (debug)
                log.debug("[TM.applyCachedOrQueue] text changed for widgetId={} old='{}' new='{}' -> clearing overlay",
                        wid, truncate(prev), truncate(plain));
            clientThread.invoke(() -> translationOverlay.updateTranslation(widget, null)); // clear old
        }
        lastSeen.put(wid, plain);

        String norm = normalizeName(plain);
        if (debug) log.debug("[TM.applyCachedOrQueue] id={} type={} plain='{}' norm='{}'", widget, type, truncate(plain), truncate(norm));

        // 1) Try cache (plain and normalized)
        String cached = getCached(plain, norm);
        if (cached != null)
        {
            if (debug)
                log.debug("[CACHE APPLY] widget={} key='{}' -> '{}'",
                        widget, truncate(plain), truncate(cached));

            clientThread.invoke(() -> translationOverlay.updateTranslation(widget, cached));
            return;
        }

        // 2) Deduplicate in-flight requests based on normalized key
        if (inFlight.containsKey(norm))
        {
            if (debug)
                log.debug("[INFLIGHT] widget={} key='{}'", widget, truncate(plain));
            return;
        }

        inFlight.put(norm, System.currentTimeMillis());
        if (debug) log.debug("[TM.applyCachedOrQueue] queued translateAsync id={} lang=RU type={} key='{}'", widget, type, truncate(norm));

        // 3) Async translation
        deepl.translateAsync(plain, "RU", type, translated -> {
            inFlight.remove(norm);

            if (translated == null || translated.isEmpty())
            {
                log.warn("[DEEPL FAIL] widget={} key='{}'", widget, truncate(plain));
                return;
            }

            String withGlossary = glossary.translate(type, translated);

            cacheManager.put(plain, withGlossary);
            cacheManager.put(norm, withGlossary);

            if (debug)
                log.debug("[CACHE STORE] widget={} plain='{}' norm='{}' -> '{}'",
                        widget, truncate(plain), truncate(norm), truncate(withGlossary));

            clientThread.invoke(() -> translationOverlay.updateTranslation(widget, withGlossary));
        });
    }

    public void clearState()
    {
        inFlight.clear();
        lastSeen.clear();
    }

    private String getCached(String plain, String norm)
    {
        String v1 = cacheManager.get(plain);
        if (v1 != null)
        {
            if (debug) log.debug("[CACHE HIT:plain] key='{}' -> '{}'", truncate(plain), truncate(v1));
            return v1;
        }

        String v2 = cacheManager.get(norm);
        if (v2 != null)
        {
            if (debug) log.debug("[CACHE HIT:norm] key='{}' -> '{}'", truncate(norm), truncate(v2));
            return v2;
        }

        if (debug) log.debug("[CACHE MISS] plain='{}' norm='{}'", truncate(plain), truncate(norm));
        return null;
    }

    public boolean shouldProcessScript(int scriptId)
    {
        return scriptId == 80 || scriptId == 664 || scriptId == 6009 || scriptId == 222;
    }


    // Step 2: actually process the widgets (no more hardcoded IDs)
    public void processRelevantWidgets(WidgetCollector collector, int scriptId)
    {
        if (debug) log.debug("Processing relevant widgets for translation");

        try
        {
            clientThread.invokeLater(() -> {
                List<WidgetCollector.CollectedWidget> widgets = collector.getRelevantCollectedWidgets(scriptId);
                if (debug) log.debug("[TM] collected {} widgets to update", widgets.size());

                for (WidgetCollector.CollectedWidget cw : widgets)
                {
                    updateWidgetTranslation(cw.widget(), cw.type());
                }

                try {
                    if (client.getCanvas() != null) client.getCanvas().repaint();
                } catch (Throwable ignored) {}
            });
        }
        catch (Exception ex)
        {
            if (debug) log.debug("clientThread.invoke failed during widget processing: {}", ex.toString());
            clientThread.invokeLater(() -> {});
        }
    }
}
