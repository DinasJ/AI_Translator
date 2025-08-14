package com.example;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AITranslatorPluginTest
{
	public static void main(String[] args) throws Exception
	{
        ExternalPluginManager.loadBuiltin(AITranslatorPlugin.class);
		RuneLite.main(args);
	}
}