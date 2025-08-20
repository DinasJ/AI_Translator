package com.example;

import net.runelite.api.widgets.Widget;

public final class Utils
{
    private Utils() {}

    /** Remove RuneLite/RS color tags etc. and normalize NBSP to space. */
    public static String stripTags(String s)
    {
        return s == null ? "" : s.replaceAll("<[^>]*>", "").replace('\u00A0', ' ');
    }

    public static String normalizeName(String s)
    {
        if (s == null) return "";
        String t = s.replace('\u00A0', ' ').trim();
        return t.toLowerCase();
    }

    public static String truncate(String s)
    {
        if (s == null) return "null";
        s = s.replace("\n", "\\n");
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    public static String safeLang(String lang)
    {
        if (lang == null || lang.trim().isEmpty()) return "RU";
        return lang.trim().toUpperCase();
    }

    /** Is this the left/right chat name widget? */
    public static boolean isNameWidget(Widget w)
    {
        int id = w.getId();
        int group = (id >>> 16);
        int child = (id & 0xFFFF);
        return (group == 231 || group == 217) && child == 4;
    }
}