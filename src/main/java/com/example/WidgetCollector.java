package com.example;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.*;
import java.util.List;

import static com.example.Utils.*;

public class WidgetCollector
{
    private static final Logger log = LoggerFactory.getLogger(WidgetCollector.class);
    private final Client client;
    private final boolean debug;

    public static class CollectedWidget
    {
        private final Widget widget;
        private final GlossaryService.Type type;
        private final String plainText;

        public CollectedWidget(Widget widget, GlossaryService.Type type, String plainText)
        {
            this.widget = widget;
            this.type = type;
            this.plainText = plainText;
        }

        public Widget widget() { return widget; }
        public GlossaryService.Type type() { return type; }
        public String plainText() { return plainText; }
        public int id() { return widget.getId(); }
    }

    public WidgetCollector(Client client, boolean debug)
    {
        this.client = client;
        this.debug = debug;
    }

    /* ========= Entry point for TranslationManager ========= */

    public List<CollectedWidget> getRelevantCollectedWidgets(int scriptId)
    {
        if (debug) log.debug("[Collector] scan start for script={}", scriptId);
        Map<String, CollectedWidget> collected = new LinkedHashMap<>();

        switch (scriptId)
        {
            case 80:
                collectChatbox(collected);
                collectOptions(collected);
                collectFallbackDialogue(collected);
                break;
            case 222:
                collectChatbox(collected);
                collectFallbackDialogue(collected);
                break;
            case 664:
                collectOptions(collected);
                break;
            case 6009:
                collectFallbackDialogue(collected);
                break;
            default:
                if (debug) log.trace("Script {} not relevant for widget collection", scriptId);
                break;
        }

        // Remove blacklisted
        collected.values().removeIf(cw ->
                cw.widget() == null ||
                        cw.widget().isHidden() ||
                        isBlacklisted(cw.widget().getId()));

        // Sort visually
        List<CollectedWidget> result = new ArrayList<>(collected.values());
        result.sort((a, b) -> {
            Rectangle ra = a.widget().getBounds();
            Rectangle rb = b.widget().getBounds();
            if (ra == null || rb == null) return 0;
            int dy = Integer.compare(ra.y, rb.y);
            return dy != 0 ? dy : Integer.compare(ra.x, rb.x);
        });

        if (debug)
        {
            if (result.isEmpty()) log.debug("[Collector] no relevant widgets found for script={}", scriptId);
            else log.info("[Collector] picked {} widgets for script={}", result.size(), scriptId);
        }

        return result;
    }

    private boolean isBlacklisted(int widgetId)
    {
        return widgetId == WidgetInfo.DIALOG_PLAYER.getId();
    }

    /* ========= Collectors ========= */

    private void collectChatbox(Map<String, CollectedWidget> map)
    {
        Widget chatboxContainer = client.getWidget(162, 36);
        if (chatboxContainer == null || chatboxContainer.isHidden())
        {
            if (debug) log.trace("Chatbox container not found or hidden");
            return;
        }

        List<Widget> texts = collectDescendantTextWidgets(chatboxContainer, 200);
        texts.sort(WIDGET_BOUNDS_COMPARATOR);

        for (Widget w : texts)
        {
            String plain = toPlainText(w);
            if (plain == null || plain.trim().isEmpty()) continue;

            GlossaryService.Type guess = classifyWidget(w, plain);
            String key = uniqueKey(w, plain);

            map.put(key, new CollectedWidget(w, guess, plain));
            if (debug)
            {
                log.debug("[Collector][CHATBOX] collected id={} bounds={} plain='{}' type={}",
                        w.getId(), w.getBounds(), truncate(plain), guess);
            }
        }
    }

    private void collectOptions(Map<String, CollectedWidget> map)
    {
        Widget optContainer = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
        if (optContainer == null || optContainer.isHidden()) return;

        Widget[] rows = optContainer.getDynamicChildren();
        if (rows == null || rows.length == 0) rows = optContainer.getChildren();
        if (rows == null) return;

        for (Widget row : rows)
        {
            if (row == null || row.isHidden()) continue;
            String plain = toPlainText(row);
            if (plain == null || plain.trim().isEmpty()) continue;

            String key = uniqueKey(row, plain);
            map.put(key, new CollectedWidget(row, GlossaryService.Type.ACTION, plain));

            if (debug) log.debug("[Collector][OPTIONS] row id={} plain='{}'", row.getId(), truncate(plain));
        }
    }

    private void collectFallbackDialogue(Map<String, CollectedWidget> map)
    {
        addIfVisible(map, client.getWidget(231, 4), GlossaryService.Type.NPC);
        addIfVisible(map, client.getWidget(231, 6), GlossaryService.Type.DIALOG);
        addIfVisible(map, client.getWidget(231, 5), GlossaryService.Type.UI);

        addIfVisible(map, client.getWidget(217, 4), GlossaryService.Type.NPC);
        addIfVisible(map, client.getWidget(217, 6), GlossaryService.Type.DIALOG);
        addIfVisible(map, client.getWidget(217, 5), GlossaryService.Type.UI);

        addIfVisible(map, client.getWidget(WidgetInfo.DIALOG_NPC_TEXT), GlossaryService.Type.DIALOG);
        addIfVisible(map, client.getWidget(WidgetInfo.DIALOG_PLAYER_TEXT), GlossaryService.Type.DIALOG);

        Widget playerName = client.getWidget(WidgetInfo.DIALOG_PLAYER);
        if (playerName != null && !playerName.isHidden() && debug)
        {
            log.debug("Skipping player name widget id={} plain='{}'",
                    playerName.getId(), truncate(toPlainText(playerName)));
        }
    }

    /* ========= Utilities ========= */

    public List<Widget> collectDescendantTextWidgets(Widget root, int limit)
    {
        List<Widget> out = new ArrayList<>();
        ArrayDeque<Widget> q = new ArrayDeque<>();
        q.add(root);

        while (!q.isEmpty() && out.size() < limit)
        {
            Widget cur = q.poll();
            if (cur == null || cur.isHidden()) continue;

            for (Widget c : getAllChildren(cur))
            {
                if (c != null) q.add(c);
            }
            if (cur == root) continue;

            String plain = toPlainText(cur);
            if (plain == null || plain.trim().isEmpty()) continue;

            Rectangle b = cur.getBounds();
            if (b == null || b.width <= 0 || b.height <= 0) continue;

            out.add(cur);
        }
        return out;
    }

    private void addIfVisible(Map<String, CollectedWidget> map, Widget w, GlossaryService.Type type)
    {
        if (w == null || w.isHidden()) return;

        String plain = toPlainText(w);
        if (plain == null || plain.trim().isEmpty()) return;

        String key = uniqueKey(w, plain);
        map.put(key, new CollectedWidget(w, type, plain));
    }

    /* ========= Stable unique key ========= */
    private String uniqueKey(Widget w, String plain)
    {
        return w.getId() + ":" + System.identityHashCode(w) + ":" + plain.hashCode();
    }
}
