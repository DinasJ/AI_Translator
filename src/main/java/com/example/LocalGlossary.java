package com.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local glossary with both case-sensitive and case-insensitive lookups.
 *
 * Behavior:
 *  - Loads TSV-like files (tab-separated or separated by 2+ spaces).
 *  - Keeps both case-sensitive entries (as provided) and a normalized lower-cased map.
 *  - Lookup attempts exact case-sensitive, exact normalized, then longest whole-word match
 *    (case-sensitive first, then case-insensitive).
 */
@Singleton
public class LocalGlossary
{
    private static final Logger log = LoggerFactory.getLogger(LocalGlossary.class);

    // Case-sensitive map uses the source key exactly as found in the TSV (preserves case)
    private final Map<String, String> caseSensitiveMap = new ConcurrentHashMap<>();

    // Normalized (lowercased and cleaned) map for case-insensitive matching
    private final Map<String, String> normalizedMap = new ConcurrentHashMap<>();

    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final char BOM = '\uFEFF';

    /**
     * Load glossary entries from an InputStream containing TSV.
     * Replaces existing content.
     *
     * @param in InputStream (UTF-8)
     * @throws IOException on read errors
     */
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

                // Remove BOM on first line if present
                if (lineNo == 1 && line.charAt(0) == BOM)
                {
                    line = line.substring(1);
                }

                // Trim ends but preserve internal spacing for splitting
                String raw = line.trim();
                if (raw.isEmpty()) continue;

                // Skip comments
                if (raw.startsWith("#") || raw.startsWith("//")) continue;

                String left = null, right = null;

                // Try splitting by tab first
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

                // Store both case-sensitive original and normalized key
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

            log.info("LocalGlossary: loaded {} entries ({} lines read)", added, lineNo);
        }
    }

    /**
     * Lookup translation for a phrase.
     * Returns null if not found.
     *
     * Lookup priority:
     * 1) Case-sensitive exact (also tries stripped tags variant)
     * 2) Normalized exact (case-insensitive)
     * 3) Case-sensitive whole-word longest match
     * 4) Case-insensitive whole-word longest match
     */
    public String lookup(String src)
    {
        if (src == null || src.trim().isEmpty())
        {
            return null;
        }

        String srcTrim = src.trim();

        // 1) Case-sensitive exact match (try as-is)
        String v = caseSensitiveMap.get(srcTrim);
        if (v != null)
        {
            String adjusted = matchCase(srcTrim, v);
            if (log.isDebugEnabled()) log.debug("LocalGlossary case-sensitive exact hit: '{}' -> '{}'", srcTrim, adjusted);
            return adjusted;
        }

        // Also try stripped tags exact case-sensitive
        String stripped = stripTags(srcTrim);
        if (!stripped.equals(srcTrim))
        {
            v = caseSensitiveMap.get(stripped);
            if (v != null)
            {
                String adjusted = matchCase(srcTrim, v);
                if (log.isDebugEnabled()) log.debug("LocalGlossary case-sensitive exact hit after strip: '{}' -> '{}'", srcTrim, adjusted);
                return adjusted;
            }
        }

        // Prepare normalized source
        String normSrc = normalizeKey(srcTrim);

        // 2) Normalized (case-insensitive) exact match
        v = normalizedMap.get(normSrc);
        if (v != null)
        {
            String adjusted = matchCase(srcTrim, v);
            if (log.isDebugEnabled()) log.debug("LocalGlossary case-insensitive exact hit: '{}' (norm='{}') -> '{}'", srcTrim, normSrc, adjusted);
            return adjusted;
        }

        // Do NOT run fuzzy on multi-word inputs
        if (!isSingleToken(srcTrim))
        {
            if (log.isDebugEnabled()) log.debug("LocalGlossary: skipping fuzzy for multi-word input '{}'", srcTrim);
            return null;
        }

        // Numeric-prefix handler (single-token only)
        String dyn = lookupNumberSuffix(srcTrim);
        if (dyn != null)
        {
            return dyn;
        }

        // 3) Case-sensitive whole-word longest match
        String bestCaseKey = null;
        int bestCaseLen = -1;
        for (String key : caseSensitiveMap.keySet())
        {
            if (key == null || key.isEmpty()) continue;
            String srcNoTags = stripTags(srcTrim);
            if (wordBoundaryMatch(srcNoTags, key))
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
            if (replaced != null)
            {
                if (log.isDebugEnabled()) log.debug("LocalGlossary in-place replace (case-sensitive): '{}' with key '{}' -> '{}'", srcTrim, bestCaseKey, replaced);
                return replaced;
            }
        }

        // 4) Case-insensitive whole-word longest match
        String bestNormKey = null;
        int bestNormLen = -1;
        for (String key : normalizedMap.keySet())
        {
            if (key == null || key.isEmpty()) continue;
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
            if (replaced != null)
            {
                if (log.isDebugEnabled()) log.debug("LocalGlossary in-place replace (case-insensitive): '{}' with key '{}' -> '{}'", srcTrim, bestNormKey, replaced);
                return replaced;
            }
        }

        log.debug("LocalGlossary miss: '{}' (norm='{}')", srcTrim, normSrc);
        return null;
    }

    /**
     * Strict exact-only lookup:
     *  - case-sensitive exact
     *  - case-sensitive exact on stripped-tags
     *  - normalized (case-insensitive) exact
     * No fuzzy/substring/whole-word matching. Safe for long chat lines.
     */
    public String lookupExact(String src)
    {
        if (src == null) return null;
        String srcTrim = src.trim();
        if (srcTrim.isEmpty()) return null;

        // 1) Case-sensitive exact
        String v = caseSensitiveMap.get(srcTrim);
        if (v != null) return matchCase(srcTrim, v);

        // 1b) Case-sensitive exact on stripped-tags
        String stripped = stripTags(srcTrim);
        if (!stripped.equals(srcTrim))
        {
            v = caseSensitiveMap.get(stripped);
            if (v != null) return matchCase(srcTrim, v);
        }

        // 2) Normalized (case-insensitive) exact
        String norm = normalizeKey(srcTrim);
        v = normalizedMap.get(norm);
        if (v != null) return matchCase(srcTrim, v);

        return null;
    }

    /**
     * Conservative per-token translation:
     *  - replaces only standalone tokens found in the glossary,
     *  - optionally fuses a translated base with an adjacent quantifier (digits|X|All|All-but-1) into "base-quant",
     *  - preserves all surrounding punctuation and spacing,
     *  - returns null if no changes were made (so callers can fall back to other logic).
     */
    public String lookupTokens(String src)
    {
        if (src == null) return null;
        String original = src;
        String s = src;
        if (s.isEmpty()) return null;

        // Work on a tag-free view, but keep the original string for spacing/punct preservation.
        String noTags = stripTags(s);

        // If the WHOLE phrase matches (exact or normalized), prefer that over token-wise
        String whole = lookupExactOrNormalized(noTags);
        if (whole != null && !whole.isEmpty())
        {
            return matchCase(s, whole);
        }

        // Token pattern: words with internal hyphens and/or digits (e.g., Talk-to, Withdraw-10),
        // numbers, and special quantifiers ("X", "All", "All-but-1")
        final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile(
                "(?i)All-but-1|All|X|[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)*|\\d+"
        );

        java.util.regex.Matcher m = TOKEN.matcher(noTags);
        StringBuilder out = new StringBuilder(noTags.length() + 16);
        int last = 0;
        boolean changed = false;

        // Buffer matches to allow lookahead (for base + quantifier fusion)
        java.util.List<java.util.regex.MatchResult> matches = new java.util.ArrayList<>();
        while (m.find()) matches.add(m.toMatchResult());

        for (int i = 0; i < matches.size(); i++)
        {
            java.util.regex.MatchResult cur = matches.get(i);
            int start = cur.start();
            int end   = cur.end();

            // copy gap
            if (start > last) out.append(noTags, last, start);

            String tok = noTags.substring(start, end); // original-case token

            // Try to translate current token exactly or normalized
            String tr = lookupExactOrNormalized(tok);

            // Check for quantifier fusion if current token is a base verb-like term and the next is a quantifier
            if (tr != null && i + 1 < matches.size())
            {
                java.util.regex.MatchResult nxt = matches.get(i + 1);
                String nextTok = noTags.substring(nxt.start(), nxt.end());
                if (isQuantifierToken(nextTok))
                {
                    // Fuse "translated-base" + "-" + original quantifier
                    String fused = tr + "-" + nextTok;
                    out.append(matchCase(tok + "-" + nextTok, fused));
                    changed = true;
                    last = nxt.end();
                    i++; // consumed next token
                    continue;
                }
            }

            if (tr != null)
            {
                out.append(matchCase(tok, tr));
                changed = true;
            }
            else
            {
                out.append(tok);
            }
            last = end;
        }

        // tail
        if (last < noTags.length()) out.append(noTags, last, noTags.length());

        if (!changed) return null;
        return out.toString();
    }

    // Quantifier tokens commonly used across game UIs; extend if needed
    private static boolean isQuantifierToken(String t)
    {
        if (t == null || t.isEmpty()) return false;
        String q = t.trim();
        if (q.matches("\\d+")) return true; // numbers
        String low = q.toLowerCase(Locale.ROOT);
        return low.equals("x") || low.equals("all") || low.equals("all-but-1");
    }

    public int size()
    {
        // size of normalized map is representative (case-sensitive map might be superset)
        return normalizedMap.size();
    }

    public void clear()
    {
        caseSensitiveMap.clear();
        normalizedMap.clear();
    }

    // ----------------- helpers -----------------

    private static String stripTags(String s)
    {
        if (s == null) return "";
        return TAGS.matcher(s).replaceAll("");
    }

    /**
     * Normalize key for case-insensitive matching:
     *  - strips tags
     *  - converts NBSP to space
     *  - keeps '-' intact (punctuation matters, e.g., "Talk-to", "Withdraw-10")
     *  - collapses multiple whitespace
     *  - trims and lowercases
     */
    public static String normalizeKey(String key)
    {
        if (key == null) return "";
        String t = key;

        // remove tags
        t = t.replaceAll("<[^>]+>", "");

        // normalize whitespace, keep hyphens significant
        t = t.replace('\u00A0', ' ');
        // DO NOT replace '-' with space; punctuation matters for actions like "Talk-to" or "Withdraw-10"

        // collapse multiple spaces
        t = t.replaceAll("\\s+", " ");

        t = t.trim().toLowerCase(Locale.ROOT);
        return t;
    }

    // Only allow fuzzy logic for single-token inputs (no whitespace).
    private static boolean isSingleToken(String s)
    {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++)
        {
            if (Character.isWhitespace(s.charAt(i)))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Word-boundary matcher that works on normalized text or raw text.
     * Uses Pattern.quote to avoid regex injection and falls back to a simple contain-with-spaces check.
     */
    private static boolean wordBoundaryMatch(String text, String key)
    {
        if (text == null || key == null) return false;
        // Quick containment check (fast path)
        if (text.equals(key)) return true;

        // Build a pattern like \bkey\b (the key may contain spaces; Pattern.quote handles that)
        try
        {
            Pattern p = Pattern.compile("\\b" + Pattern.quote(key) + "\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            Matcher m = p.matcher(text);
            return m.find();
        }
        catch (Exception e)
        {
            // fallback: very conservative check - require spaces around key
            String s = " " + text + " ";
            String k = " " + key + " ";
            return s.contains(k);
        }
    }

    /**
     * Adjust capitalization of 'target' to match source pattern 'src' in common ways:
     * - if src is ALL_UPPER -> target ALL_UPPER
     * - if src is Capitalized -> target Capitalized
     * - otherwise return target as-is
     */
    private static String matchCase(String src, String target)
    {
        if (src == null || src.isEmpty() || target == null || target.isEmpty()) return target;

        // All uppercase
        if (src.equals(src.toUpperCase(Locale.ROOT)))
        {
            return target.toUpperCase(Locale.ROOT);
        }

        // Capitalized: first letter upper, rest lower
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

        // default: return as-is
        return target;
    }

    /**
     * Try to translate strings starting with a base + separator + digits, preserving tail text.
     * E.g., "Withdraw-10 Training sword" -> "Снять-10 Training sword"
     * Coerces the separator to '-' (hyphen) if it's a space or a Unicode dash, to keep OSRS-style formatting.
     */
    private String lookupNumberSuffix(String srcTrim)
    {
        // base: words with optional internal hyphens/spaces; sep: hyphen/en dash/em dash/space; num: digits
        final java.util.regex.Pattern P = java.util.regex.Pattern.compile("^([A-Za-z][A-Za-z\\- ]*?)([-\\u2013\\u2014 ]?)(\\d+)(\\b.*)?$");
        java.util.regex.Matcher m = P.matcher(srcTrim);
        if (!m.matches())
        {
            return null;
        }

        String base = m.group(1).trim();
        String sep  = m.group(2) == null ? "" : m.group(2);
        String num  = m.group(3);
        String tail = m.group(4) == null ? "" : m.group(4);

        if (base.isEmpty())
        {
            return null;
        }

        String baseTranslated = lookupExactOrNormalized(base);
        if (baseTranslated == null)
        {
            return null;
        }

        // Force OSRS-style separator to '-' if it was space or a Unicode dash
        if (!sep.isEmpty())
        {
            if (sep.equals(" ") || sep.equals("\u2013") || sep.equals("\u2014"))
            {
                sep = "-";
            }
            // If it's already "-", keep it as is.
        }
        else
        {
            // If no separator captured but it's a typical pattern like "Withdraw 5" that failed to capture,
            // we still prefer a hyphen.
            sep = "-";
        }

        String rebuilt = baseTranslated + sep + num + tail;
        return matchCase(srcTrim, rebuilt);
    }

    /**
     * Helper that tries exact (with and without tags) and normalized exact lookups,
     * returning the mapped value or null.
     */
    private String lookupExactOrNormalized(String src)
    {
        if (src == null || src.isEmpty()) return null;

        String v = caseSensitiveMap.get(src);
        if (v != null) return v;

        String stripped = stripTags(src);
        if (!stripped.equals(src))
        {
            v = caseSensitiveMap.get(stripped);
            if (v != null) return v;
        }

        String norm = normalizeKey(src);
        return normalizedMap.get(norm);
    }

    /**
     * Replace the first whole-word occurrence of 'key' inside 'src' with 'replacement'.
     * If caseInsensitive is true, uses case-insensitive word-boundary matching.
     * Returns the new string or null if no match found.
     */
    private static String replaceFirstWholeWord(String src, String key, String replacement, boolean caseInsensitive)
    {
        if (src == null || key == null || key.isEmpty() || replacement == null) return null;

        String patternText = "\\b" + Pattern.quote(key) + "\\b";
        int flags = Pattern.UNICODE_CASE;
        if (caseInsensitive)
        {
            flags |= Pattern.CASE_INSENSITIVE;
        }
        Pattern p = Pattern.compile(patternText, flags);
        Matcher m = p.matcher(src);
        if (!m.find())
        {
            return null;
        }
        StringBuffer sb = new StringBuffer();
        m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        m.appendTail(sb);
        return sb.toString();
    }
}
