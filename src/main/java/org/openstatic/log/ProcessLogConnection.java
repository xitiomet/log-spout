package org.openstatic.log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Iterator;

import org.json.JSONArray;
import org.json.JSONObject;

public class ProcessLogConnection implements LogConnection, Runnable
{
    private ArrayList<LogConnectionListener> listeners;
    private ProcessBuilder processBuilder;
    private Process process;
    private Thread thread;
    private InputStream inputStream;
    private JSONObject config;
    private ArrayList<String> commandArray;

    public ProcessLogConnection(JSONObject config)
    {
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

    public ProcessLogConnection(ProcessBuilder processBuilder)
    {
        this.listeners = new ArrayList<LogConnectionListener>();
        this.processBuilder = processBuilder;
    }

    @Override
    public void addLogConnectionListener(LogConnectionListener listener) {
        if (!this.listeners.contains(listener))
            this.listeners.add(listener);
    }

    @Override
    public void removeLogConnectionListener(LogConnectionListener listener) {
        if (this.listeners.contains(listener))
            this.listeners.remove(listener);
    }

    @Override
    public void connect() 
    {
        try
        {
            if (this.process != null)
            {
                if (!process.isAlive())
                {
                    this.process = this.processBuilder.start();
                }
            } else {
                this.process = this.processBuilder.start();
            }
            System.err.println("Launched: " + this.commandArray.stream().collect(Collectors.joining(" ")));
            this.inputStream = this.process.getInputStream();
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
            e.printStackTrace(System.err);
        }
    }

    @Override
    public void disconnect() 
    {
        try
        {
            this.process.destroy();
            this.inputStream.close();
            this.inputStream = null;
            System.err.println("Terminated: " + this.commandArray.stream().collect(Collectors.joining(" ")));
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    @Override
    public void run()
    {
        InputStreamReader isr = new InputStreamReader(this.inputStream);
        BufferedReader br = new BufferedReader(isr);
        String line;
        try
        {
            while((line = br.readLine()) != null)
            {
                final String fLine = line;
                this.listeners.forEach((listener) -> {
                    listener.onLine(LogConnectionParser.replaceVariables(ProcessLogConnection.this.config.optString("_prefix",""), this.config) + fLine, ProcessLogConnection.this.config);
                });
                
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        //System.err.println("PROC END");
    }
    
}
