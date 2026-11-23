package org.openstatic.log;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openstatic.LogSpoutMain;

public class APIWebServerTest
{
    private JSONObject previousSettings;
    private JexlEngine previousJexl;
    private APIWebServer previousInstance;
    private APIWebServer server;

    @Before
    public void startServer()
    {
        this.previousSettings = LogSpoutMain.settings;
        this.previousJexl = LogSpoutMain.jexl;
        this.previousInstance = APIWebServer.instance;
        LogSpoutMain.settings = new JSONObject().put("apiPort", 0);
        LogSpoutMain.jexl = new JexlBuilder().create();
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
        LogSpoutMain.jexl = this.previousJexl;
    }

    @Test
    public void retainsConfiguredNumberOfRecentPackets() throws Exception
    {
        for (int index = 0; index < 205; index++)
            this.server.addHistory(packet("root", "leaf", "line " + index));

        List<JSONObject> history = this.server.getHistory("leaf", "", 300);
        assertEquals(200, history.size());
        assertEquals("line 5", history.get(0).getString("line"));
        assertEquals("line 204", history.get(199).getString("line"));

        LogSpoutMain.settings.put("historyRetention", 2);
        this.server.addHistory(packet("root", "leaf", "line 205"));
        history = this.server.getHistory("leaf", "", 300);
        assertEquals(2, history.size());
        assertEquals("line 204", history.get(0).getString("line"));
        assertEquals("line 205", history.get(1).getString("line"));

        LogSpoutMain.settings.put("historyRetention", 0);
        this.server.addHistory(packet("root", "leaf", "line 206"));
        assertTrue(this.server.getHistory("leaf", "", 300).isEmpty());
    }

    private JSONObject packet(String parent, String leaf, String line)
    {
        return new JSONObject().put("action", "line").put("line", line)
            .put("path", new JSONArray().put(parent).put(leaf));
    }

    @Test
    public void retainsSeparateHistoryForLeavesAndContainers()
    {
        LogSpoutMain.settings.put("historyRetention", 2);
        this.server.addHistory(packet("root", "quiet", "quiet line"));
        this.server.addHistory(packet("root", "busy", "busy 1"));
        this.server.addHistory(packet("root", "busy", "busy 2"));
        this.server.addHistory(packet("root", "busy", "busy 3"));
        assertEquals("quiet line", this.server.getHistory("quiet", "", 2).get(0).getString("line"));
        assertEquals("busy 2", this.server.getHistory("busy", "", 2).get(0).getString("line"));
        assertEquals("busy 2", this.server.getHistory("root", "", 2).get(0).getString("line"));
        assertTrue(this.server.getHistory("unknown", "", 2).isEmpty());
        assertTrue(this.server.getHistory("root", "", -1).isEmpty());
    }

    @Test
    public void historyAppliesFilterBeforeLimitingResults()
    {
        this.server.addHistory(packet("root", "leaf", "ERROR first"));
        this.server.addHistory(packet("root", "leaf", "INFO middle"));
        this.server.addHistory(packet("root", "leaf", "ERROR last"));
        List<JSONObject> history = this.server.getHistory("leaf", "ERROR", 2);
        assertEquals(2, history.size());
        assertEquals("ERROR first", history.get(0).getString("line"));
        assertEquals("ERROR last", history.get(1).getString("line"));
    }

    @WebSocket
    public static class TestSocket
    {
        private final BlockingQueue<JSONObject> messages = new LinkedBlockingQueue<JSONObject>();

        @OnWebSocketMessage
        public void onMessage(String message)
        {
            this.messages.add(new JSONObject(message));
        }
    }

    @Test
    public void websocketHistoryUsesSelectedLogAndFilter() throws Exception
    {
        this.server.addHistory(packet("root", "quiet", "ERROR quiet"));
        this.server.addHistory(packet("root", "quiet", "INFO quiet"));
        this.server.addHistory(packet("root", "quiet", "ERROR quiet again"));
        this.server.addHistory(packet("root", "busy", "ERROR busy"));
        WebSocketClient client = new WebSocketClient();
        client.start();
        try
        {
            TestSocket socket = new TestSocket();
            Session session = client.connect(socket, new URI("ws://127.0.0.1:" + this.server.getLocalPort() + "/logspout/"))
                .get(5, TimeUnit.SECONDS);
            JSONObject auth = socket.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(auth);
            assertEquals("authOk", auth.getString("action"));
            session.getRemote().sendString("{\"log\":\"quiet\"}");
            session.getRemote().sendString("{\"filter\":\"ERROR\"}");
            session.getRemote().sendString("{\"history\":200}");
            JSONObject replay = socket.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(replay);
            assertEquals("ERROR quiet", replay.getString("line"));
            replay = socket.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(replay);
            assertEquals("ERROR quiet again", replay.getString("line"));
            assertNull(socket.messages.poll(200, TimeUnit.MILLISECONDS));
            for (int index = 0; index < 200; index++)
                this.server.addHistory(packet("root", "busy", "ERROR " + index));
            session.getRemote().sendString("{\"log\":\"busy\"}");
            session.getRemote().sendString("{\"history\":200}");
            for (int index = 0; index < 200; index++)
            {
                replay = socket.messages.poll(5, TimeUnit.SECONDS);
                assertNotNull("Missing history line " + index, replay);
                assertEquals("ERROR " + index, replay.getString("line"));
            }
            session.close();
        } finally {
            client.stop();
        }
    }
}