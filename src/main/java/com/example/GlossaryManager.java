package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/** Wrapper around LocalGlossary with safe lookups and loading from bundled resources. */
public class GlossaryManager
{
    private static final Logger log = LoggerFactory.getLogger(GlossaryManager.class);

    private final LocalGlossary glossary;

    public GlossaryManager(LocalGlossary glossary)
    {
        this.glossary = glossary != null ? glossary : new LocalGlossary();
    }

    public int size()
    {
        return glossary.size();
    }

    /** Returns translation if exact match found. */
    public Optional<String> lookupExact(String src)
    {
        try
        {
            String v = glossary.lookupExact(src);
            return Optional.ofNullable(v);
        }
        catch (Exception ex)
        {
            log.debug("LocalGlossary lookupExact failed for '{}': {}", src, ex.toString());
            return Optional.empty();
        }
    }

    /** Load TSV from classpath resource, e.g. /glossary/osrs_action_glossary.tsv */
    public void loadFromResource(String resourcePath)
    {
        try (InputStream in = getClass().getResourceAsStream(resourcePath))
        {
            if (in == null)
            {
                log.warn("Bundled glossary not found at {}", resourcePath);
                return;
            }
            glossary.loadFromTSV(in);
            log.info("Loaded bundled local glossary ({} entries) [instance={}]", glossary.size(), System.identityHashCode(glossary));
        }
        catch (IOException e)
        {
            log.error("Failed to parse bundled glossary", e);
        }
        catch (Exception e)
        {
            log.error("Failed to load glossary {}: {}", resourcePath, e.getMessage());
        }
    }
}