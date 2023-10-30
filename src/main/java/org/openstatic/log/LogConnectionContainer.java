package org.openstatic.log;

import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONObject;

public class LogConnectionContainer implements LogConnection, LogConnectionListener
{
    private ArrayList<LogConnectionListener> listeners;
    private ArrayList<LogConnection> connections;
    private JSONObject config;

    public LogConnectionContainer(JSONObject config)
    {
        this.config = config;
        this.listeners = new ArrayList<LogConnectionListener>();
        this.connections = new ArrayList<LogConnection>();
    }

    @Override
    public void addLogConnectionListener(LogConnectionListener listener) 
    {
        if (!this.listeners.contains(listener))
            this.listeners.add(listener);
    }

    @Override
    public void removeLogConnectionListener(LogConnectionListener listener) 
    {
        if (this.listeners.contains(listener))
            this.listeners.remove(listener);
    }

    public void addLogConnection(LogConnection connection) 
    {
        if (!this.connections.contains(connection))
        {
            this.connections.add(connection);
            connection.addLogConnectionListener(this);
        }
    }

    public void removeLogConnection(LogConnection connection) 
    {
        if (this.connections.contains(connection))
        {
            this.connections.remove(connection);
            connection.removeLogConnectionListener(this);
        }
    }

    @Override
    public void connect() 
    {
        this.connections.forEach((c) -> c.connect());
    }

    @Override
    public void disconnect() 
    {
        this.connections.forEach((c) -> c.disconnect());
    }

    @Override
    public void onLine(String line, JSONObject sourceConfig)
    {
        boolean flag = false;
        if (this.config.has("_contains"))
        {
            JSONArray contains = this.config.optJSONArray("_contains");
            for(int i = 0; i < contains.length(); i++)
            {
                flag = line.contains(contains.getString(i)) || flag;
            }
            
        } else {
            flag = true;
        }
        if (flag)
        {
            final String finalLine = LogConnectionParser.replaceVariables(this.config.optString("_prefix",""), this.config) + line;
            this.listeners.forEach((l) -> {
                l.onLine(finalLine, LogConnectionContainer.this.config);
            });
        }
    }
    
}
