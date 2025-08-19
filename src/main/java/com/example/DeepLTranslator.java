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
import java.util.function.Consumer;

/**
 * A service for asynchronous translation using the DeepL API.
 * Handles local glossary lookups, HTTP requests, and JSON parsing.
 */
@Singleton
public class DeepLTranslator
{
    private static final Logger log = LoggerFactory.getLogger(DeepLTranslator.class);
    private static final String API_URL = "https://api-free.deepl.com/v2/translate";

    private final OkHttpClient client = new OkHttpClient();
    private final AITranslatorConfig config;
    private final LocalGlossary localGlossary;

    @Inject
    public DeepLTranslator(AITranslatorConfig config, LocalGlossary localGlossary)
    {
        this.config = config;
        this.localGlossary = localGlossary;
    }

    /**
     * Non-blocking translation call with a callback.
     */
    public void translateAsync(String text, String targetLang, Consumer<String> callback)
    {
        if (text == null || text.isEmpty())
        {
            callback.accept(text);
            return;
        }

        // ✅ Check local glossary first
        String manual = localGlossary.lookup(text);
        if (manual != null)
        {
            log.debug("Local glossary hit for '{}': '{}'", text, manual);
            callback.accept(manual);
            return;
        }

        String apiKey = config.deeplApiKey();
        if (apiKey == null || apiKey.isEmpty())
        {
            log.error("DeepL API key is not configured.");
            callback.accept(text);
            return;
        }

        FormBody body = new FormBody.Builder()
                .add("auth_key", apiKey)
                .add("text", text)
                .add("target_lang", targetLang)
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
                log.error("DeepL translation request failed: {}", e.getMessage());
                try { callback.accept(text); } catch (Exception ignored) {}
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try (Response res = response)
                {
                    if (!res.isSuccessful())
                    {
                        log.error("DeepL API returned code {} with message {}", res.code(), res.message());
                        callback.accept(text);
                        return;
                    }

                    ResponseBody rb = res.body();
                    if (rb == null)
                    {
                        log.error("DeepL API response body was null");
                        callback.accept(text);
                        return;
                    }

                    String json = rb.string();
                    // ✅ Old-style JsonParser call
                    JsonElement element = new JsonParser().parse(json);

                    if (element == null || !element.isJsonObject())
                    {
                        log.error("Invalid JSON from DeepL: {}", json);
                        callback.accept(text);
                        return;
                    }

                    JsonObject obj = element.getAsJsonObject();
                    String translatedText = obj.getAsJsonArray("translations")
                            .get(0).getAsJsonObject()
                            .get("text").getAsString();

                    callback.accept(translatedText);
                }
                catch (Exception e)
                {
                    log.error("Error parsing DeepL response: {}", e.getMessage());
                    callback.accept(text);
                }
            }
        });
    }
}
