package org.openstatic;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.cli.*;
import org.json.*;
import org.openstatic.log.*;

public class LogSpoutMain
{
    public static JSONObject settings;

    public static void main(String[] args)
    {
        org.eclipse.jetty.util.log.Log.setLog(new NoLogging());
        //System.setProperty("org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.StdErrLog");
        System.setProperty("org.eclipse.jetty.LEVEL", "OFF");
        System.setProperty("org.eclipse.jetty.server.Request.maxFormContentSize","1000000000");
        
        CommandLine cmd = null;
        Options options = new Options();
        CommandLineParser parser = new DefaultParser();
        LogSpoutMain.settings = new JSONObject();
        options.addOption(new Option("?", "help", false, "Shows help"));
        options.addOption(new Option("f", "config", true, "Specify config file"));
        options.addOption(new Option("c", "contains", true, "Specify a comma seperated list of strings to match each line against. Must contain one of the strings provided."));
        options.addOption(new Option("s", "stdout", false, "Print logs to STDOUT"));
        boolean stdoutLogs = false;
        try
        {
            cmd = parser.parse(options, args);

            if (cmd.hasOption("?"))
            {
                showHelp(options);
            }
            if (cmd.hasOption("s"))
            {
                stdoutLogs = true;
            }
            if (cmd.hasOption("f"))
            {
                File file = new File(cmd.getOptionValue("f"));
                LogSpoutMain.settings = loadJSONObject(file);
            }

            if (cmd.hasOption("c"))
            {
                LogSpoutMain.settings.put("_contains", new JSONArray(cmd.getOptionValue("c").split(Pattern.quote(","))));
            }

            if (!LogSpoutMain.settings.has("hostname"))
            {
                LogSpoutMain.settings.put("hostname", getLocalHostname());
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
        final LogConnectionContainer lcc = LogConnectionParser.parseInitial(LogSpoutMain.settings);
        if (stdoutLogs)
        {
            lcc.addLogConnectionListener(new LogConnectionListener() {

                @Override
                public void onLine(String line, ArrayList<String> logPath, LogConnection connection) {
                    System.err.println(line);
                }
                
            });
        }
        lcc.connect();
        APIWebServer apiWebServer = new APIWebServer(lcc);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run()
            {
                lcc.disconnect();
            }
        });
    }
    
    public static void showHelp(Options options)
    {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp( "log-spout", "Log Spout: A tool for tapping into several log streams at once", options, "" );
        System.exit(0);
    }

    public static JSONObject loadJSONObject(File file)
    {
        try
        {
            FileInputStream fis = new FileInputStream(file);
            StringBuilder builder = new StringBuilder();
            int ch;
            while((ch = fis.read()) != -1){
                builder.append((char)ch);
            }
            fis.close();
            JSONObject props = new JSONObject(builder.toString());
            return props;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static void saveJSONObject(File file, JSONObject obj)
    {
        try
        {
            FileOutputStream fos = new FileOutputStream(file);
            PrintStream ps = new PrintStream(fos);
            ps.print(obj.toString(2));
            ps.close();
            fos.close();
        } catch (Exception e) {
        }
    }

    public static String shellExec(String cmd[])
    {
        try
        {
            Process cmdProc = Runtime.getRuntime().exec(cmd);
            cmdProc.waitFor();
            return readStreamToString(cmdProc.getInputStream());
        } catch (Exception e) {
           
        }
        return null;
    }

    public static String readStreamToString(InputStream is)
    {
        String result = "";
        try
        {
            java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
            result = s.hasNext() ? s.next() : "";
            s.close();
        } catch (Exception e) {}
        return result;
    }

    public static String getLocalHostname()
    {
        String returnValue = "";
        Map<String, String> env = System.getenv();
        if (env.containsKey("COMPUTERNAME"))
        {
            returnValue = env.get("COMPUTERNAME");
        } else if (env.containsKey("HOSTNAME")) {
            returnValue = env.get("HOSTNAME");
        } else {
            String hostnameCommand = shellExec(new String[] {"hostname"});
            if (hostnameCommand != null)
            {
                String hostname = hostnameCommand.trim();
                if (!"".equals(hostname))
                    returnValue = hostname;
            }
        }
        if ("".equals(returnValue))
        {
            try
            {
                for(Enumeration<NetworkInterface> n = NetworkInterface.getNetworkInterfaces(); n.hasMoreElements() && "".equals(returnValue);)
                {
                    NetworkInterface ni = n.nextElement();
                    for(Enumeration<InetAddress> e = ni.getInetAddresses(); e.hasMoreElements() && "".equals(returnValue);)
                    {
                        InetAddress ia = e.nextElement();
                        if (!ia.isLoopbackAddress() && ia.isSiteLocalAddress())
                        {
                            String hostname = ia.getHostName();
                            returnValue = hostname;
                        }
                    }
                }

            } catch (Exception e) {}
        }
        if (returnValue.contains(".local"))
        {
            returnValue = returnValue.replace(".local", "");
        }
        if (returnValue.contains(".lan"))
        {
            returnValue = returnValue.replace(".lan", "");
        }
        return returnValue;
    }
}
