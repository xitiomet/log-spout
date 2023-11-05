package org.openstatic.log;

public interface LogConnection 
{
    public void addLogConnectionListener(LogConnectionListener listener);
    public void removeLogConnectionListener(LogConnectionListener listener);
    public void connect();
    public void disconnect();
    public String getName();
    public String getType();
}
