package com.example;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A service for asynchronous translation using the DeepL API.
 * Always checks glossary first, then falls back to DeepL if available.
 */
@Singleton
public class DeepLTranslator
{
    private static final Logger log = LoggerFactory.getLogger(DeepLTranslator.class);
    private static final String API_URL = "https://api-free.deepl.com/v2/translate";

    private final OkHttpClient client = new OkHttpClient();
    private final AITranslatorConfig config;
    private final GlossaryService glossaryService;
    private final CacheManager cacheManager;  // <-- add this

    @Inject
    public DeepLTranslator(AITranslatorConfig config, GlossaryService glossaryService, CacheManager cacheManager) {
        this.config = config;
        this.glossaryService = glossaryService;
        this.cacheManager = cacheManager;
    }

    /**
     * Non-blocking translation call with a callback.
     *
     * @param text         The text to translate.
     * @param targetLang   The target language (e.g., "RU").
     * @param glossaryType The glossary type to check first.
     * @param callback     Callback that receives the translated text.
     */
    public void translateAsync(String text, String targetLang, GlossaryService.Type glossaryType, Consumer<String> callback)
    {
        if (text == null || text.isEmpty())
        {
            callback.accept(text);
            return;
        }

        // --- 1. Cache lookup first (instant, snappy) ---
        String cached = cacheManager.get(text);
        if (cached != null && !cached.isEmpty())
        {
            log.debug("[DeepL] Cache hit ({}): '{}' -> '{}'", glossaryType, text, cached);
            callback.accept(matchCase(text, cached)); // immediately return
            return;
        }

        // --- 2. Glossary lookup ---
        String manual = glossaryService.tryTranslate(glossaryType, text);
        if (manual != null && !manual.isEmpty())
        {
            log.debug("[DeepL] Local glossary hit ({}): '{}' -> '{}'", glossaryType, text, manual);
            cacheManager.put(text, manual); // seed into cache for future instant hits
            callback.accept(matchCase(text, manual)); // immediately return
            return;
        }

        // --- 3. DeepL fallback (async) ---
        String apiKey = config.deeplApiKey();
        if (apiKey == null || apiKey.isEmpty())
        {
            log.warn("[DeepL] API key missing, returning original text: '{}'", text);
            callback.accept(text);
            return;
        }

        RequestBody body = new FormBody.Builder()
                .add("auth_key", apiKey)
                .add("text", text)
                .add("target_lang", safeLang(targetLang))
                .add("source_lang", "EN")
                .build();

        Request request = new Request.Builder()
                .url(API_URL)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.error("[DeepL] Request failed: {}", e.getMessage());
                safeAccept(callback, text);
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (Response res = response)
                {
                    if (!res.isSuccessful())
                    {
                        log.error("[DeepL] API returned {} {}", res.code(), res.message());
                        safeAccept(callback, text);
                        return;
                    }

                    ResponseBody rb = res.body();
                    if (rb == null)
                    {
                        log.error("[DeepL] Response body null");
                        safeAccept(callback, text);
                        return;
                    }

                    String json = rb.string();
                    JsonElement element = JsonParser.parseString(json);
                    if (!element.isJsonObject())
                    {
                        log.error("[DeepL] Invalid JSON: {}", json);
                        safeAccept(callback, text);
                        return;
                    }

                    JsonObject obj = element.getAsJsonObject();
                    String translated = obj.getAsJsonArray("translations")
                            .get(0).getAsJsonObject()
                            .get("text").getAsString();

                    log.debug("[DeepL] '{}' -> '{}'", text, translated);

                    // seed cache for instant future hits
                    cacheManager.put(text, translated);

                    safeAccept(callback, matchCase(text, translated));
                }
                catch (Exception e)
                {
                    log.error("[DeepL] Error parsing response: {}", e.getMessage());
                    safeAccept(callback, text);
                }
            }
        });
    }

    private static void safeAccept(Consumer<String> cb, String value)
    {
        try { cb.accept(value); } catch (Exception ignored) {}
    }

    private static String safeLang(String s)
    {
        return s == null ? "EN" : s.trim().toUpperCase(Locale.ROOT);
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
            return target.substring(0, 1).toUpperCase(Locale.ROOT) +
                    (target.length() > 1 ? target.substring(1).toLowerCase(Locale.ROOT) : "");
        }

        return target;
    }
}
