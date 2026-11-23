package org.openstatic.log;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;

import org.eclipse.jetty.server.Server;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openstatic.LogSpoutMain;

public class APIWebServerTest
{
    private JSONObject previousSettings;
    private APIWebServer previousInstance;
    private APIWebServer server;

    @Before
    public void startServer()
    {
        this.previousSettings = LogSpoutMain.settings;
        this.previousInstance = APIWebServer.instance;
        LogSpoutMain.settings = new JSONObject().put("apiPort", 0);
        this.server = new APIWebServer(new LogConnectionContainer(LogSpoutMain.settings));
    }

    @After
    public void stopServer() throws Exception
    {
        Field httpServer = APIWebServer.class.getDeclaredField("httpServer");
        httpServer.setAccessible(true);
        ((Server) httpServer.get(this.server)).stop();
        Field pingPongThread = APIWebServer.class.getDeclaredField("pingPongThread");
        pingPongThread.setAccessible(true);
        Thread thread = (Thread) pingPongThread.get(this.server);
        thread.interrupt();
        thread.join();
        APIWebServer.instance = this.previousInstance;
        LogSpoutMain.settings = this.previousSettings;
    }

    @Test
    public void retainsConfiguredNumberOfRecentPackets() throws Exception
    {
        for (int index = 0; index < 205; index++)
            this.server.addHistory(new JSONObject().put("index", index));

        Field packetHistory = APIWebServer.class.getDeclaredField("packetHistory");
        packetHistory.setAccessible(true);
        @SuppressWarnings("unchecked")
        ArrayList<JSONObject> history = (ArrayList<JSONObject>) packetHistory.get(this.server);
        assertEquals(200, history.size());
        assertEquals(5, history.get(0).getInt("index"));
        assertEquals(204, history.get(199).getInt("index"));

        LogSpoutMain.settings.put("historyRetention", 2);
        this.server.addHistory(new JSONObject().put("index", 205));
        assertEquals(2, history.size());
        assertEquals(204, history.get(0).getInt("index"));
        assertEquals(205, history.get(1).getInt("index"));

        LogSpoutMain.settings.put("historyRetention", 0);
        this.server.addHistory(new JSONObject().put("index", 206));
        assertTrue(history.isEmpty());
    }
}