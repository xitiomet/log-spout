package org.openstatic.log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONObject;
import org.openstatic.LogSpoutMain;

public class ForEachLineContainer extends LogConnectionContainer
{
    private JSONObject config;
    private boolean connected;
    private boolean inRebuild;

    public ForEachLineContainer(JSONObject config)
    {
        super(config);
        this.config = config;  
        this.connected = false;
        this.inRebuild = false;
    }

    @Override
    public void connect()
    {
        this.connected = true;
        this.clearAllLogConnections();
        ArrayList<String> forEachValues = null;
        if (config.has("_execute"))
        {
            forEachValues = executeForEach(config.getJSONArray("_execute"), LogConnectionParser.cleanVariables(config));
        }
        if (config.has("_ignore"))
        {
            forEachValues.removeAll(config.optJSONArray("_ignore").toList());
        }
        if (forEachValues != null && config.has("_source"))
        {
            forEachValues.forEach((val) -> {
                JSONObject source = LogConnectionParser.mergeCleanVariables(config.getJSONObject("_source"), config);
                source.put("line", val);
                source.put("_name",this.getName() + " (" + val + ")");
                LogConnection con = LogConnectionParser.parse(source);
                this.addLogConnection(con);
                con.connect();
            });
        }
        this.inRebuild = false;
    }

    @Override
    public void disconnect()
    {
        this.connected = false;
        super.disconnect();
    }

    @Override
    public String getType()
    {
        return "forEachLine";
    }
    
    public static ArrayList<String> executeForEach(JSONArray command, JSONObject varContext)
    {
        final ArrayList<String> commandArray = new ArrayList<String>();
        for(int i = 0; i < command.length(); i++)
        {
            String cs = command.getString(i);
            commandArray.add(LogConnectionParser.replaceVariables(cs, varContext));
        }
        //System.err.println("CommandArray: " + commandArray.stream().collect(Collectors.joining(" ")));
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
                    //System.err.println("line: " + line);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return results;
    }

    @Override
    public void onLogDisconnectError(LogConnection connection, String err)
    {
        if (!this.inRebuild)
        {
            (new Thread(() -> {
                try
                {
                    Thread.sleep(10000);
                    if (ForEachLineContainer.this.connected)
                    {
                        if (LogSpoutMain.verbose)
                            System.err.println("Issue inside forEachLineContainer " + this.getName() + ", rebuilding! " + err);
                        ForEachLineContainer.this.connect();
                    }
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                }
            })).start();
        }
    }

    @Override
    public boolean isConnected()
    {
        return this.connected;
    }
}
