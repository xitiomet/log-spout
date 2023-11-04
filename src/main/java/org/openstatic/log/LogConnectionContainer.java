package org.openstatic.log;

import java.util.ArrayList;

import java.util.Collection;

import org.apache.commons.logging.LogSource;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openstatic.LogSpoutMain;

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

    public Collection<LogConnection> getLogConnections()
    {
        return this.connections;
    }

    @Override
    public void onLine(String line, ArrayList<String> logPath, LogConnection connection)
    {
        boolean flag = false;
        if (this.config.has("_filter"))
        {
            String filter = this.config.optString("_filter");
            flag = LogSpoutMain.isMatch(line, filter);
        } else {
            flag = true;
        }
        if (flag)
        {
            final String finalLine = LogConnectionParser.replaceVariables(this.config.optString("_prefix",""), this.config) + line;
            ArrayList<String> newLogPath = new ArrayList<String>();
            newLogPath.add(this.getName());
            newLogPath.addAll(logPath);
            ((ArrayList<LogConnectionListener>) this.listeners.clone()).forEach((l) -> {
                l.onLine(finalLine, newLogPath, connection);
            });
        }
    }

    @Override
    public String getName() 
    {
        return this.config.optString("_name", "Untitled Container");

    }
    
}
