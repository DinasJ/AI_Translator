package com.example;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.*;
import java.util.List;

import static com.example.Utils.stripTags;

public class WidgetCollector
{
    private static final Logger log = LoggerFactory.getLogger(WidgetCollector.class);
    private final Client client;
    private final boolean debug;

    public WidgetCollector(Client client, boolean debug)
    {
        this.client = client;
        this.debug = debug;
    }

    /** Collect all visible option row texts into a single plain string separated by newlines, sorted by Y then X */
    public String collectOptionsPlain(Widget optionsContainer)
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

    /** When options are visible, return only the container to avoid per-row id collisions */
    public List<Widget> getRelevantWidgetsAsList(boolean optionsVisible)
    {
        if (optionsVisible)
        {
            Widget container = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
            if (container != null && !container.isHidden())
            {
                if (debug)
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
        return getRelevantWidgetsAsList();
    }

    public List<Widget> getRelevantWidgetsAsList()
    {
        LinkedHashMap<Integer, Widget> byId = new LinkedHashMap<>();

        java.util.function.Consumer<Widget> addIfVisible = w -> {
            if (w != null && !w.isHidden())
            {
                byId.put(w.getId(), w);
            }
        };

        // Chatbox container 162:566 deep scan
        Widget chatboxContainer = client.getWidget(162, 566);
        if (chatboxContainer != null && !chatboxContainer.isHidden())
        {
            List<Widget> texts = collectDescendantTextWidgets(chatboxContainer, 200);
            if (!texts.isEmpty())
            {
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

                if (debug)
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
            else if (debug)
            {
                log.info("Chatbox scan: 162:566 visible but found no text widgets");
            }
        }

        // Dialogue options (if visible)
        Widget dialogOptions = client.getWidget(WidgetInfo.DIALOG_OPTION_OPTIONS);
        if (dialogOptions != null && !dialogOptions.isHidden())
        {
            Widget[] rows = dialogOptions.getDynamicChildren();
            if (rows == null || rows.length == 0) rows = dialogOptions.getChildren();
            if (rows == null || rows.length == 0) rows = dialogOptions.getStaticChildren();

            if (rows != null)
            {
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
                    if (count >= 6) break;
                }
                if (debug) log.info("Options: picked {} row widgets", count);
            }
            return new ArrayList<>(byId.values());
        }

        // Normal dialogue detection
        addIfVisible.accept(client.getWidget(231, 4));    // ChatLeft.Name
        addIfVisible.accept(client.getWidget(231, 6));    // ChatLeft.Text
        addIfVisible.accept(client.getWidget(231, 5));    // ChatLeft.Continue

        addIfVisible.accept(client.getWidget(217, 4));    // ChatRight.Name
        addIfVisible.accept(client.getWidget(217, 6));    // ChatRight.Text
        addIfVisible.accept(client.getWidget(217, 5));    // ChatRight.Continue

        addIfVisible.accept(client.getWidget(WidgetInfo.DIALOG_NPC_TEXT));
        addIfVisible.accept(client.getWidget(WidgetInfo.DIALOG_PLAYER_TEXT));

        return new ArrayList<>(byId.values());
    }

    /** Collect visible, text-like widgets under a container (BFS), skipping sprites/icons. */
    public List<Widget> collectDescendantTextWidgets(Widget root, int limit)
    {
        List<Widget> out = new ArrayList<>();
        ArrayDeque<Widget> q = new ArrayDeque<>();
        q.add(root);

        while (!q.isEmpty() && out.size() < limit)
        {
            Widget cur = q.poll();
            if (cur == null || cur.isHidden()) continue;

            Widget[] dyn = cur.getDynamicChildren();
            Widget[] ch  = cur.getChildren();
            Widget[] st  = cur.getStaticChildren();
            if (dyn != null) for (Widget w : dyn) if (w != null) q.add(w);
            if (ch  != null) for (Widget w : ch)  if (w != null) q.add(w);
            if (st  != null) for (Widget w : st)  if (w != null) q.add(w);

            if (cur == root) continue;

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
}