package org.openstatic.log;

import java.util.ArrayList;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Collectors;

import org.json.JSONObject;
import org.openstatic.LogSpoutMain;

public class LogConnectionContainer implements LogConnection, LogConnectionListener
{
    private ArrayList<LogConnectionListener> listeners;
    private ArrayList<LogConnection> connections;
    private JSONObject config;
    private long createdAt;
    private boolean connected;
    private boolean started; // This flag means its ok to start and stop downstream connections based on listeners

    public LogConnectionContainer(JSONObject config)
    {
        this.config = config;
        this.listeners = new ArrayList<LogConnectionListener>();
        this.connections = new ArrayList<LogConnection>();
        this.connected = false;
        this.started = false;
        this.createdAt = System.currentTimeMillis();
    }

    @Override
    public void addLogConnectionListener(LogConnectionListener listener) 
    {
        if (!this.listeners.contains(listener))
            this.listeners.add(listener);
        if (this.listeners.size() > 0 && !connected && started)
        {
            if (LogSpoutMain.verbose)
            {
                System.err.println ("Connecting " + this.getName() + " (We have listeners!)");
            }
            this.connect();
        }
    }

    @Override
    public void removeLogConnectionListener(LogConnectionListener listener) 
    {
        if (this.listeners.contains(listener))
            this.listeners.remove(listener);
        if (LogConnectionContainer.this.listeners.size() == 0 && LogConnectionContainer.this.connected && LogConnectionContainer.this.started)
        {
            Thread d = new Thread(() -> {
                try
                {
                    Thread.sleep(30000);
                    if (LogConnectionContainer.this.listeners.size() == 0 && LogConnectionContainer.this.connected && LogConnectionContainer.this.started)
                    {
                        if (LogSpoutMain.verbose)
                        {
                            System.err.println ("Disconnecting " + this.getName() + " (no listeners!)");
                        }
                        LogConnectionContainer.this.disconnect();
                    }
                } catch (Exception e) {
                    if (LogSpoutMain.verbose)
                        e.printStackTrace(System.err);
                }
            });
            d.start();
        }
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

    public void clearAllLogConnections() 
    {
        ((ArrayList<LogConnection>) this.connections.clone()).forEach((c) -> {
            try
            {
                c.removeLogConnectionListener(LogConnectionContainer.this);
                if (c.isConnected())
                    c.disconnect();
            } catch (Exception e) {}
        });
        this.connections.clear();
    }

    @Override
    public void start()
    {
        if (LogSpoutMain.verbose)
        {
            System.err.println("Starting: " + this.getName());
        }
        this.started = true;
        ((ArrayList<LogConnection>) this.connections.clone()).forEach((c) -> c.start());
    }

    @Override
    public void connect() 
    {
        this.connected = true;
        ((ArrayList<LogConnection>) this.connections.clone()).forEach((c) -> c.connect());
    }

    @Override
    public void disconnect() 
    {
        this.connected = false;
        ((ArrayList<LogConnection>) this.connections.clone()).forEach((c) -> c.disconnect());
    }

    public Collection<LogConnection> getLogConnections()
    {
        return this.connections;
    }

    @Override
    public void onLine(String line, ArrayList<String> logPath, LogConnection connection)
    {
        boolean flag = false;
        String linePrefix = "";
        String lineSuffix = "";
        if (this.config.has("_filter"))
        {
            String filter = this.config.optString("_filter");
            flag = LogSpoutMain.isMatch(line, filter);
        } else {
            flag = true;
        }
        if (this.config.has("_highlight"))
        {
            JSONObject rules = this.config.getJSONObject("_highlight");
            Set<String> keySet = rules.keySet();
            Iterator<String> keyIterator = keySet.iterator();
            while(keyIterator.hasNext())
            {
                String key = keyIterator.next();
                if (line.contains(key))
                {
                    linePrefix = rules.getString(key);
                    lineSuffix = "\u001b[0m";
                }
            }
        }
        if (flag)
        {
            final String finalLine = (linePrefix + line + lineSuffix);
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

    @Override
    public Collection<String> getContainedNames()
    {
        return this.connections.stream().map((con) -> { 
            return con.getName();
        }).collect(Collectors.toList());
    }

    @Override
    public String getType() 
    {
        return this.config.optString("_type", "container");

    }

    @Override
    public void onLogDisconnectError(LogConnection connection, String err, Exception exception) 
    {
        ((ArrayList<LogConnectionListener>) this.listeners.clone()).forEach((l) -> {
            l.onLogDisconnectError(connection, err, exception);
        });
    }

    @Override
    public boolean isConnected() {
        return this.connected;
    }

    @Override
    public long getAgeMillis() {
        return System.currentTimeMillis() - this.createdAt;
    }
    
}
