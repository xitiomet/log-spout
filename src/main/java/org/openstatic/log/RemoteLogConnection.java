package org.openstatic.log;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openstatic.log.APIWebServer.EventsWebSocket;

public class RemoteLogConnection implements LogConnection, Runnable
{
    private ArrayList<LogConnectionListener> listeners;
    private JSONObject config;
    private WebSocketClient webSocketClient;
    private WebSocketSession session;
    private EventsWebSocket socket;
    private Thread keepAliveThread;
    private String wsUrl;
    private boolean connected;
    private JSONArray logs;

    public RemoteLogConnection(JSONObject config)
    {
        this.config = config;
        this.listeners = new ArrayList<LogConnectionListener>();
        this.wsUrl = config.optString("_remote", "ws://127.0.0.1:8662") + "/logspout/";
        this.connected = false;
        //System.err.println("Remote Connection: " + this.wsUrl);
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

    @Override
    public void connect() 
    {
        this.connected = true;
        if (this.wsUrl != null && this.webSocketClient != null)
        {
            try
            {
                URI echoUri = new URI(this.wsUrl);
                ClientUpgradeRequest request = new ClientUpgradeRequest();
                this.socket = new EventsWebSocket();
                this.launchKeepAliveThread();
                this.webSocketClient.connect(socket, echoUri, request);
                //System.out.printf("Connecting to : %s%n", echoUri);
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }
    }

    public void transmit(JSONObject data)
    {
        if (this.session != null)
        {
            try
            {
                String textData = data.toString();
                //System.err.println("Transmit: " + textData);
                this.session.getRemote().sendStringByFuture(textData);
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }
    }

    @Override
    public void disconnect() 
    {
        this.connected = false;
    }

    @Override
    public void start() 
    {
        RemoteLogConnection.this.webSocketClient = new WebSocketClient();
        try
        {
            RemoteLogConnection.this.webSocketClient.start();
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    @Override
    public String getName()
    {
        return LogConnectionParser.replaceVariables(this.config.optString("_name", ""), config);
    }
    
    @Override
    public String getType()
    {
        return "remote";
    }

    @Override
    public boolean isConnected() 
    {
        return this.connected;
    }

    public void launchKeepAliveThread()
    {
        if (this.keepAliveThread == null) 
        {
            //System.err.println("RemoteLogConnection - Keep alive thread is null, launching now");
            this.keepAliveThread = new Thread(RemoteLogConnection.this);
            this.keepAliveThread.start();
        } else {
            if (!this.keepAliveThread.isAlive())
            {
                //System.err.println("RemoteLogConnection - Keep alive thread is dead, but exists, launching new one");
                this.keepAliveThread = new Thread(RemoteLogConnection.this);
                this.keepAliveThread.start();
            } else {
                //System.err.println("RemoteLogConnection - Keep alive thread already active, no need to launch another");
            }
        }
    }

    
    @WebSocket
    public class EventsWebSocket {

        @OnWebSocketMessage
        public void onText(Session session, String message) throws IOException {
            try {
                //System.err.println("Rx: " + message);
                final JSONObject jo = new JSONObject(message);
                if (jo.has("action"))
                {
                    String action = jo.optString("action");
                    if (action.equals("line"))
                    {

                        final String fLine =  jo.optString("line");
                        ((ArrayList<LogConnectionListener>) RemoteLogConnection.this.listeners.clone()).forEach((listener) -> {
                            ArrayList<String> logPath = new ArrayList<String>();
                            logPath.add(RemoteLogConnection.this.getName());
                            logPath.add(jo.optString("connection"));
                            listener.onLine(fLine, logPath, RemoteLogConnection.this);
                        });
                    } else if (action.equals("authOk")) {
                        RemoteLogConnection.this.config.put("_termAuth", jo.optString("termAuth"));
                        RemoteLogConnection.this.logs = jo.getJSONArray("logs");
                        JSONObject logSelection = new JSONObject();
                        logSelection.put("log", RemoteLogConnection.this.logs.getString(0));
                        transmit(logSelection);
                        JSONObject filterSelection = new JSONObject();
                        filterSelection.put("filter", RemoteLogConnection.this.config.optString("_filter", ""));
                        transmit(filterSelection);
                    }
                } else if (jo.has("name") && jo.has("hostname")) {
                        RemoteLogConnection.this.config.put("_name", jo.optString("name"));
                        if (RemoteLogConnection.this.config.has("_remote_password"))
                        {
                            JSONObject authJsonObject = new JSONObject();
                            authJsonObject.put("apiPassword", RemoteLogConnection.this.config.optString("_remote_password"));
                            authJsonObject.put("termId", System.currentTimeMillis());
                            transmit(authJsonObject);
                        }
                }
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }

        @OnWebSocketConnect
        public void onConnect(Session session) throws IOException 
        {
            // System.err.println("Connected websocket");
            if (session instanceof WebSocketSession) {
                RemoteLogConnection.this.session = (WebSocketSession) session;
            } else {
                // System.err.println("Not an instance of WebSocketSession");
            }
        }

        @OnWebSocketClose
        public void onClose(Session session, int status, String reason) {
            // System.err.println("Close websocket");
            RemoteLogConnection.this.close();
            RemoteLogConnection.this.session = null;
        }

        @OnWebSocketError
        public void onError(Throwable e) 
        {
            e.printStackTrace(System.err);
            RemoteLogConnection.this.close();
            RemoteLogConnection.this.session = null;
        }
    }

    @Override
    public void run()
    {
        while (this.connected) 
        {
            try
            {
                Thread.sleep(10000);
                if (!this.isReallyConnected())
                {
                    //System.err.println("RemoteLogConnection - No connection detected by keep alive reconnecting...");
                    this.close();
                    this.session = null;
                    this.connect();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        try
        {
            //System.err.print("RemoteLogConnection - Stopping websocketClient....");
            this.webSocketClient.stop();
            //System.err.println("DONE");
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        //System.err.println("RemoteLogConnection - Exiting Keep Alive Thread!");        
    }

    private boolean isReallyConnected()
    {
        if (this.session != null)
        {
            return this.session.isOpen();
        } else {
            return false;
        }
    }

    private void close()
    {
        if (this.session != null)
        {
            try
            {
                this.session.close();
            } catch (Exception e) {
                // e.printStackTrace();
            }
        }
    }
}
