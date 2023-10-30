package org.openstatic.log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.json.JSONArray;
import org.json.JSONObject;

public class LogConnectionParser
{
    public static LogConnection parse(JSONObject json)
    {
        if (json.has("_type"))
        {
            String type = json.getString("_type");
            if (type.equals("forEachLine"))
            {
                return forEachLine(json);
            } else if (type.equals("process")) {
                return new ProcessLogConnection(json);
            }
        } else if (json.has("_source")) {
            JSONObject source = json.getJSONObject("_source");
            return parse(mergeCleanVariables(source, json));
        }
        return null;
    }

    public static LogConnectionContainer forEachLine(JSONObject config)
    {
        System.err.println("ForEachLine: " + config.toString());
        LogConnectionContainer lcc = new LogConnectionContainer();
        ArrayList<String> forEachValues = null;
        if (config.has("_execute"))
        {
            forEachValues = executeForEach(config.getJSONArray("_execute"), cleanVariables(config));
        }
        if (forEachValues != null && config.has("_source"))
        {
            forEachValues.forEach((val) -> {
                JSONObject source = mergeCleanVariables(config.getJSONObject("_source"), config);
                source.put("line", val);
                lcc.addLogConnection(parse(source));
            });
        }
        return lcc;
    }


    private static JSONObject mergeCleanVariables(JSONObject config, JSONObject variables)
    {
        JSONObject j = new JSONObject(config.toString());
        Set<String> keySet = variables.keySet();
        for (Iterator<String> si = keySet.iterator(); si.hasNext(); )
        {
            String key = si.next();
            if (!key.startsWith("_") && !j.has(key))
            {
                j.put(key, variables.opt(key));
            }
        }
        return j;
    }

    private static JSONObject cleanVariables(JSONObject variables)
    {
        JSONObject j = new JSONObject();
        Set<String> keySet = variables.keySet();
        for (Iterator<String> si = keySet.iterator(); si.hasNext(); )
        {
            String key = si.next();
            if (!key.startsWith("_"))
            {
                j.put(key, variables.opt(key));
            }
        }
        return j;
    }

    public static ArrayList<String> executeForEach(JSONArray command, JSONObject varContext)
    {
        final ArrayList<String> commandArray = new ArrayList<String>();
        for(int i = 0; i < command.length(); i++)
        {
            String cs = command.getString(i);
            Set<String> keySet = varContext.keySet();
            for(Iterator<String> keyIterator = keySet.iterator(); keyIterator.hasNext();)
            {
                String key = keyIterator.next();
                cs = cs.replaceAll(Pattern.quote("$(" + key + ")"), varContext.get(key).toString());
            }
            commandArray.add(cs);
        }
        System.err.println("CommandArray: " + commandArray.stream().collect(Collectors.joining(" ")));
        ProcessBuilder processBuilder = new ProcessBuilder(commandArray);
        ArrayList<String> results = new ArrayList<String>();
        try
        {
            Process p = processBuilder.redirectErrorStream(true).start();
            InputStream is = p.getInputStream();
            InputStreamReader isr = new InputStreamReader(is);
            BufferedReader br = new BufferedReader(isr);
            String line;
            while((line = br.readLine()) != null)
            {
                try
                {
                    results.add(line);
                    System.err.println("line: " + line);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return results;
    }
}
