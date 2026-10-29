package org.openstatic.log;

import java.util.Collection;

public interface LogConnection 
{
    public void addLogConnectionListener(LogConnectionListener listener);
    public void removeLogConnectionListener(LogConnectionListener listener);

    // fired when the log actually starts being followed
    public void connect();

    // fired when the log is disconnected, either intentionally or due to an error
    public void disconnect();

    // fired once when the log connection is first started
    public void start();

    // what the log connection is called, this appears in the dropdown
    public String getName();

    // the names of the individual logs contained within this connection, if applicable
    public Collection<String> getContainedNames();

    // the type of the log connection, e.g., "tail","process","remote"
    public String getType();

    // whether the log connection is currently connected
    public boolean isConnected();

    
    public long getAgeMillis();
}
