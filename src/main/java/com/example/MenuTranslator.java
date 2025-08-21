package com.example;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translates MenuEntry option (action, white) and target (entity, colored).
 * Local glossary is applied synchronously. Optionally triggers DeepL asynchronously to refine text.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class MenuTranslator
{
    private final GlossaryService glossary;
    private final DeepLTranslator deepL;
    private final AITranslatorConfig config;

    // Simple cache to avoid spamming DeepL for identical phrases
    private final ConcurrentHashMap<String, String> deeplCache = new ConcurrentHashMap<>();

    public MenuEntry translate(MenuEntry entry)
    {
        if (entry == null) return null;

        final String option = safe(entry.getOption());
        final String target = safe(entry.getTarget());

        // Determine entity type for target based on MenuAction
        final GlossaryService.Type entityType = mapType(entry.getType());

        final String newOption = translateAction(option);
        final String newTarget = translateTargetPreserveColor(target, entityType);

        // Modify the existing entry in-place (MenuEntry is abstract / implemented by client),
        // because creating a concrete instance isn't portable across RuneLite implementations.
        if (!newOption.equals(option) || !newTarget.equals(target))
        {
            try
            {
                entry.setOption(newOption);
                entry.setTarget(newTarget);
            }
            catch (Exception ex)
            {
                log.debug("Failed to set translated option/target on MenuEntry: {}", ex.toString());
            }
        }

        return entry;
    }

    private GlossaryService.Type mapType(MenuAction type)
    {
        if (type == null) return GlossaryService.Type.DEFAULT;
        switch (type)
        {
            case NPC_FIRST_OPTION:
            case NPC_SECOND_OPTION:
            case NPC_THIRD_OPTION:
            case NPC_FOURTH_OPTION:
            case NPC_FIFTH_OPTION:
            case EXAMINE_NPC:
                return GlossaryService.Type.NPC;

            case ITEM_FIRST_OPTION:
            case ITEM_SECOND_OPTION:
            case ITEM_THIRD_OPTION:
            case ITEM_FOURTH_OPTION:
            case ITEM_FIFTH_OPTION:
            case ITEM_USE:
            case EXAMINE_ITEM:
            case WIDGET_TARGET_ON_WIDGET: // many inventory interactions fall here
                return GlossaryService.Type.ITEM;

            case GAME_OBJECT_FIRST_OPTION:
            case GAME_OBJECT_SECOND_OPTION:
            case GAME_OBJECT_THIRD_OPTION:
            case GAME_OBJECT_FOURTH_OPTION:
            case GAME_OBJECT_FIFTH_OPTION:
            case EXAMINE_OBJECT:
                return GlossaryService.Type.OBJECT;

            case GROUND_ITEM_FIRST_OPTION:
            case GROUND_ITEM_SECOND_OPTION:
            case GROUND_ITEM_THIRD_OPTION:
            case GROUND_ITEM_FOURTH_OPTION:
            case GROUND_ITEM_FIFTH_OPTION:
                return GlossaryService.Type.ITEM;

            default:
                return GlossaryService.Type.DEFAULT;
        }
    }

    private String translateAction(String option)
    {
        if (option.isEmpty()) return option;

        // First try glossary (synchronous)
        String g = glossary.translate(GlossaryService.Type.ACTION, option);

        // If glossary produced translation different from input, preserve original casing style
        if (!equalsIgnoreCaseTrim(g, option))
        {
            return matchCase(option, g);
        }

        return matchCase(option, g);
    }

    private String translateTargetPreserveColor(String target, GlossaryService.Type type)
    {
        if (target.isEmpty()) return target;

        // Extract leading color tag and trailing reset, if present
        String leading = "";
        String trailing = "";
        String inner = target;

        // RuneLite target usually like <col=ffff00>Goblin</col>
        if (inner.matches("^<col=[0-9a-fA-F]{6}>.*</col>$"))
        {
            int gt = inner.indexOf('>') + 1;
            leading = inner.substring(0, gt);
            trailing = "</col>";
            inner = inner.substring(gt, inner.length() - trailing.length());
        }

        String g = glossary.translate(type, inner);

        // If glossary returned an actual translation different from inner, preserve casing
        if (!equalsIgnoreCaseTrim(g, inner))
        {
            g = matchCase(inner, g);
        }

        return leading + g + trailing;
    }

    private void triggerAsyncDeepL(String src, java.util.function.Consumer<String> store)
    {
        try
        {
            final String lang = safeLang(config.targetLang());
            if (lang.isEmpty()) return;
            deepL.translateAsync(src, lang, store);
        }
        catch (Exception ex)
        {
            // never let translation break menu building
            log.debug("DeepL trigger failed: {}", ex.toString());
        }
    }

    private static String cacheKey(String prefix, String s)
    {
        return prefix + "|" + s.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean equalsIgnoreCaseTrim(String a, String b)
    {
        return safe(a).trim().equalsIgnoreCase(safe(b).trim());
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String safeLang(String s)
    {
        if (s == null) return "";
        String x = s.trim().toUpperCase(Locale.ROOT);
        // DeepL expects e.g., RU, EN-GB, etc. Let config drive exact value.
        return x;
    }

    /**
     * Preserve capitalization style from the source when applying the translation.
     * - If source is ALL UPPER -> return target uppercased.
     * - If source is Capitalized (First upper, rest lower) -> capitalize target similarly.
     * - Otherwise return target as-is.
     */
    private static String matchCase(String src, String target)
    {
        if (src == null || src.isEmpty() || target == null || target.isEmpty()) return target;

        if (src.equals(src.toUpperCase(Locale.ROOT)))
        {
            return target.toUpperCase(Locale.ROOT);
        }

        if (Character.isUpperCase(src.charAt(0)) &&
                src.substring(1).equals(src.substring(1).toLowerCase(Locale.ROOT)))
        {
            if (target.length() == 1)
            {
                return target.substring(0, 1).toUpperCase(Locale.ROOT);
            }
            return target.substring(0, 1).toUpperCase(Locale.ROOT) +
                    target.substring(1).toLowerCase(Locale.ROOT);
        }

        return target;
    }
    /**
     * Translates just the option text of a MenuEntry for overlay display,
     * without touching the target or the actual MenuEntry object.
     */
    public String translateOptionOnly(String option, String target)
    {
        if (option == null) return "";

        // First try glossary translation
        String translated = glossary.translate(GlossaryService.Type.ACTION, option);

        // Preserve capitalization style
        translated = matchCase(option, translated);

        // Optionally, you could trigger async DeepL here if needed
        // triggerAsyncDeepL(option, s -> deeplCache.put(cacheKey("overlay", option), s));

        // Logging for debugging
        log.debug("translateOptionOnly: '{}' -> '{}'", option, translated);

        return translated;
    }

}
