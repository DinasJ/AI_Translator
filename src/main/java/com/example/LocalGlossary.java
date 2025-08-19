package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Local glossary with both case-sensitive and case-insensitive lookups.
 *
 * - Loads TSV (tab or multi-space separated).
 * - Keeps a case-sensitive map and a normalized (lowercase/collapsed) map.
 * - lookup(...) performs exact then substring (longest) matches (useful for target matching).
 * - lookupExact(...) performs only exact matches (case-sensitive then normalized) and DOES NOT do substring matching.
 */
@Singleton
public class LocalGlossary
{
    private static final Logger log = LoggerFactory.getLogger(LocalGlossary.class);

    private final Map<String, String> caseSensitiveMap = new ConcurrentHashMap<>();
    private final Map<String, String> normalizedMap = new ConcurrentHashMap<>();

    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final char BOM = '\uFEFF';

    public synchronized void loadFromTSV(InputStream in) throws IOException
    {
        if (in == null)
        {
            log.warn("LocalGlossary.loadFromTSV: input stream is null");
            return;
        }

        caseSensitiveMap.clear();
        normalizedMap.clear();

        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            String line;
            int lineNo = 0;
            int added = 0;

            while ((line = r.readLine()) != null)
            {
                lineNo++;
                if (line.isEmpty()) continue;

                if (lineNo == 1 && line.charAt(0) == BOM)
                {
                    line = line.substring(1);
                }

                String raw = line.trim();
                if (raw.isEmpty()) continue;
                if (raw.startsWith("#") || raw.startsWith("//")) continue;

                String left = null, right = null;

                String[] parts = raw.split("\t", 2);
                if (parts.length >= 2)
                {
                    left = parts[0].trim();
                    right = parts[1].trim();
                }
                else
                {
                    parts = MULTI_SPACE.split(raw, 2);
                    if (parts.length >= 2)
                    {
                        left = parts[0].trim();
                        right = parts[1].trim();
                    }
                }

                if (left == null || right == null) continue;

                // Skip header rows like "EN RU"
                if (lineNo == 1)
                {
                    String leftUpper = left.toUpperCase(Locale.ROOT);
                    String rightUpper = right.toUpperCase(Locale.ROOT);
                    if (("EN".equals(leftUpper) || "SOURCE".equals(leftUpper)) &&
                            ("RU".equals(rightUpper) || "TARGET".equals(rightUpper) || "RUS".equals(rightUpper)))
                    {
                        log.debug("Skipping header line: {}", raw);
                        continue;
                    }
                }

                String keyNorm = normalizeKey(left);

                if (!left.isEmpty() && !right.isEmpty())
                {
                    // keep original left (case-sensitive) as key
                    caseSensitiveMap.put(left, right);
                }
                if (!keyNorm.isEmpty())
                {
                    normalizedMap.put(keyNorm, right);
                }
                added++;
            }

            log.info("LocalGlossary: loaded {} entries ({} lines read)", added, lineNo);
        }
    }

    /**
     * Tolerant lookup used by UI code when searching for single-word or partial matches.
     *
     * Steps:
     *  1) Case-sensitive exact match
     *  2) Case-insensitive normalized exact match
     *  3) Case-sensitive longest substring match
     *  4) Case-insensitive longest substring match
     *
     * This function intentionally returns substring matches — it's useful when looking up
     * a target token (e.g. "Logs") inside a longer input.
     */
    public String lookup(String src)
    {
        if (src == null || src.trim().isEmpty())
        {
            if (log.isDebugEnabled()) log.debug("LocalGlossary: lookup called with empty src");
            return null;
        }

        // 1. Case-sensitive exact match
        String v = caseSensitiveMap.get(src);
        if (v != null)
        {
            if (log.isDebugEnabled()) log.debug("LocalGlossary case-sensitive exact hit: '{}' -> '{}'", src, v);
            return v;
        }

        // 2. Case-insensitive exact match
        String norm = normalizeKey(src);
        v = normalizedMap.get(norm);
        if (v != null)
        {
            if (log.isDebugEnabled()) log.debug("LocalGlossary case-insensitive exact hit: '{}' (norm='{}') -> '{}'", src, norm, v);
            return v;
        }

        // 3. Substring / longest match (case-sensitive)
        String bestKey = null;
        for (String key : caseSensitiveMap.keySet())
        {
            if (src.contains(key))
            {
                if (bestKey == null || key.length() > bestKey.length())
                {
                    bestKey = key;
                }
            }
        }

        if (bestKey != null)
        {
            v = caseSensitiveMap.get(bestKey);
            if (log.isDebugEnabled()) log.debug("LocalGlossary substring hit: '{}' contains '{}' -> '{}'", src, bestKey, v);
            return v;
        }

        // 4. Substring / longest match (case-insensitive via normalized map)
        String bestNorm = null;
        for (String key : normalizedMap.keySet())
        {
            if (norm.contains(key))
            {
                if (bestNorm == null || key.length() > bestNorm.length())
                {
                    bestNorm = key;
                }
            }
        }

        if (bestNorm != null)
        {
            v = normalizedMap.get(bestNorm);
            if (log.isDebugEnabled()) log.debug("LocalGlossary substring hit (norm): '{}' (norm='{}') contains '{}' -> '{}'", src, norm, bestNorm, v);
            return v;
        }

        if (log.isDebugEnabled()) log.debug("LocalGlossary miss: '{}' (norm='{}')", src, norm);
        return null;
    }

    /**
     * Exact-only lookup used for combined-phrase detection.
     *
     * Steps:
     *  1) Case-sensitive exact match (map key equals src)
     *  2) Case-insensitive normalized exact match (normalize(src) equals normalized key)
     *
     * NOTE: does NOT attempt substring matching. Use this when you want to know whether
     * the user provided an explicit combined phrase (e.g., "Light Logs") rather than a part match.
     */
    public String lookupExact(String src)
    {
        if (src == null || src.trim().isEmpty())
        {
            if (log.isDebugEnabled()) log.debug("LocalGlossary.lookupExact called with empty src");
            return null;
        }

        // 1) case-sensitive exact
        String v = caseSensitiveMap.get(src);
        if (v != null)
        {
            if (log.isDebugEnabled()) log.debug("LocalGlossary case-sensitive exact (lookupExact): '{}' -> '{}'", src, v);
            return v;
        }

        // 2) normalized exact
        String norm = normalizeKey(src);
        v = normalizedMap.get(norm);
        if (v != null)
        {
            if (log.isDebugEnabled()) log.debug("LocalGlossary case-insensitive exact (lookupExact): '{}' (norm='{}') -> '{}'", src, norm, v);
            return v;
        }

        if (log.isDebugEnabled()) log.debug("LocalGlossary lookupExact miss: '{}' (norm='{}')", src, norm);
        return null;
    }

    public int size()
    {
        return caseSensitiveMap.size() + normalizedMap.size();
    }

    public void clear()
    {
        caseSensitiveMap.clear();
        normalizedMap.clear();
    }

    // ----------------- helpers -----------------

    private static String stripTags(String s)
    {
        return (s == null) ? "" : TAGS.matcher(s).replaceAll("");
    }

    public static String normalizeKey(String key)
    {
        if (key == null) return "";
        String t = key;

        // strip any tags like <col=ff9040> or <col="00ffff">, </col>, etc.
        t = t.replaceAll("<[^>]+>", "");

        // normalize spaces: NBSP -> space, collapse runs of whitespace
        t = t.replace('\u00A0', ' ');     // NBSP to normal space
        t = t.replace('-', ' ');          // optional: "Talk-to" ≈ "Talk to"
        t = t.replaceAll("\\s+", " ");    // collapse whitespace

        t = t.trim().toLowerCase(Locale.ROOT);
        return t;
    }

    /**
     * Adjust target string capitalization to match source pattern.
     */
    private static String matchCase(String src, String target)
    {
        if (src.isEmpty() || target.isEmpty()) return target;

        // All uppercase
        if (src.equals(src.toUpperCase(Locale.ROOT)))
        {
            return target.toUpperCase(Locale.ROOT);
        }

        // Capitalized (first letter upper, rest lower)
        if (Character.isUpperCase(src.charAt(0)) &&
                src.substring(1).equals(src.substring(1).toLowerCase(Locale.ROOT)))
        {
            return target.substring(0, 1).toUpperCase(Locale.ROOT) +
                    target.substring(1).toLowerCase(Locale.ROOT);
        }

        // Default: return as-is
        return target;
    }
}
