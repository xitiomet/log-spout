package org.openstatic;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Random;
import java.util.StringTokenizer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.cli.*;
import org.json.*;
import org.openstatic.log.*;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.JexlFeatures;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.MapContext;

public class LogSpoutMain
{
    public static JSONObject settings;
    public static JexlEngine jexl;
    public static boolean verbose = false;
    public static APIWebServer apiWebServer;

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

    public static boolean isMatch(String line, String filter)
    {
        if (filter != null)
        {
            try
            {
                String exp = filter.replaceAll("\\(", "( ").replaceAll("\\)", " )");
                JexlContext jexlContext = new MapContext();
                StringTokenizer st = new StringTokenizer(exp);
                String newFilter = filter + "";
                while(st.hasMoreElements())
                {
                    String el = st.nextToken().trim();
                    if (el.equals("(") || el.equals(")") || el.equals("||") || el.equals("&&"))
                    {
                        //Ignore
                    } else {
                        String var = generateBigAlphaKey(3);
                        if (el.startsWith("!"))
                        {
                            boolean contained = !line.contains(el.substring(1));
                            jexlContext.set(var, contained);
                            newFilter = newFilter.replaceAll(Pattern.quote(el),var);
                        } else {
                            boolean contained = line.contains(el);
                            jexlContext.set(var, contained);
                            newFilter = newFilter.replaceAll(Pattern.quote(el),var);
                        }
                    }
                }
                JexlExpression expression = jexl.createExpression(newFilter);
                return (Boolean)expression.evaluate(jexlContext);
            } catch (Exception e) {
                return false;
            }
        } else {
            return true;
        }
    }

    public static void main(String[] args)
    {
        JexlFeatures features = new JexlFeatures()
                .loops(false)
                .sideEffectGlobal(false)
                .sideEffect(false);
        // Restricted permissions to a safe set but with URI allowed
        LogSpoutMain.jexl = new JexlBuilder().features(features).create();

        org.eclipse.jetty.util.log.Log.setLog(new NoLogging());
        //System.setProperty("org.eclipse.jetty.util.log.class", "org.eclipse.jetty.util.log.StdErrLog");
        System.setProperty("org.eclipse.jetty.LEVEL", "OFF");
        //System.setProperty("org.eclipse.jetty.server.Request.maxFormContentSize","1000000000");
        
        CommandLine cmd = null;
        Options options = new Options();
        CommandLineParser parser = new DefaultParser();
        LogSpoutMain.settings = new JSONObject();
        options.addOption(new Option("?", "help", false, "Shows help"));
        options.addOption(new Option("f", "config", true, "Specify config file"));
        options.addOption(new Option("e", "expression", true, "Specify an expression for filtering log data"));
        options.addOption(new Option("s", "stdout", false, "Print logs to STDOUT"));
        options.addOption(new Option("c", "connect", true, "Connect to a logspout server (ws://hostname:port)"));
        options.addOption(new Option("p", "password", true, "Set password to send to remote connection"));
        options.addOption(new Option("l", "log", true, "Select which log to view"));
        options.addOption(new Option("v", "verbose", false, "Turn on verbose output"));
        Option apiOption = new Option("a", "api", true, "Turn on api server, optional argument to specify port (default 8662)");
        apiOption.setOptionalArg(true);
        options.addOption(apiOption);

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
            if (cmd.hasOption("v"))
            {
                LogSpoutMain.verbose = true;
            }
            if (cmd.hasOption("f"))
            {
                String filename = cmd.getOptionValue("f");
                File file = new File(filename);
                if (file.exists())
                {
                    LogSpoutMain.settings = loadJSONObject(file);
                } else {
                    System.err.println("Cannot load file: " + filename);
                    System.exit(0);
                }
            } else if (!cmd.hasOption("c")) {
                File file = new File("./.log-spout.json");
                if (file.exists())
                {
                    LogSpoutMain.settings = loadJSONObject(file);
                } else {
                    showHelp(options);
                }
            }

            if (cmd.hasOption("e"))
            {
                LogSpoutMain.settings.put("_filter", cmd.getOptionValue("e"));
            }

            if (cmd.hasOption("c"))
            {
                LogSpoutMain.settings.put("_remote", cmd.getOptionValue("c"));
            }

            if (cmd.hasOption("l"))
            {
                LogSpoutMain.settings.put("_select", cmd.getOptionValue("l"));
            }

            if (cmd.hasOption("p"))
            {
                LogSpoutMain.settings.put("_remote_password", cmd.getOptionValue("p"));
            }

            if (cmd.hasOption("a"))
            {
                LogSpoutMain.settings.put("apiPort", Integer.valueOf(cmd.getOptionValue("a", "8662")));
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
                    if (LogSpoutMain.settings.has("_select"))
                    {
                        String select = LogSpoutMain.settings.getString("_select");
                        if (logPath.contains(select))
                        {
                            System.err.println(line);
                        }
                    } else {
                        System.err.println(line);
                    }
                }

                @Override
                public void onLogDisconnectError(LogConnection connection, String err) {
                    
                }
                
            });
        }
        if (lcc != null && (settings.has("apiPort") || settings.has("apiPassword")))
        {
            LogSpoutMain.apiWebServer = new APIWebServer(lcc);
            Runtime.getRuntime().addShutdownHook(new Thread() {
                public void run()
                {
                    lcc.disconnect();
                }
            });
            showIPS();
        }
        while(lcc != null)
        {
            try
            {
                Thread.sleep(1000);
            } catch (Exception e) {}
        }
    }

    public static void showIPS()
    {
        try
        {
            System.err.println("Web Interface:");
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface netint : Collections.list(nets))
            {
                //System.err.println("Interface: " + netint.getDisplayName());
                // Create a JmDNS instance
                Enumeration<InetAddress> addresses = netint.getInetAddresses();
                Collections.list(addresses).forEach((address) -> {
                    try
                    {
                        System.err.println("  http://" + address.toString() + ":" + String.valueOf(LogSpoutMain.settings.optInt("apiPort", 8662)) + "/");
                    } catch (Exception e) {}
                });
            }
        } catch (Exception e) {

        }
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
