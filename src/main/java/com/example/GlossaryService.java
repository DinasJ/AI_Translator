package com.example;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads TSV glossaries from resources or from the RuneLite working dir if present.
 * Keys are normalized (trim, collapse spaces, strip color tags, case-insensitive).
 */
@Slf4j
@Singleton
public class GlossaryService
{
    public enum Type { ACTION, NPC, ITEM, OBJECT, DEFAULT }

    private final Map<Type, Map<String, String>> maps = new EnumMap<>(Type.class);

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
        if (source == null || source.isEmpty()) return source;

        String norm = normalize(source);
        String v = lookup(source, type);

        log.debug("[Glossary] type={} source='{}' norm='{}' -> '{}'",
                type, source, norm, v);

        if (v == null && type == Type.ACTION)
        {
            v = lookup(source, Type.DEFAULT);
            if (v != null)
            {
                log.trace("Fallback to DEFAULT glossary: '{}' -> '{}'", source, v);
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

                    // Accept either tab or 2+ spaces as delimiter
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
}
