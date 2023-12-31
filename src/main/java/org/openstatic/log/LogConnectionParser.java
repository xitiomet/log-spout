package org.openstatic.log;

import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;
import org.openstatic.LogSpoutMain;

public class LogConnectionParser
{

    public static LogConnectionContainer parseInitial(JSONObject json)
    {
        if (LogSpoutMain.verbose)
            System.err.println("Parsing: \n" + json.toString(2));
        if (json.has("_remote"))
        {
            LogConnectionContainer lcc = new LogConnectionContainer(json);
            lcc.addLogConnection(new RemoteLogConnection(json));
            lcc.start();
            return lcc;
        } else if (json.has("_sources")) {
            JSONArray sources = json.getJSONArray("_sources");
            LogConnectionContainer lcc = new LogConnectionContainer(json);
            for(int i = 0; i < sources.length(); i++)
            {
                JSONObject source = sources.getJSONObject(i);
                if (json.has("_filter"))
                {
                    source.put("_filter", json.get("_filter"));
                }
                if (json.has("_select"))
                {
                    source.put("_select", json.get("_select"));
                }
                lcc.addLogConnection(parse(mergeCleanVariables(source, json)));
            }
            lcc.start();
            return lcc;
        }
        return null;
    }

    public static LogConnection parse(JSONObject json)
    {
        if (json.has("_type"))
        {
            String type = json.getString("_type");
            if (type.equals("forEachLine"))
            {
                return new ForEachLineContainer(json);
            } else if (type.equals("process")) {
                return new ProcessLogConnection(json);
            } else if (type.equals("remote")) {
                return new RemoteLogConnection(json);
            }
        } else if (json.has("_sources")) {
            JSONArray sources = json.getJSONArray("_sources");
            LogConnectionContainer lcc = new LogConnectionContainer(json);
            for(int i = 0; i < sources.length(); i++)
            {
                JSONObject source = sources.getJSONObject(i);
                if (json.has("_filter"))
                {
                    source.put("_filter", json.get("_filter"));
                }
                if (json.has("_select"))
                {
                    source.put("_select", json.get("_select"));
                }
                lcc.addLogConnection(parse(mergeCleanVariables(source, json)));
            }
            return lcc;
        }
        return null;
    }

    public static JSONObject mergeCleanVariables(JSONObject config, JSONObject variables)
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

    public static JSONObject cleanVariables(JSONObject variables)
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

    public static String replaceVariables(String line, JSONObject variables)
    {
        String cs = line;
        Set<String> keySet = variables.keySet();
        for(Iterator<String> keyIterator = keySet.iterator(); keyIterator.hasNext();)
        {
            String key = keyIterator.next();
            cs = cs.replaceAll(Pattern.quote("$(" + key + ")"), variables.get(key).toString());
        }
        return cs;
    }

    
}
