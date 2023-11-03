package org.openstatic.log;

import java.util.ArrayList;

public interface LogConnectionListener
{
    public void onLine(String line, ArrayList<String> logPath, LogConnection connection);
}
