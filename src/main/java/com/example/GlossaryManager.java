package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Wrapper around LocalGlossary with safe lookups and loading from bundled resources.
 * Exposes convenience helpers so callers need not reference GlossaryType everywhere.
 */
public class GlossaryManager
{
    private static final Logger log = LoggerFactory.getLogger(GlossaryManager.class);

    private final LocalGlossary glossary;

    public GlossaryManager(LocalGlossary glossary)
    {
        this.glossary = glossary != null ? glossary : new LocalGlossary();
    }

    /**
     * Generic safe lookup by type (ACTION, NPC, etc.).
     */
    public Optional<String> lookup(String src, LocalGlossary.GlossaryType type)
    {
        try
        {
            String v = glossary.lookup(src, type);
            return Optional.ofNullable(v);
        }
        catch (Exception ex)
        {
            log.debug("LocalGlossary lookup failed for '{}': {}", src, ex.toString());
            return Optional.empty();
        }
    }

    /**
     * Exact-only lookup by type (no fuzzy). Useful when you only want canonical matches.
     */
    public Optional<String> lookupExact(String src, LocalGlossary.GlossaryType type)
    {
        try
        {
            String v = glossary.lookupExact(src, type);
            return Optional.ofNullable(v);
        }
        catch (Exception ex)
        {
            log.debug("LocalGlossary lookupExact failed for '{}': {}", src, ex.toString());
            return Optional.empty();
        }
    }

    // Convenience sugar so callers don't need to import GlossaryType:
    public Optional<String> lookupAction(String src) { return lookup(src, LocalGlossary.GlossaryType.ACTION); }
    public Optional<String> lookupNpc(String src)    { return lookup(src, LocalGlossary.GlossaryType.NPC); }
    public Optional<String> lookupItem(String src)   { return lookup(src, LocalGlossary.GlossaryType.ITEM); }
    public Optional<String> lookupChat(String src)   { return lookup(src, LocalGlossary.GlossaryType.CHAT); }

    public Optional<String> lookupExactAction(String src) { return lookupExact(src, LocalGlossary.GlossaryType.ACTION); }
    public Optional<String> lookupExactNpc(String src)    { return lookupExact(src, LocalGlossary.GlossaryType.NPC); }

    /**
     * Load TSV from classpath resource into specified glossary type.
     */
    public void loadFromResource(String resourcePath, LocalGlossary.GlossaryType type)
    {
        try (InputStream in = getClass().getResourceAsStream(resourcePath))
        {
            if (in == null)
            {
                log.warn("Bundled glossary not found at {}", resourcePath);
                return;
            }
            glossary.loadFromTSV(in, type);
            log.info("Loaded glossary {} (type={}, entriesNow={})",
                    resourcePath, type, size());
        }
        catch (IOException e)
        {
            log.error("Failed to parse bundled glossary {}", resourcePath, e);
        }
        catch (Exception e)
        {
            log.error("Failed to load glossary {}: {}", resourcePath, e.getMessage());
        }
    }

    // Convenience loaders:
    public void loadActionResource(String resourcePath) { loadFromResource(resourcePath, LocalGlossary.GlossaryType.ACTION); }
    public void loadNpcResource(String resourcePath)    { loadFromResource(resourcePath, LocalGlossary.GlossaryType.NPC); }

    /** Number of entries across all glossary types. */
    public int size()
    {
        try
        {
            return glossary.sizeAll();
        }
        catch (Throwable t)
        {
            log.debug("size() failed: {}", t.toString());
            return 0;
        }
    }

    /** Number of entries for a specific type. */
    public int size(LocalGlossary.GlossaryType type)
    {
        try
        {
            return glossary.size(type);
        }
        catch (Throwable t)
        {
            log.debug("size({}) failed: {}", type, t.toString());
            return 0;
        }
    }
}
