package com.example;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private final ConcurrentHashMap<String, String> deeplCache = new ConcurrentHashMap<>();

    private static final Pattern COLOR_PATTERN = Pattern.compile("(<col=[0-9a-fA-F]+>.*?</col>)");
    private static final Pattern COLOR_OR_TAG_PATTERN = Pattern.compile("(<col=[0-9a-fA-F]+>|</col>)");
    private static final Pattern LEVEL_TAG_PATTERN = Pattern.compile("\\(([^)]+)\\)");

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
            case PLAYER_FIRST_OPTION:
            case PLAYER_SECOND_OPTION:
            case PLAYER_THIRD_OPTION:
            case PLAYER_FOURTH_OPTION:
            case PLAYER_FIFTH_OPTION:
            case PLAYER_SIXTH_OPTION:
            case PLAYER_SEVENTH_OPTION:
            case PLAYER_EIGHTH_OPTION:
            case RUNELITE_PLAYER:
                return GlossaryService.Type.PLAYER;
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
        return translateWithColors(option, GlossaryService.Type.ACTION);
    }

    private String translateTarget(String target, GlossaryService.Type type)
    {
        if (target.isEmpty()) return target;

        String base;
        if (target.contains("<col="))
        {
            base = translateWithColors(target, type);
        }
        else
        {
            base = tokenizeAndTranslate(target, type);
        }

        // Finally, translate structured tags like (level-18), (rating-2000), etc.
        return translateLevelTags(base);
    }

    /**
     * Handles strings with multiple <col=xxxxxx> ... </col> segments.
     * Outer text → provided type, inner <col> segments → DEFAULT.
     */
    private String translateWithColors(String text, GlossaryService.Type outerType)
    {
        StringBuilder sb = new StringBuilder();
        Matcher matcher = COLOR_OR_TAG_PATTERN.matcher(text);

        int last = 0;
        GlossaryService.Type currentType = outerType;

        while (matcher.find())
        {
            // Text before the tag
            if (matcher.start() > last)
            {
                String before = text.substring(last, matcher.start());
                sb.append(tokenizeAndTranslate(before, currentType));
            }

            String tag = matcher.group(1);
            sb.append(tag);

            if (tag.startsWith("<col="))
            {
                currentType = GlossaryService.Type.DEFAULT; // switch inside colored text
            }
            else if ("</col>".equals(tag))
            {
                currentType = outerType; // reset after closing
            }

            last = matcher.end();
        }

        // Remaining text after last tag
        if (last < text.length())
        {
            String after = text.substring(last);
            sb.append(tokenizeAndTranslate(after, currentType));
        }

        return sb.toString();
    }

    private String translatePlain(String text, GlossaryService.Type type)
    {
        if (text == null || text.isEmpty()) return text;

        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("[A-Za-z]+|\\d+|[^A-Za-z\\d]+").matcher(text);

        while (m.find())
        {
            String token = m.group();
            if (token.matches("[A-Za-z]+")) // word
            {
                String g = glossary.translate(type, token);
                if (equalsIgnoreCaseTrim(g, token))
                {
                    g = glossary.translate(GlossaryService.Type.DEFAULT, token);
                }
                sb.append(matchCase(token, g));
            }
            else
            {
                sb.append(token);
            }
        }

        return sb.toString();
    }

    private void triggerAsyncDeepL(String src, GlossaryService.Type type, java.util.function.Consumer<String> store)
    {
        try
        {
            final String lang = safeLang(config.targetLang());
            if (lang.isEmpty()) return;

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

    private String tokenizeAndTranslate(String text, GlossaryService.Type type)
    {
        if (text == null || text.isEmpty()) return text;

        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("[A-Za-z]+|\\d+|[^A-Za-z\\d]+").matcher(text);
        while (m.find())
        {
            String token = m.group();
            if (token.matches("[A-Za-z]+"))
            {
                String translated = glossary.translate(type, token);
                if (equalsIgnoreCaseTrim(translated, token))
                {
                    translated = glossary.translate(GlossaryService.Type.DEFAULT, token);
                }
                sb.append(matchCase(token, translated));
            }
            else
            {
                sb.append(token);
            }
        }
        return sb.toString();
    }

    /**
     * Detects and translates structured tags like (level-18), (rating-2000) without
     * touching player names or other outer text.
     */
    private String translateLevelTags(String text)
    {
        Matcher matcher = LEVEL_TAG_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find())
        {
            String inner = matcher.group(1); // e.g. "level-18"
            String[] parts = inner.split("-", 2);
            if (parts.length > 0)
            {
                String keyword = parts[0].trim(); // "level"
                String rest = parts.length > 1 ? "-" + parts[1] : "";
                String translatedKeyword = glossary.translate(GlossaryService.Type.DEFAULT, keyword);
                matcher.appendReplacement(sb, "(" + translatedKeyword + rest + ")");
            }
            else
            {
                matcher.appendReplacement(sb, matcher.group());
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
