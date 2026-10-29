package org.openstatic.log;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.apache.commons.text.StringEscapeUtils;
import org.json.JSONObject;

final class LogLineFormatter
{
    private LogLineFormatter()
    {
    }

    static String format(String line, JSONObject config)
    {
        try
        {
            if (config.optBoolean("_unescape", true))
                line = StringEscapeUtils.unescapeJava(line.replaceAll(Pattern.quote("\\x"), "\\\\u00"));
            if (config.optBoolean("_fixescape", true))
                line = StringEscapeUtils.unescapeJava(line.replaceAll(Pattern.quote("#033["), "\\\\u001b["));
        } catch (IllegalArgumentException e) {
            System.err.println("Unable to unescape log line: " + e.getMessage());
        }
        try
        {
            if (config.optBoolean("_urldecode", false))
                line = URLDecoder.decode(line, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            System.err.println("Unable to URL-decode log line: " + e.getMessage());
        }
        String prefix = "";
        String suffix = "";
        if (config.has("_highlight"))
        {
            JSONObject rules = config.getJSONObject("_highlight");
            for (String key : rules.keySet())
            {
                if (line.contains(key))
                {
                    prefix = rules.getString(key);
                    suffix = "\u001b[0m";
                }
            }
        }
        return prefix + line + suffix;
    }
}
