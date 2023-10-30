package org.openstatic.log;

import java.util.ArrayList;

public class LogConnectionContainer implements LogConnection
{
    private ArrayList<LogConnectionListener> listeners;
    private ArrayList<LogConnection> connections;

    public LogConnectionContainer()
    {
        this.listeners = new ArrayList<LogConnectionListener>();
        this.connections = new ArrayList<LogConnection>();
    }

    @Override
    public void addLogConnectionListener(LogConnectionListener listener) {
        if (!this.listeners.contains(listener))
            this.listeners.add(listener);
    }

    @Override
    public void removeLogConnectionListener(LogConnectionListener listener) {
        if (this.listeners.contains(listener))
            this.listeners.remove(listener);
    }

    public void addLogConnection(LogConnection connection) {
        if (!this.connections.contains(connection))
            this.connections.add(connection);
    }

    public void removeLogConnection(LogConnection connection) {
        if (this.connections.contains(connection))
            this.connections.remove(connection);
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
    
}
