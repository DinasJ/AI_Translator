package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local glossary with both case-sensitive and case-insensitive lookups.
 *
 * Now supports multiple glossary "types" (ACTION, NPC, ITEM, etc.),
 * so ambiguous words like "Cook" can be kept separate per context.
 */
@Singleton
public class LocalGlossary
{
    private static final Logger log = LoggerFactory.getLogger(LocalGlossary.class);

    public enum GlossaryType {
        ACTION,
        NPC,
        ITEM,
        OBJECT,
        CHAT,
        DEFAULT
    }

    // Maps are stored per glossary type
    private final Map<GlossaryType, Map<String, String>> caseSensitiveMaps = new ConcurrentHashMap<>();
    private final Map<GlossaryType, Map<String, String>> normalizedMaps   = new ConcurrentHashMap<>();

    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final char BOM = '\uFEFF';

    public LocalGlossary()
    {
        for (GlossaryType type : GlossaryType.values())
        {
            caseSensitiveMaps.put(type, new ConcurrentHashMap<>());
            normalizedMaps.put(type, new ConcurrentHashMap<>());
        }
    }

    /**
     * Load glossary entries from an InputStream containing TSV into a specific glossary type.
     */
    public synchronized void loadFromTSV(InputStream in, GlossaryType type) throws IOException
    {
        if (in == null)
        {
            log.warn("LocalGlossary.loadFromTSV: input stream is null for type {}", type);
            return;
        }

        Map<String, String> caseSensitiveMap = caseSensitiveMaps.get(type);
        Map<String, String> normalizedMap = normalizedMaps.get(type);

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

                // Remove BOM on first line if present
                if (lineNo == 1 && line.charAt(0) == BOM)
                {
                    line = line.substring(1);
                }

                String raw = line.trim();
                if (raw.isEmpty()) continue;

                // Skip comments
                if (raw.startsWith("#") || raw.startsWith("//")) continue;

                String left = null, right = null;

                // Split by tab first
                String[] parts = raw.split("\t", 2);
                if (parts.length >= 2)
                {
                    left = parts[0].trim();
                    right = parts[1].trim();
                }
                else
                {
                    // fallback: split on two+ spaces
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

                if (!left.isEmpty() && !right.isEmpty())
                {
                    caseSensitiveMap.put(left, right);
                    String keyNorm = normalizeKey(left);
                    if (!keyNorm.isEmpty())
                    {
                        normalizedMap.put(keyNorm, right);
                    }
                    added++;
                }
            }

            log.info("LocalGlossary: loaded {} entries ({} lines read) into {}", added, lineNo, type);
        }
    }

    /**
     * Lookup translation for a phrase within the given glossary type.
     * This implements the previous (exact + fuzzy single-token) behavior.
     */
    public String lookup(String src, GlossaryType type)
    {
        if (src == null || src.trim().isEmpty())
        {
            return null;
        }

        Map<String, String> caseSensitiveMap = caseSensitiveMaps.get(type);
        Map<String, String> normalizedMap = normalizedMaps.get(type);

        String srcTrim = src.trim();

        // 1) Case-sensitive exact
        String v = caseSensitiveMap.get(srcTrim);
        if (v != null)
        {
            return matchCase(srcTrim, v);
        }

        // 1b) Exact match with stripped tags
        String stripped = stripTags(srcTrim);
        if (!stripped.equals(srcTrim))
        {
            v = caseSensitiveMap.get(stripped);
            if (v != null) return matchCase(srcTrim, v);
        }

        // 2) Case-insensitive exact
        String normSrc = normalizeKey(srcTrim);
        v = normalizedMap.get(normSrc);
        if (v != null) return matchCase(srcTrim, v);

        // Fuzzy/word-boundary replacement only allowed on single-token
        if (!isSingleToken(srcTrim))
        {
            return null;
        }

        // 3) Case-sensitive whole-word match
        String bestCaseKey = null;
        int bestCaseLen = -1;
        for (String key : caseSensitiveMap.keySet())
        {
            if (wordBoundaryMatch(stripTags(srcTrim), key))
            {
                int len = key.length();
                if (len > bestCaseLen)
                {
                    bestCaseLen = len;
                    bestCaseKey = key;
                }
            }
        }
        if (bestCaseKey != null)
        {
            String repl = caseSensitiveMap.get(bestCaseKey);
            String replaced = replaceFirstWholeWord(srcTrim, bestCaseKey, matchCase(bestCaseKey, repl), false);
            if (replaced != null) return replaced;
        }

        // 4) Case-insensitive whole-word match
        String bestNormKey = null;
        int bestNormLen = -1;
        for (String key : normalizedMap.keySet())
        {
            if (wordBoundaryMatch(normSrc, key))
            {
                int len = key.length();
                if (len > bestNormLen)
                {
                    bestNormLen = len;
                    bestNormKey = key;
                }
            }
        }
        if (bestNormKey != null)
        {
            String repl = normalizedMap.get(bestNormKey);
            String replaced = replaceFirstWholeWord(srcTrim, bestNormKey, matchCase(bestNormKey, repl), true);
            if (replaced != null) return replaced;
        }

        return null;
    }

    /**
     * Exact-only lookup (no fuzzy/substring). Returns exact mapping or null.
     * Useful for TranslationManager flows that want to prefer exact matches only.
     */
    public String lookupExact(String src, GlossaryType type)
    {
        if (src == null || src.trim().isEmpty()) return null;
        Map<String, String> caseSensitiveMap = caseSensitiveMaps.get(type);
        Map<String, String> normalizedMap = normalizedMaps.get(type);

        String srcTrim = src.trim();

        // 1) Case-sensitive exact
        String v = caseSensitiveMap.get(srcTrim);
        if (v != null) return matchCase(srcTrim, v);

        // 1b) Stripped tags exact
        String stripped = stripTags(srcTrim);
        if (!stripped.equals(srcTrim))
        {
            v = caseSensitiveMap.get(stripped);
            if (v != null) return matchCase(srcTrim, v);
        }

        // 2) Case-insensitive normalized exact
        String norm = normalizeKey(srcTrim);
        v = normalizedMap.get(norm);
        if (v != null) return matchCase(srcTrim, v);

        return null;
    }

    /**
     * Number of entries for a specific glossary type.
     */
    public int size(GlossaryType type)
    {
        Map<String, String> m = normalizedMaps.get(type);
        return m == null ? 0 : m.size();
    }

    /**
     * Total number of entries across all types.
     */
    public int sizeAll()
    {
        int sum = 0;
        for (GlossaryType t : GlossaryType.values())
        {
            Map<String, String> m = normalizedMaps.get(t);
            if (m != null) sum += m.size();
        }
        return sum;
    }

    // ================= helpers =================

    private static String stripTags(String s)
    {
        if (s == null) return "";
        return TAGS.matcher(s).replaceAll("");
    }

    public static String normalizeKey(String key)
    {
        if (key == null) return "";
        String t = key;

        t = t.replaceAll("<[^>]+>", "");
        t = t.replace('\u00A0', ' ');
        t = t.replaceAll("\\s+", " ");
        t = t.trim().toLowerCase(Locale.ROOT);

        return t;
    }

    private static boolean isSingleToken(String s)
    {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++)
        {
            if (Character.isWhitespace(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean wordBoundaryMatch(String text, String key)
    {
        if (text == null || key == null) return false;
        if (text.equals(key)) return true;

        try
        {
            Pattern p = Pattern.compile("\\b" + Pattern.quote(key) + "\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            Matcher m = p.matcher(text);
            return m.find();
        }
        catch (Exception e)
        {
            String s = " " + text + " ";
            String k = " " + key + " ";
            return s.contains(k);
        }
    }

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

    private static String replaceFirstWholeWord(String src, String key, String replacement, boolean caseInsensitive)
    {
        if (src == null || key == null || key.isEmpty() || replacement == null) return null;

        String patternText = "\\b" + Pattern.quote(key) + "\\b";
        int flags = Pattern.UNICODE_CASE;
        if (caseInsensitive) flags |= Pattern.CASE_INSENSITIVE;

        Pattern p = Pattern.compile(patternText, flags);
        Matcher m = p.matcher(src);
        if (!m.find()) return null;

        StringBuffer sb = new StringBuffer();
        m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        m.appendTail(sb);
        return sb.toString();
    }
}
