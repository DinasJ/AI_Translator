package com.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Loads ru_index_merged.json (or .json.gz) into an in-memory search index.
 *
 * The JSON produced earlier uses numeric top-level keys and contains a field "game_id".
 * Example entry:
 *  "8041": { "id": 8041, "ename": "The eight clans", "rname": "Восемь кланов", "game_id": 24057 }
 *
 * This loader prefers "game_id" as the real client item id; if absent it falls back to "id"
 * or the numeric top-level key. It is also tolerant when prefix/trigram arrays reference
 * either the top-level key or the "game_id".
 */
public class RuIndexLoader
{
    public static class Item
    {
        public final int gameId;
        public final String ename;
        public final String rname;

        public Item(int gameId, String ename, String rname)
        {
            this.gameId = gameId;
            this.ename = ename;
            this.rname = rname;
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    public static class RuIndex
    {
        public final List<Item> itemsByIndex;          // ordinal -> Item
        public final Map<Integer, Integer> idToIndex;  // actual gameId -> ordinal
        public final Map<String, BitSet> prefixIndex;  // prefix -> bitset
        public final Map<String, BitSet> trigramIndex; // trigram -> bitset

        public RuIndex(List<Item> itemsByIndex,
                       Map<Integer,Integer> idToIndex,
                       Map<String,BitSet> prefixIndex,
                       Map<String,BitSet> trigramIndex)
        {
            this.itemsByIndex = itemsByIndex;
            this.idToIndex = idToIndex;
            this.prefixIndex = prefixIndex;
            this.trigramIndex = trigramIndex;
        }

        /** Get Russian name by gameId (or empty string if not found). */
        public String getRuName(int gameId)
        {
            Integer ord = idToIndex.get(gameId);
            if (ord == null || ord < 0 || ord >= itemsByIndex.size())
            {
                return "";
            }
            return itemsByIndex.get(ord).rname;
        }

        /** Normalize Russian strings for consistent searching. */
        private static String normalize(String s)
        {
            if (s == null) return "";
            String lower = s.toLowerCase(Locale.forLanguageTag("ru"));
            return Normalizer.normalize(lower, Normalizer.Form.NFKC);
        }

        /** Simple search by substring over rname (ignoring trigram optimization for now). */
        public List<Item> searchByRussianName(String query)
        {
            String norm = normalize(query);
            if (norm.isEmpty()) return Collections.emptyList();

            List<Item> results = new ArrayList<>();
            for (Item item : itemsByIndex)
            {
                if (normalize(item.rname).contains(norm))
                {
                    results.add(item);
                }
            }
            return results;
        }
    }

    /**
     * Load the JSON or gzipped JSON file (path may point to .json or .json.gz).
     * The JSON should have a top-level "items" object and optional "prefix_index" and "trigram_index".
     */
    public RuIndex loadIndex(Path filePath) throws IOException
    {
        // Use try-with-resources so streams are always closed
        try (InputStream fis = java.nio.file.Files.newInputStream(filePath);
             InputStream is = filePath.toString().endsWith(".gz") ? new GZIPInputStream(fis) : fis)
        {
            JsonNode root = mapper.readTree(is);

            // 1) read items
            JsonNode itemsNode = root.get("items");
            if (itemsNode == null || !itemsNode.isObject())
            {
                throw new IOException("Invalid index: missing 'items' node");
            }

            // Collect numeric top-level keys (original keys)
            List<Integer> originalKeys = new ArrayList<>();
            Iterator<String> itFields = itemsNode.fieldNames();
            while (itFields.hasNext())
            {
                String key = itFields.next();
                try { originalKeys.add(Integer.parseInt(key)); }
                catch (NumberFormatException ex) { /* skip non-numeric keys */ }
            }
            Collections.sort(originalKeys);

            // We'll build two maps:
            //  - idToIndex: actual gameId -> ordinal (preferred for lookups)
            //  - originalKeyToIndex: numeric top-level key -> ordinal (in case prefix arrays refer to top-level keys)
            Map<Integer, Integer> idToIndex = new HashMap<>(originalKeys.size());
            Map<Integer, Integer> originalKeyToIndex = new HashMap<>(originalKeys.size());
            List<Item> itemsByIndex = new ArrayList<>(originalKeys.size());

            for (int ord = 0; ord < originalKeys.size(); ord++)
            {
                int origKey = originalKeys.get(ord);
                JsonNode node = itemsNode.get(String.valueOf(origKey));
                if (node == null || !node.isObject())
                {
                    // shouldn't happen, but skip defensively
                    continue;
                }

                // Preferred: use "game_id" if present (real client item id).
                // Fallback: use "id" field if present, otherwise use the top-level key.
                int actualGameId = origKey;
                if (node.has("game_id")) actualGameId = node.get("game_id").asInt(origKey);
                else if (node.has("id")) actualGameId = node.get("id").asInt(origKey);

                String ename = node.has("ename") ? node.get("ename").asText("") : "";
                String rname = node.has("rname") ? node.get("rname").asText("") : "";

                // add item using actual gameId
                itemsByIndex.add(new Item(actualGameId, ename, rname));

                // register mappings
                idToIndex.put(actualGameId, ord);
                originalKeyToIndex.put(origKey, ord);
            }

            // 2) prefix/trigram -> BitSet (tolerant: accept either actual game_id values or original keys)
            Map<String, BitSet> prefixIndex = new HashMap<>();
            Map<String, BitSet> trigramIndex = new HashMap<>();

            JsonNode prefixNode = root.get("prefix_index");
            if (prefixNode != null && prefixNode.isObject())
            {
                Iterator<String> pfxIt = prefixNode.fieldNames();
                while (pfxIt.hasNext())
                {
                    String key = pfxIt.next();
                    JsonNode arr = prefixNode.get(key);
                    if (arr == null || !arr.isArray()) continue;
                    BitSet bs = new BitSet(itemsByIndex.size());
                    for (JsonNode idNode : arr)
                    {
                        if (!idNode.isIntegralNumber()) continue;
                        int v = idNode.asInt(-1);
                        Integer ord = idToIndex.get(v);
                        if (ord == null) ord = originalKeyToIndex.get(v);
                        if (ord != null) bs.set(ord);
                    }
                    if (!bs.isEmpty()) prefixIndex.put(key, bs);
                }
            }

            JsonNode trigramNode = root.get("trigram_index");
            if (trigramNode != null && trigramNode.isObject())
            {
                Iterator<String> triIt = trigramNode.fieldNames();
                while (triIt.hasNext())
                {
                    String key = triIt.next();
                    JsonNode arr = trigramNode.get(key);
                    if (arr == null || !arr.isArray()) continue;
                    BitSet bs = new BitSet(itemsByIndex.size());
                    for (JsonNode idNode : arr)
                    {
                        if (!idNode.isIntegralNumber()) continue;
                        int v = idNode.asInt(-1);
                        Integer ord = idToIndex.get(v);
                        if (ord == null) ord = originalKeyToIndex.get(v);
                        if (ord != null) bs.set(ord);
                    }
                    if (!bs.isEmpty()) trigramIndex.put(key, bs);
                }
            }

            return new RuIndex(itemsByIndex, idToIndex, prefixIndex, trigramIndex);
        }
    }
}
