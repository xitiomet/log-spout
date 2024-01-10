package org.openstatic.log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Iterator;

import org.json.JSONArray;
import org.json.JSONObject;
import org.openstatic.LogSpoutMain;
import org.apache.commons.text.StringEscapeUtils;

public class ProcessLogConnection implements LogConnection, Runnable
{
    private ArrayList<LogConnectionListener> listeners;
    private ProcessBuilder processBuilder;
    private Process process;
    private Thread thread;
    private InputStream inputStream;
    private JSONObject config;
    private boolean userDisconnect;
    private ArrayList<String> commandArray;
    private Exception exception;

    public ProcessLogConnection(JSONObject config)
    {
        this.userDisconnect = false;
        //System.err.println("Process: " + config.toString());
        JSONArray command = config.optJSONArray("_execute");
        this.commandArray = new ArrayList<String>();
        for(int i = 0; i < command.length(); i++)
        {
            String cs = command.getString(i);
            Set<String> keySet = config.keySet();
            for(Iterator<String> keyIterator = keySet.iterator(); keyIterator.hasNext();)
            {
                String key = keyIterator.next();
                cs = cs.replaceAll(Pattern.quote("$(" + key + ")"), config.get(key).toString());
            }
            commandArray.add(cs);
        }
        //System.err.println("CommandArray: " + commandArray.stream().collect(Collectors.joining(" ")));
        this.config = config;
        this.listeners = new ArrayList<LogConnectionListener>();
        this.processBuilder = new ProcessBuilder(this.commandArray);
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
        try
        {
            this.userDisconnect = false;
            if (this.process != null)
            {
                if (!process.isAlive())
                {
                    this.process = this.processBuilder.start();
                    this.inputStream = this.process.getInputStream();
                }
            } else {
                this.process = this.processBuilder.start();
                this.inputStream = this.process.getInputStream();
            }
            if (LogSpoutMain.verbose)
                System.err.println("Launched: " + this.commandArray.stream().collect(Collectors.joining(" ")));
            if (this.thread == null)
            {
                this.thread = new Thread(this);
                this.thread.start();
            } else {
                if (!this.thread.isAlive())
                {
                    this.thread = new Thread(this);
                    this.thread.start();
                }
            }
        } catch (Exception e) {
            this.exception = e;
            e.printStackTrace(System.err);
        }
    }

    @Override
    public void disconnect() 
    {
        try
        {
            this.userDisconnect = true;
            if (this.process != null)
               this.process.destroyForcibly().waitFor();
            if (this.inputStream != null)
            {
                this.inputStream.close();
            }
            this.inputStream = null;
            if (LogSpoutMain.verbose)
                System.err.println("Terminated: " + this.commandArray.stream().collect(Collectors.joining(" ")));
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    @Override
    public void run()
    {
        String errMsg = "";
        InputStreamReader isr = new InputStreamReader(this.inputStream);
        BufferedReader br = new BufferedReader(isr);
        String line;
        try
        {
            while((line = br.readLine()) != null)
            {
                String linePrefix = "";
                String lineSuffix = "";
                if (this.config.optBoolean("_unescape", true))
                    line = StringEscapeUtils.unescapeJava(line.replaceAll(Pattern.quote("\\x"), "\\\\u00"));
                if (this.config.optBoolean("_urldecode", false))
                    line = URLDecoder.decode(line,Charset.forName("UTF-8"));
                if (this.config.has("_highlight"))
                {
                    JSONObject rules = this.config.getJSONObject("_highlight");
                    Set<String> keySet = rules.keySet();
                    Iterator<String> keyIterator = keySet.iterator();
                    while(keyIterator.hasNext())
                    {
                        String key = keyIterator.next();
                        if (line.contains(key))
                        {
                            linePrefix = rules.getString(key);
                            lineSuffix = "\u001b[0m";
                        }
                    }
                }
                final String fLine = linePrefix + line + lineSuffix;
                ((ArrayList<LogConnectionListener>) this.listeners.clone()).forEach((listener) -> {
                    ArrayList<String> logPath = new ArrayList<String>();
                    logPath.add(this.getName());
                    listener.onLine(fLine, logPath, ProcessLogConnection.this);
                });
            }
            errMsg = "EOS";
        } catch (Exception e) {
            this.exception = e;
            //e.printStackTrace();
        }
        if (!userDisconnect)
        {
            if (this.exception != null)
                errMsg = this.exception.toString() + " - " + this.exception.getMessage();
            final String fErrMsg = errMsg;
            ((ArrayList<LogConnectionListener>) this.listeners.clone()).forEach((listener) -> {
                listener.onLogDisconnectError(this, fErrMsg);
            });
        }
        //System.err.println("PROC END");
    }

    @Override
    public String getName()
    {
        return LogConnectionParser.replaceVariables(this.config.optString("_name", ""), config);
    }
    
    @Override
    public String getType()
    {
        return "process";
    }

    @Override
    public boolean isConnected()
    {
        if (this.thread != null)
        {
            if (this.thread.isAlive())
                return this.inputStream != null;
        }
        return false;
    }

    @Override
    public void start() {
       
    }

    @Override
    public Collection<String> getContainedNames() 
    {
        return new ArrayList<String>();
    }
}
