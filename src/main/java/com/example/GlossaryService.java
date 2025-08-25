package com.example;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads TSV glossaries from resources or from the RuneLite working dir if present.
 * Keys are normalized (trim, collapse spaces, strip color tags, case-insensitive).
 */
@Slf4j
@Singleton
public class GlossaryService
{
    public enum Type { ACTION, NPC, ITEM, OBJECT, PLAYER, UI, DIALOG, DEFAULT }
    private final CacheManager cacheManager = new CacheManager();
    private final Map<Type, Map<String, String>> maps = new EnumMap<>(Type.class);
    private static final Pattern LEVEL_TAG_PATTERN =
            Pattern.compile("\\(([^-]+)-(\\d+)\\)");
    @Getter
    private volatile boolean loaded = false;

    @Inject
    public GlossaryService() {}

    /**
     * Lookup in a specific glossary type.
     */
    private String lookup(String source, Type type)
    {
        if (source == null) return null;
        Map<String, String> dict = maps.get(type);
        if (dict == null) return null;

        String norm = normalize(source);
        return dict.get(norm);
    }

    /**
     * Load all glossaries (only once).
     */
    public synchronized void loadAll()
    {
        if (loaded)
        {
            log.debug("Glossaries already loaded, skipping reload");
            return;
        }

        clear();
        loadTsv(Type.ACTION, "glossary/osrs_action_glossary.tsv");
        loadTsv(Type.NPC,    "glossary/osrs_npc_glossary.tsv");
        loadTsv(Type.ITEM,   "glossary/osrs_item_glossary.tsv");
        loadTsv(Type.OBJECT, "glossary/osrs_object_glossary.tsv");
        loadTsv(Type.DEFAULT,"glossary/osrs_default_glossary.tsv");

        loaded = true;
        log.info("Glossaries loaded: {}", mapsSummary());
    }

    public void clear()
    {
        maps.clear();
        for (Type t : Type.values())
        {
            maps.put(t, new ConcurrentHashMap<>());
        }
        loaded = false;
    }

    public String translate(Type type, String source)
    {
        if (source == null || source.isEmpty())
            return source;

        String norm = normalize(source);
        Map<String, String> dict = maps.get(type);

        if (dict == null)
        {
            log.warn("[Glossary] Missing glossary map for type={}, source='{}' (norm='{}')", type, source, norm);
            return source; // fallback: return original text
        }

        String v = dict.get(norm);

        log.debug("[Glossary] type={} source='{}' norm='{}' -> '{}'",
                type, source, norm, v);

        if (v == null && type == Type.ACTION)
        {
            Map<String, String> defDict = maps.get(Type.DEFAULT);
            if (defDict != null)
            {
                v = defDict.get(norm);
                if (v != null)
                {
                    log.trace("Fallback to DEFAULT glossary: '{}' -> '{}'", source, v);
                }
            }
        }

        return v != null ? v : source;
    }


    private void loadTsv(Type type, String resourcePath)
    {
        try (InputStream in = openResource(resourcePath))
        {
            if (in == null)
            {
                log.warn("Glossary not found: {}", resourcePath);
                return;
            }

            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
            {
                String line;
                int lineNo = 0;
                while ((line = br.readLine()) != null)
                {
                    lineNo++;
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;

                    // 🔽 Skip header (first row if it looks like EN / RU / etc.)
                    if (lineNo == 1 && line.matches("(?i).*\\bEN\\b.*\\bRU\\b.*"))
                    {
                        log.debug("Skipping header row in {}", resourcePath);
                        continue;
                    }

                    String[] parts = line.split("\\t|\\s{2,}");
                    if (parts.length < 2)
                    {
                        log.debug("Skipping malformed glossary line {}: {}", lineNo, line);
                        continue;
                    }

                    final String en = normalize(parts[0]);
                    final String ru = parts[1].trim();
                    if (!en.isEmpty() && !ru.isEmpty())
                    {
                        maps.get(type).put(en, ru);
                    }
                }
            }
        }
        catch (Exception ex)
        {
            log.error("Failed loading glossary {}: {}", resourcePath, ex.toString());
        }
    }

    private InputStream openResource(String resourcePath) throws IOException
    {
        File f = new File(resourcePath);
        if (f.exists() && f.isFile())
        {
            log.info("Loading glossary from file: {}", f.getAbsolutePath());
            return new FileInputStream(f);
        }
        InputStream in = GlossaryService.class.getClassLoader().getResourceAsStream(resourcePath);
        if (in != null)
        {
            log.info("Loading glossary from resources: {}", resourcePath);
        }
        return in;
    }

    public static String normalize(String s)
    {
        // Strip RuneLite color tags and nbsp, collapse spaces, lowercase
        String x = s
                .replace('\u00A0', ' ')
                .replaceAll("<col=[0-9a-fA-F]{6}>", "")
                .replaceAll("</col>", "")
                .replaceAll("<[^>]+>", "") // any leftover tags
                .trim();
        x = x.replaceAll("\\s+", " ");
        return x.toLowerCase(Locale.ROOT);
    }

    private String mapsSummary()
    {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Type, Map<String, String>> e : maps.entrySet())
        {
            parts.add(e.getKey() + "=" + e.getValue().size());
        }
        return String.join(", ", parts);
    }
    public String translateInline(Type type, String source)
    {
        if (source == null || source.isEmpty()) return source;

        String result = source;

        // Apply glossary replacements word by word
        for (Map.Entry<String, String> entry : maps.get(type).entrySet())
        {
            String en = entry.getKey();
            String ru = entry.getValue();

            // simple whole-word replace, case-insensitive
            result = result.replaceAll("(?i)\\b" + java.util.regex.Pattern.quote(en) + "\\b", ru);
        }

        return result;
    }
    private String translateSuffixTags(Type type, String s)
    {
        Matcher m = LEVEL_TAG_PATTERN.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find())
        {
            String tag = m.group(1); // keep original case
            String num = m.group(2);

            String translated = lookupCaseSensitive(tag);
            if (translated == null)
            {
                translated = tag; // fallback: leave as-is
            }

            m.appendReplacement(sb, "(" + translated + "-" + num + ")");
        }
        m.appendTail(sb);
        return sb.toString();
    }
    private String lookupCaseSensitive(String tag)
    {
        Map<String, String> dict = maps.get(Type.DEFAULT);

        // try exact case first
        String res = dict.get(tag);
        if (res != null) return res;

        // try lowercase key
        res = dict.get(tag.toLowerCase(Locale.ROOT));
        if (res != null) return res;

        // try uppercase key
        res = dict.get(tag.toUpperCase(Locale.ROOT));
        if (res != null) return res;

        // try capitalized key
        String cap = tag.substring(0,1).toUpperCase(Locale.ROOT) + tag.substring(1).toLowerCase(Locale.ROOT);
        return dict.get(cap);
    }
    public String tryTranslate(Type type, String source)
    {
        try
        {
            String result = translate(type, source);

            // If glossary returned null OR just echoed the original → treat as miss
            if (result == null || result.equals(source))
            {
                return null;
            }

            return result;
        }
        catch (Exception e)
        {
            log.debug("[Glossary] Failed for type={}, source='{}' ({})", type, source, e.toString());
            return null;
        }
    }
    // CACHE
    public void initCache(String lang)
    {
        cacheManager.init(lang);
        cacheManager.load();
    }

    public String tryCache(String targetLang, String source)
    {
        return cacheManager.get(source);
    }

    public void putCache(String targetLang, String source, String translated)
    {
        cacheManager.put(source, translated);
    }
}
