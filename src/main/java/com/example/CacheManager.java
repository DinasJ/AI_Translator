package com.example;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.runelite.client.RuneLite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Handles persistent translation cache (LRU in-memory + JSON file). */
public class CacheManager
{
    private static final Logger log = LoggerFactory.getLogger(CacheManager.class);
    private static final int MAX_CACHE_ENTRIES = 10_000;

    private final Gson gson = new Gson();
    private Path cacheFile;
    private volatile boolean dirty = false;

    /** LRU cache with eviction. */
    private final Map<String, String> cache =
            java.util.Collections.synchronizedMap(new LinkedHashMap<String, String>(1024, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });

    public void init(String lang)
    {
        try
        {
            String safe = Utils.safeLang(lang);
            Path baseDir = RuneLite.RUNELITE_DIR.toPath().resolve("ai-translator");
            Files.createDirectories(baseDir);
            cacheFile = baseDir.resolve("cache-" + safe + ".json");
            log.info("Cache file: {}", cacheFile.toAbsolutePath());
        }
        catch (Exception e)
        {
            log.error("Failed to prepare cache directory/file: {}", e.getMessage());
            cacheFile = null;
        }
    }

    public void load()
    {
        if (cacheFile == null) return;
        try
        {
            if (!Files.exists(cacheFile))
            {
                log.info("No existing cache file found (cold start)");
                return;
            }
            byte[] bytes = Files.readAllBytes(cacheFile);
            String json = new String(bytes, StandardCharsets.UTF_8);
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> loaded = gson.fromJson(json, type);
            if (loaded != null && !loaded.isEmpty())
            {
                synchronized (cache)
                {
                    cache.clear();
                    cache.putAll(loaded);
                }
                log.info("Loaded {} cached translations", cache.size());
            }
            else
            {
                log.info("Cache file present but empty");
            }
            dirty = false;
        }
        catch (Exception e)
        {
            log.error("Failed to load cache: {}", e.getMessage());
        }
    }

    public void saveIfDirty() { if (dirty) saveNow(); }

    public void saveNow()
    {
        if (cacheFile == null) return;
        try
        {
            Map<String,String> snapshot;
            synchronized (cache)
            {
                snapshot = new LinkedHashMap<>(cache);
            }
            String json = gson.toJson(snapshot);
            Files.write(cacheFile, json.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            dirty = false;
            log.info("Saved {} translations to cache", snapshot.size());
        }
        catch (Exception e)
        {
            log.error("Failed to save cache: {}", e.getMessage());
        }
    }

    public String get(String key)
    {
        synchronized (cache) { return cache.get(key); }
    }

    public void put(String key, String value)
    {
        if (key == null || value == null) return;
        synchronized (cache) { cache.put(key, value); }
        dirty = true;
    }

    public void seedPerLineCache(String combinedPlain, String combinedTranslated)
    {
        if (combinedPlain == null || combinedTranslated == null) return;
        String[] src = combinedPlain.split("\\R");
        String[] dst = combinedTranslated.split("\\R");
        if (src.length != dst.length) return;

        boolean any = false;
        for (int i = 0; i < src.length; i++)
        {
            String s = src[i].trim();
            String t = dst[i].trim();
            if (s.isEmpty() || t.isEmpty()) continue;
            synchronized (cache)
            {
                if (!t.equals(cache.get(s)))
                {
                    cache.put(s, t);
                    any = true;
                }
            }
        }
        if (any) dirty = true;
    }
}