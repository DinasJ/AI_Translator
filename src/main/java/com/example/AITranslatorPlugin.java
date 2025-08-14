package com.example;

import net.runelite.api.Client;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@PluginDescriptor(
        name = "AI Translator",
        description = "Translates OSRS UI skill tooltips to Russian and overlays them",
        tags = {"translation", "russian", "language"}
)
public class AITranslatorPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(AITranslatorPlugin.class);

    // Source tooltip widget (skills)
    private static final int SOURCE_GROUP = 320;
    private static final int SOURCE_CHILD = 28;

    // Boundaries for overlay positioning
    private static final int BOUNDARY_GROUP = 320;
    private static final int BOUNDARY_CHILD = 0;

    @Inject private Client client;
    @Inject private OverlayManager overlayManager;
    @Inject private CyrillicTooltipOverlay tooltipOverlay;

    private String translatedText = "";
    private String lastRawText = "";

    // --- translations ---
    private static final Map<String, String> SKILL_TRANSLATIONS  = new HashMap<>();
    private static final Map<String, String> PHRASE_TRANSLATIONS = new HashMap<>();

    static {
        // Skills
        SKILL_TRANSLATIONS.put("Attack",       "Атака");
        SKILL_TRANSLATIONS.put("Strength",     "Сила");
        SKILL_TRANSLATIONS.put("Defence",      "Защита");
        SKILL_TRANSLATIONS.put("Ranged",       "Рейндж");
        SKILL_TRANSLATIONS.put("Prayer",       "Молитва");
        SKILL_TRANSLATIONS.put("Magic",        "Магия");
        SKILL_TRANSLATIONS.put("Runecraft",    "Руноделие");
        SKILL_TRANSLATIONS.put("Hitpoints",    "Очки здоровья");
        SKILL_TRANSLATIONS.put("Crafting",     "Ремесло");
        SKILL_TRANSLATIONS.put("Mining",       "Добыча");
        SKILL_TRANSLATIONS.put("Smithing",     "Кузнечное дело");
        SKILL_TRANSLATIONS.put("Fishing",      "Рыбалка");
        SKILL_TRANSLATIONS.put("Cooking",      "Кулинария");
        SKILL_TRANSLATIONS.put("Firemaking",   "Разжигание огня");
        SKILL_TRANSLATIONS.put("Woodcutting",  "Лесорубство");
        SKILL_TRANSLATIONS.put("Agility",      "Ловкость");
        SKILL_TRANSLATIONS.put("Herblore",     "Травничество");
        SKILL_TRANSLATIONS.put("Thieving",     "Воровство");
        SKILL_TRANSLATIONS.put("Fletching",    "Лучное дело");
        SKILL_TRANSLATIONS.put("Slayer",       "Слейер");
        SKILL_TRANSLATIONS.put("Farming",      "Фермерство");
        SKILL_TRANSLATIONS.put("Construction", "Строительство");
        SKILL_TRANSLATIONS.put("Hunter",       "Охота");

        // Phrases (cover common variants)
        PHRASE_TRANSLATIONS.put("Next level at:", "Следующий ур.:");
        PHRASE_TRANSLATIONS.put("Remaining XP:",  "Осталось ОП:");
        PHRASE_TRANSLATIONS.put("Remaining:",     "Осталось:");
        PHRASE_TRANSLATIONS.put("Remaining",      "Осталось");
        PHRASE_TRANSLATIONS.put("XP:",            "ОП:");
        PHRASE_TRANSLATIONS.put("XP",             "ОП");
    }

    @Override
    protected void startUp()
    {
        log.info("AI Translator starting up");
        overlayManager.add(tooltipOverlay);
    }

    @Override
    protected void shutDown()
    {
        log.info("AI Translator shutting down");
        overlayManager.remove(tooltipOverlay);
        tooltipOverlay.setVisible(false);
        translatedText = "";
        lastRawText = "";
    }

    @Subscribe
    public void onBeforeRender(BeforeRender event)
    {
        Widget source   = client.getWidget(SOURCE_GROUP, SOURCE_CHILD);
        Widget boundary = client.getWidget(BOUNDARY_GROUP, BOUNDARY_CHILD);

        // Validate boundary
        if (boundary == null)
        {
            if (tooltipOverlay.isVisible())
            {
                tooltipOverlay.setVisible(false);
                log.info("Overlay hidden: boundary (320,0) not found");
            }
            return;
        }

        // Validate source
        if (source == null || source.isSelfHidden())
        {
            if (tooltipOverlay.isVisible())
            {
                tooltipOverlay.setVisible(false);
                log.info("Overlay hidden: source tooltip (320,28) not found/hidden");
            }
            return;
        }

        // Validate positions
        if (source.getCanvasLocation() == null || boundary.getCanvasLocation() == null)
        {
            if (tooltipOverlay.isVisible())
            {
                tooltipOverlay.setVisible(false);
                log.info("Overlay hidden: missing canvas locations");
            }
            return;
        }

        // Capture text before hiding
        final String raw = collectText(source).trim();
        if (raw.isEmpty())
        {
            if (tooltipOverlay.isVisible())
            {
                tooltipOverlay.setVisible(false);
                log.info("Overlay hidden: tooltip text empty");
            }
            return;
        }

        // Capture positions before hiding
        int anchorX = source.getCanvasLocation().getX();
        int anchorY = source.getCanvasLocation().getY();
        int bx = boundary.getCanvasLocation().getX();
        int by = boundary.getCanvasLocation().getY();
        int bw = boundary.getWidth();
        int bh = boundary.getHeight();
        anchorX += 2;
        anchorY += 1;
        // Translate if changed
        if (!Objects.equals(raw, lastRawText))
        {
            log.info("Raw tooltip (320,28): {}", raw.replace("<br>", " | "));
            translatedText = translate(raw);
            tooltipOverlay.setText(translatedText);
            log.info("Translated tooltip: {}", translatedText.replace("<br>", " | "));
            lastRawText = raw;
        }

        // Update overlay anchor/bounds
        tooltipOverlay.updateAnchor(anchorX, anchorY);
        tooltipOverlay.updateBounds(bx, by, bw, bh);

        // Show overlay if not visible
        if (!tooltipOverlay.isVisible())
        {
            tooltipOverlay.setVisible(true);
            log.info("Overlay shown. Anchor=({}, {}) Bounds=({}, {}, {}, {})",
                    anchorX, anchorY, bx, by, bw, bh);
        }
    }

    private String collectText(Widget widget)
    {
        StringBuilder sb = new StringBuilder();
        if (widget.getText() != null && !widget.getText().isEmpty())
            sb.append(widget.getText());

        Widget[] children = widget.getChildren();
        if (children != null)
        {
            for (Widget child : children)
            {
                String childText = collectText(child);
                if (!childText.isEmpty())
                {
                    if (sb.length() > 0) sb.append("<br>");
                    sb.append(childText);
                }
            }
        }
        return sb.toString();
    }

    private String translate(String raw)
    {
        String[] lines = raw.split("<br>");
        StringBuilder out = new StringBuilder();

        for (String line : lines)
        {
            if (line == null || line.trim().isEmpty())
                continue;
            String t = line;

            // Skills
            for (Map.Entry<String, String> e : SKILL_TRANSLATIONS.entrySet())
            {
                if (t.contains(e.getKey()))
                {
                    log.info("Skill match: {} → {}", e.getKey(), e.getValue());
                    t = t.replace(e.getKey(), e.getValue());
                }
            }
            // Phrases (do after skills to catch “XP:” etc.)
            for (Map.Entry<String, String> e : PHRASE_TRANSLATIONS.entrySet())
            {
                if (t.contains(e.getKey()))
                {
                    log.info("Phrase match: {} → {}", e.getKey(), e.getValue());
                    t = t.replace(e.getKey(), e.getValue());
                }
            }

            if (out.length() > 0) out.append("<br>");
            out.append(t);
        }
        return out.toString();
    }

    // Allow overlay to pull current text if needed
    public String getTranslatedText()
    {
        return translatedText;
    }
}
