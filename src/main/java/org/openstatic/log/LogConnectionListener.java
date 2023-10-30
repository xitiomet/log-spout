package org.openstatic.log;

import org.json.JSONObject;

public interface LogConnectionListener
{
    public void onLine(String line, JSONObject config);
}
