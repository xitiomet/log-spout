package org.openstatic.log;

import org.json.*;
import org.openstatic.LogSpoutMain;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.Stack;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Arrays;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Server;

import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.ajax.JSON;

public class APIWebServer implements Runnable, LogConnectionListener
{
    private Server httpServer;
    protected ArrayList<WebSocketSession> wsSessions;
    protected HashMap<WebSocketSession, JSONObject> sessionProps;
    private Thread pingPongThread;
    private ArrayList<JSONObject> packetHistory;
    private LogConnectionContainer logConnections;
    protected static APIWebServer instance;

    public APIWebServer(LogConnectionContainer lc)
    {
        this.logConnections = lc;
        this.packetHistory = new ArrayList<JSONObject>();
        APIWebServer.instance = this;
        this.wsSessions = new ArrayList<WebSocketSession>();
        this.sessionProps = new HashMap<WebSocketSession, JSONObject>();
        httpServer = new Server(LogSpoutMain.settings.optInt("apiPort", 8662));
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        context.addServlet(ApiServlet.class, "/logspout/api/*");
        context.addServlet(EventsWebSocketServlet.class, "/logspout/*");
        try {
            context.addServlet(InterfaceServlet.class, "/*");
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        httpServer.setHandler(context);
        try {
            httpServer.start();
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        this.pingPongThread = new Thread(this);
        this.pingPongThread.start();
        this.logConnections.addLogConnectionListener(this);
    }

    public void replaceLogConnectionContainer(LogConnectionContainer lc)
    {
        if (this.logConnections != null)
        {
            this.logConnections.disconnect();
        }
        this.logConnections = lc;
    }

    public static synchronized String generateBigAlphaKey(int key_length)
    {
        try
        {
            // make sure we never get the same millis!
            Thread.sleep(1);
        } catch (Exception e) {}
        Random n = new Random(System.currentTimeMillis());
        String alpha = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuffer return_key = new StringBuffer();
        for (int i = 0; i < key_length; i++)
        {
            return_key.append(alpha.charAt(n.nextInt(alpha.length())));
        }
        String randKey = return_key.toString();
        //System.err.println("Generated Rule ID: " + randKey);
        return randKey;
    }

    public void addHistory(JSONObject obj)
    {
        if (obj != null)
        {
            this.packetHistory.add(obj);
            if (this.packetHistory.size() > 1000)
                this.packetHistory.remove(0);
        }
    }

    public static ArrayList<String> getLogNames(LogConnection lc)
    {
        final ArrayList<String> rl = new ArrayList<String>();
        String lName = lc.getName();
        if (!"".equals(lName))
            rl.add(lName);
        if (lc instanceof LogConnectionContainer)
        {
            LogConnectionContainer lcc = (LogConnectionContainer) lc;
            lcc.getLogConnections().forEach((slc) -> {
                rl.addAll(getLogNames(slc));
            });
        }
        return rl;
    }

    public static void sendAuthOk(WebSocketSession session, String termAuth)
    {
        JSONObject authJsonObject = new JSONObject();
        authJsonObject.put("action", "authOk");
        authJsonObject.put("termAuth", termAuth);
        authJsonObject.put("availableHistory", APIWebServer.instance.packetHistory.size());
        authJsonObject.put("logs", getLogNames(APIWebServer.instance.logConnections));
        authJsonObject.put("hostname", LogSpoutMain.settings.optString("hostname", "LOGSPOUT"));
        authJsonObject.put("name", APIWebServer.instance.logConnections.getName());
        session.getRemote().sendStringByFuture(authJsonObject.toString());
    }

    public void handleWebSocketEvent(JSONObject j, WebSocketSession session) 
    {
        JSONObject sessionProperties = this.sessionProps.get(session);
        if (!sessionProperties.optBoolean("auth", false))
        {
            String settingPassword = LogSpoutMain.settings.optString("apiPassword","");
            if (j.has("apiPassword"))
            {
                boolean authGood = settingPassword.equals(j.optString("apiPassword",""));
                if (authGood)
                {
                    String termAuth = generateBigAlphaKey(16);
                    sessionProperties.put("auth", true);
                    sessionProperties.put("termAuth", termAuth);
                    sendAuthOk(session,termAuth);
                } else {
                    JSONObject errorJsonObject = new JSONObject();
                    errorJsonObject.put("action", "authFail");
                    errorJsonObject.put("error", "Invalid apiPassword!");
                    session.getRemote().sendStringByFuture(errorJsonObject.toString());
                }
            } else if (j.has("termAuth")) {
                String termAuth = j.optString("termAuth", "");
                if (validateTermAuth(termAuth))
                {
                    sessionProperties.put("auth", true);
                    sessionProperties.put("termAuth", termAuth);
                    sendAuthOk(session,termAuth);
                }
            }
        }
        
        if (sessionProperties.optBoolean("auth", false))
        {
            if (j.has("history")) 
            {
                int historyRequest = j.optInt("history", 100);
                if (historyRequest > this.packetHistory.size())
                    historyRequest = this.packetHistory.size();
                for(int i = this.packetHistory.size() - historyRequest; i < this.packetHistory.size(); i++)
                {
                    JSONObject hp = this.packetHistory.get(i);
                    if (hp != null)
                    {
                        String histPacket = hp.toString();
                        session.getRemote().sendStringByFuture(histPacket);
                    }
                }
            } else if (j.has("filter")) {
                sessionProperties.put("filter", j.optString("filter", ""));
            } else if (j.has("log")) {
                sessionProperties.put("log", j.optString("log", ""));
            }
        } else {
            JSONObject errorJsonObject = new JSONObject();
            errorJsonObject.put("error", "Not Authorized!");
            session.getRemote().sendStringByFuture(errorJsonObject.toString());
        }
        this.sessionProps.put(session, sessionProperties);
    }

    public boolean validateTermAuth(String termAuth)
    {
        if (termAuth == null) return false;
        List<String> termAuths = this.sessionProps.values().stream().map((p) -> p.optString("termAuth", null)).filter((v) -> { return !"".equals(v) && v != null; }).collect(Collectors.toList());
        return termAuths.contains(termAuth);
    }

    private static String[] JSONArrayToStringArray(JSONArray arry)
    {
        String[] args = new String[arry.length()];
        for (int i = 0; i < arry.length(); i++)
        {
            args[i] = arry.getString(i);
        }
        return args;
    }

    public void broadcastJSONObject(JSONObject jo) 
    {
        String message = jo.toString();
        for (Session s : this.wsSessions)
        {
            try
            {
                JSONObject sessionProps = this.sessionProps.get(s);
                if (sessionProps.optBoolean("auth", false))
                {
                    s.getRemote().sendStringByFuture(message);
                }
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }
    }

    public void sendJSONObject(JSONObject jo, long termId) 
    {
        String message = jo.toString();
        for (Session s : this.wsSessions) 
        {
            try
            {
                JSONObject sessionProps = this.sessionProps.get(s);
                if (sessionProps.optBoolean("auth", false) && sessionProps.optLong("termId", 0) == termId)
                {
                    s.getRemote().sendStringByFuture(message);
                }
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }
    }

    public void handleCommand(WebSocketSession session, JSONObject sessionProperties, String command, String[] args)
    {
        long termId = sessionProperties.optLong("termId", 0);
        
    }

    public static class EventsWebSocketServlet extends WebSocketServlet {
        @Override
        public void configure(WebSocketServletFactory factory) {
            // factory.getPolicy().setIdleTimeout(10000);
            factory.register(EventsWebSocket.class);
        }
    }

    @WebSocket
    public static class EventsWebSocket {

        @OnWebSocketMessage
        public void onText(Session session, String message) throws IOException
        {
            try {
                JSONObject jo = new JSONObject(message);
                if (session instanceof WebSocketSession) {
                    WebSocketSession wssession = (WebSocketSession) session;
                    APIWebServer.instance.handleWebSocketEvent(jo, wssession);
                } else {
                    //System.err.println("not instance of WebSocketSession");
                }
            } catch (Exception e) {
                e.printStackTrace(System.err);
            }
        }

        @OnWebSocketConnect
        public void onConnect(Session session) throws IOException {
            //System.err.println("@OnWebSocketConnect");
            if (session instanceof WebSocketSession) {
                WebSocketSession wssession = (WebSocketSession) session;
                //System.out.println(wssession.getRemoteAddress().getHostString() + " connected!");
                APIWebServer.instance.wsSessions.add(wssession);
                JSONObject sessionProperties = new JSONObject();
                String settingPassword = LogSpoutMain.settings.optString("apiPassword","");

                if (settingPassword.equals(""))
                {
                    String termAuth = generateBigAlphaKey(16);
                    sessionProperties.put("auth", true);
                    sessionProperties.put("termAuth", termAuth);
                    APIWebServer.sendAuthOk(wssession, termAuth);
                } else {
                    JSONObject hostJsonObject = new JSONObject();
                    hostJsonObject.put("hostname", LogSpoutMain.settings.optString("hostname", "LOGSPOUT"));
                    hostJsonObject.put("name", APIWebServer.instance.logConnections.getName());
                    session.getRemote().sendStringByFuture(hostJsonObject.toString());
                }
                APIWebServer.instance.sessionProps.put(wssession, sessionProperties);
            } else {
                //System.err.println("Not an instance of WebSocketSession");
            }
        }

        @OnWebSocketClose
        public void onClose(Session session, int status, String reason) {
            //System.err.println(reason);
            if (session instanceof WebSocketSession) {
                WebSocketSession wssession = (WebSocketSession) session;
                APIWebServer.instance.wsSessions.remove(wssession);
                APIWebServer.instance.sessionProps.remove(wssession);
            }
        }

        @OnWebSocketError
        public void onError(Session session, Throwable err) {
            //err.printStackTrace(System.err);
        }

    }

    public static class ApiServlet extends HttpServlet {
        public JSONObject readJSONObjectPOST(HttpServletRequest request) {
            StringBuffer jb = new StringBuffer();
            String line = null;
            try {
                BufferedReader reader = request.getReader();
                while ((line = reader.readLine()) != null) {
                    jb.append(line);
                }
            } catch (Exception e) {
                //e.printStackTrace(System.err);
            }

            try {
                JSONObject jsonObject = new JSONObject(jb.toString());
                return jsonObject;
            } catch (JSONException e) {
                e.printStackTrace(System.err);
                return new JSONObject();
            }
        }

        public boolean isNumber(String v) {
            try {
                Integer.parseInt(v);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse httpServletResponse)
                throws ServletException, IOException {
                    httpServletResponse.setContentType("text/javascript");
                    httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                    httpServletResponse.setCharacterEncoding("iso-8859-1");
                    String target = request.getPathInfo();
                    //System.err.println("Path: " + target);
                    JSONObject response = new JSONObject();
                    if (request.getContentType().contains("text/javascript") || request.getContentType().contains("application/json"))
                    {
                        JSONObject requestPost = readJSONObjectPOST(request);
                        if (LogSpoutMain.settings.optString("apiPassword","").equals(requestPost.optString("apiPassword","")) || APIWebServer.instance.validateTermAuth(requestPost.optString("termAuth",null)))
                        {       
                            try {
                                if (target.equals("/transmit/"))
                                {
                                    
                                }
                            } catch (Exception x) {
                                x.printStackTrace(System.err);
                            }
                        } else {
                            response.put("error", "Invalid apiPassword or termAuth!");
                        }
                    } 
                    httpServletResponse.getWriter().println(response.toString());
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse httpServletResponse)
                throws ServletException, IOException {
            String responseType = "text/javascript";
            String target = request.getPathInfo();
            //System.err.println("Path: " + target);
            Set<String> parameterNames = request.getParameterMap().keySet();
            JSONObject response = new JSONObject();
            if (LogSpoutMain.settings.optString("apiPassword","").equals(request.getParameter("apiPassword")) || APIWebServer.instance.validateTermAuth(request.getParameter("termAuth")))
            {
                if (target.equals("/transmit/"))
                {
                    
                } else if (target.equals("/settings/")) {
                    Set<String> keySet = LogSpoutMain.settings.keySet();
                    for(String key : keySet)
                    {
                        if (!"apiPassword".equals(key))
                        {
                            response.put(key, LogSpoutMain.settings.opt(key));
                        }
                    }
                }
            } else {
                response.put("error", "Invalid apiPassword!");
            }
            if ("text/javascript".equals(responseType))
            {
                httpServletResponse.setContentType("text/javascript");
                httpServletResponse.setStatus(HttpServletResponse.SC_OK);
                httpServletResponse.setCharacterEncoding("iso-8859-1");
                httpServletResponse.getWriter().println(response.toString());
            }
            //request.setHandled(true);
        }
    }

    @Override
    public void run()
    {
        while(this.httpServer.isRunning())
        {
            try
            {
                JSONObject pingJSON = new JSONObject();
                pingJSON.put("action", "ping");
                pingJSON.put("timestamp", System.currentTimeMillis());
                broadcastJSONObject(pingJSON);
                Thread.sleep(10000);
            } catch (Exception e) {

            }
        }
    }

    @Override
    public void onLine(String line, ArrayList<String> logPath, LogConnection connection) 
    {
        JSONObject jo = new JSONObject();
        jo.put("action", "line");
        jo.put("line", line);
        jo.put("connection", connection.getName());
        String message = jo.toString();
        for (Session s : this.wsSessions)
        {
            try
            {
                JSONObject sessionProps = this.sessionProps.get(s);
                if (sessionProps != null)
                {
                    boolean fitsFilter = false;
                    if (sessionProps.optBoolean("auth", false))
                    {
                        if (sessionProps.has("log"))
                        {
                            String log = sessionProps.optString("log","");
                            if (logPath.contains(log) && !"".equals(log))
                            {
                                if (sessionProps.has("filter"))
                                {
                                    String filter = sessionProps.optString("filter","");
                                    if (filter.equals(""))
                                    {
                                        fitsFilter = true;
                                    } else {
                                        try
                                        {
                                            fitsFilter = LogSpoutMain.isMatch(line, filter);
                                        } catch (Exception e2) {
                                            e2.printStackTrace(System.err);
                                        }
                                    }
                                } else {
                                    fitsFilter = true;
                                }
                            }
                        }
                    }
                    if (fitsFilter)
                        s.getRemote().sendStringByFuture(message);
                }
            } catch (Exception e) {
                //e.printStackTrace(System.err);
            }
        }
        addHistory(jo);
    }

    @Override
    public void onLogDisconnectError(LogConnection connection, String err) {
        
    }
}
