// Java
package com.example;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * A service for asynchronous translation using the DeepL API.
 * This class handles HTTP requests and JSON parsing.
 */
@Singleton
public class DeepLTranslator
{
    private static final Logger log = LoggerFactory.getLogger(DeepLTranslator.class);
    private static final String API_URL = "https://api-free.deepl.com/v2/translate";

    // OkHttpClient is a dependency that can be reused for all requests.
    private final OkHttpClient client = new OkHttpClient();

    // Inject the config to get the API key, rather than hardcoding it.
    private final AITranslatorConfig config;

    @Inject
    public DeepLTranslator(AITranslatorConfig config)
    {
        this.config = config;
    }

    /**
     * Non-blocking translation call with a callback.
     * This is the preferred method as it does not freeze the game client.
     *
     * @param text The text to translate.
     * @param targetLang The language code for the translation target (e.g., "RU").
     * @param callback The function to call with the translated string.
     */
    public void translateAsync(String text, String targetLang, Consumer<String> callback)
    {
        // Guard against empty or null input
        if (text == null || text.isEmpty())
        {
            callback.accept(text);
            return;
        }

        // Retrieve the API key from the plugin's configuration
        String apiKey = config.deeplApiKey();
        if (apiKey == null || apiKey.isEmpty())
        {
            log.error("DeepL API key is not configured.");
            callback.accept(text);
            return;
        }

        // Build the request body with the necessary form data
        RequestBody body = new FormBody.Builder()
                .add("auth_key", apiKey)
                .add("text", text)
                .add("target_lang", targetLang)
                .build();

        // Build the HTTP request
        Request request = new Request.Builder()
                .url(API_URL)
                .post(body)
                .build();

        // Use OkHttp's enqueue method for a truly asynchronous call on a background thread.
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
                try (Response res = response) // ensure body is closed
                {
                    if (!res.isSuccessful())
                    {
                        log.error("DeepL API returned an unexpected response code: {}", res.code());
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

                    // Prefer modern parseString; falls back to object check
                    JsonElement element = new JsonParser().parse(json);
                    if (element == null || !element.isJsonObject())
                    {
                        log.error("DeepL API returned invalid JSON: {}", json);
                        callback.accept(text);
                        return;
                    }

                    JsonObject obj = element.getAsJsonObject();
                    String translatedText = obj.getAsJsonArray("translations")
                            .get(0).getAsJsonObject()
                            .get("text").getAsString();

                    callback.accept(translatedText);
                }
                catch (JsonSyntaxException e)
                {
                    log.error("Failed to parse DeepL response (JsonSyntaxException): {}", e.getMessage());
                    callback.accept(text);
                }
                catch (IllegalStateException e)
                {
                    log.error("Failed to parse DeepL response (IllegalStateException): {}", e.getMessage());
                    callback.accept(text);
                }
                catch (Exception e)
                {
                    log.error("An unexpected error occurred while parsing DeepL response: {}", e.getMessage());
                    callback.accept(text); // Return original text on failure
                }
            }
        });
    }
}