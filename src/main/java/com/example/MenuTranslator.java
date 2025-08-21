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

        final GlossaryService.Type entityType = resolveType(entry.getType());

        final String newOption = translateAction(option);
        final String newTarget = translateTarget(target, entityType);

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

    private GlossaryService.Type resolveType(MenuAction type)
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
            case WIDGET_TARGET_ON_WIDGET:
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

        String g = glossary.translate(GlossaryService.Type.ACTION, option);
        return matchCase(option, g);
    }

    private String translateTarget(String target, GlossaryService.Type type)
    {
        if (target.isEmpty()) return target;

        String leading = "";
        String trailing = "";
        String inner = target;

        // If it already has a single <col=xxxxxx>...</col>, unwrap it
        if (inner.matches("^<col=[0-9a-fA-F]{6}>.*</col>$"))
        {
            int gt = inner.indexOf('>') + 1;
            leading = inner.substring(0, gt);
            trailing = "</col>";
            inner = inner.substring(gt, inner.length() - trailing.length());
        }

        String g = glossary.translate(type, inner);
        g = matchCase(inner, g);

        return leading + g + trailing;
    }

    private void triggerAsyncDeepL(String src, GlossaryService.Type type, java.util.function.Consumer<String> store)
    {
        try
        {
            final String lang = safeLang(config.targetLang());
            if (lang.isEmpty()) return;

            // Map GlossaryService.Type to GlossaryService.Type
            GlossaryService.Type glossaryType;
            switch (type)
            {
                case ACTION:
                    glossaryType = GlossaryService.Type.ACTION;
                    break;
                case NPC:
                    glossaryType = GlossaryService.Type.NPC;
                    break;
                case ITEM:
                    glossaryType = GlossaryService.Type.ITEM;
                    break;
                case OBJECT:
                    glossaryType = GlossaryService.Type.OBJECT;
                    break;
                default:
                    glossaryType = GlossaryService.Type.DEFAULT;
                    break;
            }

            deepL.translateAsync(src, lang, glossaryType, store);
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
        return s.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Preserve capitalization style from the source when applying the translation.
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
     * Translates just the option text for overlay display (doesn't modify MenuEntry).
     */
    public String translateOptionOnly(String option, String target)
    {
        if (option == null) return "";

        String translated = glossary.translate(GlossaryService.Type.ACTION, option);
        translated = matchCase(option, translated);

        log.debug("translateOptionOnly: '{}' -> '{}'", option, translated);

        return translated;
    }
}
