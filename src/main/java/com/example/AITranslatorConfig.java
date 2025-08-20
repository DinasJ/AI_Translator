package com.example;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("AITranslator")
public interface AITranslatorConfig extends Config
{
    @ConfigItem(
            keyName = "greeting",
            name = "Welcome Greeting",
            description = "The message to show to the user when they login"
    )
    default String greeting()
    {
        return "Hello";
    }

    @ConfigItem(
            keyName = "deeplApiKey",
            name = "DeepL API Key",
            description = "DeepL auth key (never hardcode in source).",
            secret = true
    )
    default String deeplApiKey()
    {
        return "";
    }

    @ConfigItem(
            keyName = "targetLang",
            name = "Target Language",
            description = "Target language code for translation (e.g., RU, JA, DE)."
    )
    default String targetLang()
    {
        return "RU";
    }

    @ConfigItem(
            keyName = "translateChat",
            name = "Translate Chat",
            description = "Translate public chat box text."
    )
    default boolean translateChat()
    {
        return true;
    }
    @ConfigItem(
            keyName = "translateDialogue",
            name = "Translate Dialogue",
            description = "Translate NPC/Player dialogues."
    )
    default boolean translateDialogue()
    {
        return true;
    }
}
