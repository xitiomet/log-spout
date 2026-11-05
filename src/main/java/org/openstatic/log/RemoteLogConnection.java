package org.openstatic.log;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
import org.openstatic.LogSpoutMain;

public class RemoteLogConnection implements LogConnection, Runnable
{
    private ArrayList<LogConnectionListener> listeners;
    private JSONObject config;
    private WebSocketClient webSocketClient;
    private WebSocketSession session;
    private volatile EventsWebSocket socket;
    private Thread keepAliveThread;
    private static final long CONNECT_TIMEOUT_SECONDS = 10;
    private static final Pattern URL_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");
    private final String remoteAddress;
    private final List<String> candidateUrls;
    private volatile String resolvedUrl;
    private boolean connected;
    private List<String> logs;
    private long createdAt;
    private final boolean localNameSpecified;

    public RemoteLogConnection(JSONObject config)
    {
        this.createdAt = System.currentTimeMillis();
        this.config = config;
        this.localNameSpecified = config.has("_name");
        this.listeners = new ArrayList<LogConnectionListener>();
        this.remoteAddress = config.optString("_remote", "ws://127.0.0.1:8662");
        this.candidateUrls = candidateUrls(this.remoteAddress);
        this.connected = false;
        this.logs = new ArrayList<String>();
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
        if (this.webSocketClient != null)
            this.launchKeepAliveThread();
    }

    /**
     * Builds the ordered list of WebSocket URLs to try for a remote address.
     * Addresses without a scheme try wss:// then ws://; each is tried as given
     * and then with the /logspout/ endpoint appended.
     */
    public static List<String> candidateUrls(String remote)
    {
        String address = remote == null ? "" : remote.trim();
        List<String> urls = new ArrayList<String>();
        if (URL_SCHEME.matcher(address).matches())
        {
            addCandidates(urls, address);
        } else {
            addCandidates(urls, "wss://" + address);
            addCandidates(urls, "ws://" + address);
        }
        return urls;
    }

    private static void addCandidates(List<String> urls, String url)
    {
        int authorityStart = url.indexOf("://") + 3;
        int pathStart = url.indexOf('/', authorityStart);
        String base = pathStart < 0 ? url : url.substring(0, pathStart);
        String path = pathStart < 0 ? "/" : url.substring(pathStart);
        urls.add(base + path);
        if (!path.matches(".*/logspout/?"))
            urls.add(base + (path.endsWith("/") ? path : path + "/") + "logspout/");
    }

    private void openSession()
    {
        List<String> urls = this.resolvedUrl != null ? List.of(this.resolvedUrl) : this.candidateUrls;
        for (String url : urls)
        {
            if (!this.connected)
                return;
            EventsWebSocket attempt = new EventsWebSocket();
            this.socket = attempt;
            Future<Session> future = null;
            try
            {
                future = this.webSocketClient.connect(attempt, new URI(url), new ClientUpgradeRequest());
                future.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (this.resolvedUrl == null)
                {
                    if (url.regionMatches(true, 0, "ws://", 0, 5))
                        System.err.println("\u001b[1;31m[INSECURE CONNECTION]\u001b[0m " + url);
                    else if (LogSpoutMain.verbose)
                        System.err.println("Connected to remote: " + url);
                }
                this.resolvedUrl = url;
                return;
            } catch (Exception e) {
                if (future != null)
                    future.cancel(true);
                if (LogSpoutMain.verbose)
                    System.err.println("Remote connection failed: " + url + " (" + e + ")");
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
                this.session.getRemote().sendStringByFuture(textData);
            } catch (Exception e) {
                //e.printStackTrace(System.err);
            }
        }
    }

    @Override
    public void disconnect() 
    {
        this.connected = false;
        this.close();
    }

    @Override
    public void start() 
    {
        RemoteLogConnection.this.webSocketClient = new WebSocketClient();
        try
        {
            RemoteLogConnection.this.webSocketClient.start();
        } catch (Exception e) {
            //e.printStackTrace(System.err);
        }
    }

    @Override
    public String getName()
    {
        return LogConnectionParser.replaceVariables(this.config.optString("_name", this.remoteAddress), config);
    }

    @Override
    public Collection<String> getContainedNames() 
    {
        return this.logs.stream().filter((entry) -> { 
            String select = RemoteLogConnection.this.config.optString("_select", null);
            if (select == null)
            {
                return true;
            } else if (select.equals(entry)) {
                return true;
            }
            return false;
        }).collect(Collectors.toList());
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
                final JSONObject jo = new JSONObject(message);
                if (jo.has("action"))
                {
                    String action = jo.optString("action");
                    JSONArray path = jo.optJSONArray("path");
                    if (action.equals("line"))
                    {

                        final String fLine =  jo.optString("line");
                        final ArrayList<String> logPath = new ArrayList<String>();
                        logPath.add(RemoteLogConnection.this.getName());
                        for(int i = 0; i < path.length(); i++)
                            logPath.add(path.getString(i));
                        ((ArrayList<LogConnectionListener>) RemoteLogConnection.this.listeners.clone()).forEach((listener) -> {
                            listener.onLine(fLine, logPath, RemoteLogConnection.this);
                        });
                    } else if (action.equals("authOk")) {
                        RemoteLogConnection.this.config.put("_termAuth", jo.optString("termAuth"));
                        RemoteLogConnection.this.logs = jo.getJSONArray("logs").toList().stream().map((obj) -> obj.toString()).collect(Collectors.toList());
                        updateRemoteMetadata(jo);
                        JSONObject logSelection = new JSONObject();
                        logSelection.put("log", RemoteLogConnection.this.config.optString("_select",
                            RemoteLogConnection.this.logs.isEmpty() ? getName() : RemoteLogConnection.this.logs.get(0)));
                        transmit(logSelection);
                        JSONObject filterSelection = new JSONObject();
                        filterSelection.put("filter", RemoteLogConnection.this.config.optString("_filter", ""));
                        transmit(filterSelection);
                    } else if (action.equals("presets")) {
                        updateRemoteMetadata(jo);
                    }
                } else if (jo.has("name") && jo.has("hostname")) {
                        updateRemoteMetadata(jo);
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

        private void updateRemoteMetadata(JSONObject message)
        {
            if (!RemoteLogConnection.this.localNameSpecified && message.has("name"))
                RemoteLogConnection.this.config.put("_name", message.getString("name"));
            if (RemoteLogConnection.this.config == LogSpoutMain.settings)
            {
                if (message.has("presets"))
                    LogSpoutMain.setRemotePresets(RemoteLogConnection.this.config, message.getJSONArray("presets"));
                if (LogSpoutMain.apiWebServer != null)
                {
                    LogSpoutMain.apiWebServer.broadcastPresets();
                    LogSpoutMain.apiWebServer.broadcastNameToUnauthenticated();
                }
            }
        }

        @OnWebSocketConnect
        public void onConnect(Session session) throws IOException 
        {
            // System.err.println("Connected websocket");
            if (RemoteLogConnection.this.socket != this)
            {
                // A stale attempt (e.g. one that timed out) connected late.
                session.close();
                return;
            }
            if (session instanceof WebSocketSession) {
                RemoteLogConnection.this.session = (WebSocketSession) session;
            } else {
                // System.err.println("Not an instance of WebSocketSession");
            }
        }

        @OnWebSocketClose
        public void onClose(Session session, int status, String reason) {
            // System.err.println("Close websocket");
            if (RemoteLogConnection.this.socket != this)
                return;
            RemoteLogConnection.this.close();
            RemoteLogConnection.this.session = null;
        }

        @OnWebSocketError
        public void onError(Throwable e) 
        {
            //e.printStackTrace(System.err);
            if (RemoteLogConnection.this.socket != this)
                return;
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
                if (!this.isReallyConnected())
                {
                    //System.err.println("RemoteLogConnection - No connection detected by keep alive reconnecting...");
                    this.close();
                    this.session = null;
                    this.openSession();
                }
                Thread.sleep(10000);
            } catch (Exception e) {
                //e.printStackTrace();
            }
        }
        try
        {
            //System.err.print("RemoteLogConnection - Stopping websocketClient....");
            this.webSocketClient.stop();
            //System.err.println("DONE");
        } catch (Exception e) {
            //e.printStackTrace(System.err);
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

    @Override
    public long getAgeMillis() {
        return System.currentTimeMillis() - this.createdAt;
    }
}
