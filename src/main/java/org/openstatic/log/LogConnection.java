package org.openstatic.log;

import java.util.Collection;

public interface LogConnection 
{
    public void addLogConnectionListener(LogConnectionListener listener);
    public void removeLogConnectionListener(LogConnectionListener listener);
    public void connect();
    public void disconnect();
    public void start();
    public String getName();
    public Collection<String> getContainedNames();
    public String getType();
    public boolean isConnected();
    public long getAgeMillis();
}
