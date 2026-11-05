package org.openstatic;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.Random;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

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
    public static File configFile;
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
        
        Options options = commandLineOptions();
        CommandLineParser parser = new DefaultParser();
        LogSpoutMain.settings = new JSONObject();
        LogSpoutMain.configFile = null;

        boolean stdoutLogs = false;
        try
        {
            final CommandLine cmd = parser.parse(options, args);

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
            boolean temporaryConfig = "_TEMP_FILE_".equals(cmd.getOptionValue("f"));
            boolean defaultDirectoryConfig = cmd.hasOption("d") && cmd.hasOption("g") && !cmd.hasOption("f");
            boolean overwriteDefaultConfig = false;
            if (temporaryConfig && !cmd.hasOption("d"))
                throw new IllegalArgumentException("-f _TEMP_FILE_ requires -d / --directory");
            if (cmd.hasOption("d"))
            {
                LogSpoutMain.settings = generateDirectoryConfig(cmd.getOptionValue("d", "."));
                if (temporaryConfig && !cmd.hasOption("g"))
                {
                    LogSpoutMain.configFile = Files.createTempFile("log-spout-", ".json").toFile();
                    LogSpoutMain.configFile.deleteOnExit();
                } else if (cmd.hasOption("f") && !temporaryConfig) {
                    LogSpoutMain.configFile = new File(cmd.getOptionValue("f"));
                } else if (defaultDirectoryConfig) {
                    LogSpoutMain.configFile = new File("./.log-spout.json");
                    if (LogSpoutMain.configFile.exists())
                    {
                        System.err.print("Warning: " + LogSpoutMain.configFile.getAbsolutePath()
                            + " already exists. Overwrite? [y/N] ");
                        System.err.flush();
                        String answer = new BufferedReader(new InputStreamReader(System.in)).readLine();
                        if (answer == null || !(answer.trim().equalsIgnoreCase("y")
                            || answer.trim().equalsIgnoreCase("yes")))
                        {
                            System.err.println("Cancelled; existing config was not overwritten.");
                            return;
                        }
                        overwriteDefaultConfig = true;
                    }
                }
            } else if (cmd.hasOption("f"))
            {
                String filename = cmd.getOptionValue("f");
                File file = new File(filename);
                if (file.exists())
                {
                    LogSpoutMain.settings = loadJSONObject(file);
                    LogSpoutMain.configFile = file;
                } else {
                    System.err.println("Cannot load file: " + filename);
                    System.exit(0);
                }
            } else if (!cmd.hasOption("c")) {
                File file = new File("./.log-spout.json");
                if (file.exists())
                {
                    LogSpoutMain.settings = loadJSONObject(file);
                    LogSpoutMain.configFile = file;
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
                if (cmd.hasOption("a") && !settings.has("apiPassword"))
                {
                    LogSpoutMain.settings.put("apiPassword", cmd.getOptionValue("p"));
                }
                if (cmd.hasOption("c"))
                {
                    LogSpoutMain.settings.put("_remote_password", cmd.getOptionValue("p"));
                }
            }

            if (cmd.hasOption("a"))
            {
                LogSpoutMain.settings.put("apiPort", Integer.valueOf(cmd.getOptionValue("a", "8662")));
            }

            if (cmd.hasOption("d"))
            {
                if (LogSpoutMain.configFile == null)
                {
                    if (!cmd.hasOption("g"))
                    {
                        System.out.println(LogSpoutMain.settings.toString(2));
                        return;
                    }
                } else {
                    if (defaultDirectoryConfig && !overwriteDefaultConfig)
                        Files.writeString(LogSpoutMain.configFile.toPath(), LogSpoutMain.settings.toString(2),
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    else
                        Files.writeString(LogSpoutMain.configFile.toPath(), LogSpoutMain.settings.toString(2));
                }
            }

            if (cmd.hasOption("g"))
            {
                File filename = launchScriptFile(cmd.getOptionValue("g"));
                Files.writeString(filename.toPath(), generateLaunchScript(cmd));
                if (!isWindows() && !filename.setExecutable(true))
                    throw new IOException("Cannot make script executable: " + filename);
                System.err.println("Generated: " + filename);
                return;
            }

            if (!LogSpoutMain.settings.has("hostname"))
            {
                LogSpoutMain.settings.put("hostname", getLocalHostname());
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(1);
            return;
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
                            System.out.println(line);
                        }
                    } else {
                        System.out.println(line);
                    }
                }

                @Override
                public void onLogDisconnectError(LogConnection connection, String err, Exception exception) {
                    
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

    static Options commandLineOptions()
    {
        Options options = new Options();
        options.addOption(new Option("?", "help", false, "Shows help"));
        options.addOption(new Option("f", "config", true, "Specify config file (use _TEMP_FILE_ with -d for a fresh temporary config)"));
        Option directoryOption = new Option("d", "directory", true,
            "Generate a tail config by recursively scanning a directory (default: current directory)");
        directoryOption.setOptionalArg(true);
        options.addOption(directoryOption);
        options.addOption(new Option("e", "expression", true, "Specify an expression for filtering log data"));
        options.addOption(new Option("s", "stdout", false, "Print logs to STDOUT"));
        options.addOption(new Option("c", "connect", true, "Connect to a logspout server (ws://hostname:port)"));
        options.addOption(new Option("g", "generate", true, "Generate a launch script (Windows: .cmd, POSIX: Bash)"));
        options.addOption(new Option("p", "password", true, "Set password to send to remote connection -c, or for local api -a"));
        options.addOption(new Option("l", "log", true, "Select which log to view"));
        options.addOption(new Option("v", "verbose", false, "Turn on verbose output"));
        Option apiOption = new Option("a", "api", true, "Turn on api server, optional argument to specify port (default 8662)");
        apiOption.setOptionalArg(true);
        options.addOption(apiOption);
        return options;
    }

    static JSONObject generateDirectoryConfig(String directory) throws IOException
    {
        Path root = Path.of(directory).toAbsolutePath().normalize();
        if (!Files.isDirectory(root))
            throw new IOException("Not a directory: " + directory);
        ArrayList<Path> files = new ArrayList<Path>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>()
        {
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException
            {
                String name = path.getFileName().toString();
                if (Files.isRegularFile(path) && (name.endsWith(".log") || name.endsWith(".out")
                    || name.endsWith(".err") || name.equals("syslog") || name.equals("dmesg")
                    || name.equals("messages")))
                {
                    try
                    {
                        if (isLikelyBinary(path))
                        {
                            System.err.println("Skipping binary log: " + path);
                            return FileVisitResult.CONTINUE;
                        }
                    } catch (AccessDeniedException error) {
                        return visitFileFailed(path, error);
                    }
                    files.add(path);
                    System.err.println("Found Log: " + path);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path path, IOException error) throws IOException
            {
                if (error instanceof AccessDeniedException)
                {
                    System.err.println("Skipping inaccessible path: " + path);
                    return FileVisitResult.CONTINUE;
                }
                throw error;
            }
        });
        Collections.sort(files);
        JSONArray sources = new JSONArray();
        for (Path path : files)
        {
            sources.put(new JSONObject()
                .put("_type", "tail").put("_file", path.toString()).put("_name", path.getFileName().toString()));
        }
        return new JSONObject().put("_name", directory).put("_sources", sources);
    }

    static boolean isLikelyBinary(Path path) throws IOException
    {
        byte[] sample;
        try (InputStream input = Files.newInputStream(path))
        {
            sample = input.readNBytes(8192);
        }
        int nonText = 0;
        for (byte value : sample)
        {
            int character = value & 0xff;
            if (character == 0)
                return true;
            if ((character < 32 && character != '\t' && character != '\n'
                && character != '\r' && character != 27) || character == 127)
                nonText++;
        }
        return nonText * 10 > sample.length;
    }

    static String generateLaunchScript(CommandLine cmd)
    {
        boolean windows = isWindows();
        StringBuilder command = new StringBuilder(windows
            ? "@echo off\r\nsetlocal DisableDelayedExpansion\r\nlog-spout.exe"
            : "#!/bin/bash\nlog-spout");
        boolean temporaryConfig = cmd.hasOption("d") && "_TEMP_FILE_".equals(cmd.getOptionValue("f"));
        for (Option option : cmd.getOptions())
        {
            if (option.getOpt().equals("g") || (option.getOpt().equals("d") && !temporaryConfig))
                continue;
            command.append(" -").append(option.getOpt());
            if (option.getValues() != null)
            {
                for (String value : option.getValues())
                    command.append(" ").append(windows ? batchQuote(value) : shellQuote(value));
            } else if (option.getOpt().equals("d")) {
                command.append(" ").append(windows ? batchQuote(".") : shellQuote("."));
            }
        }
        if (cmd.hasOption("d") && !cmd.hasOption("f"))
        {
            String file = Path.of(".log-spout.json").toAbsolutePath().toString();
            command.append(" -f ").append(windows ? batchQuote(file) : shellQuote(file));
        }
        if (!cmd.hasOption("s") && !cmd.hasOption("a"))
            command.append(" -s");
        for (String argument : cmd.getArgList())
            command.append(" ").append(windows ? batchQuote(argument) : shellQuote(argument));
        return command.append(windows ? "\r\n" : "\n").toString();
    }

    static File launchScriptFile(String filename)
    {
        File file = new File(filename);
        if (!isWindows())
            return file;
        String name = file.getName();
        int extension = name.lastIndexOf('.');
        if (extension > 0)
            name = name.substring(0, extension);
        return new File(file.getParentFile(), name + ".cmd");
    }

    private static boolean isWindows()
    {
        return File.separatorChar == '\\';
    }

    private static String batchQuote(String value)
    {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Windows script arguments cannot contain line breaks or NUL");
        StringBuilder quoted = new StringBuilder("\"");
        int backslashes = 0;
        for (char character : value.toCharArray())
        {
            if (character == '\\')
            {
                backslashes++;
            } else {
                quoted.append("\\".repeat(character == '"' ? backslashes * 2 + 1 : backslashes));
                quoted.append(character);
                backslashes = 0;
            }
        }
        quoted.append("\\".repeat(backslashes * 2)).append('"');
        StringBuilder escaped = new StringBuilder();
        for (char character : quoted.toString().toCharArray())
        {
            if (character == '%')
                escaped.append("%%");
            else
            {
                // CMD escaping is separate from the Windows process argument quoting above.
                if ("\"^&|<>()".indexOf(character) >= 0)
                    escaped.append('^');
                escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private static String shellQuote(String value)
    {
        return "'" + value.replace("'", "'\"'\"'") + "'";
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

    public static JSONArray getPresets()
    {
        if (LogSpoutMain.settings != null && LogSpoutMain.settings.has("_presets"))
        {
            return LogSpoutMain.settings.optJSONArray("_presets");
        }
        return new JSONArray();
    }

    public static synchronized boolean savePreset(String name, String log, String filter)
    {
        if (name == null || "".equals(name.trim()))
            return false;
        name = name.trim();
        if (LogSpoutMain.settings == null)
            return false;
        JSONArray presets = LogSpoutMain.settings.optJSONArray("_presets");
        if (presets == null)
        {
            presets = new JSONArray();
            LogSpoutMain.settings.put("_presets", presets);
        }
        JSONObject preset = null;
        for (int i = 0; i < presets.length(); i++)
        {
            JSONObject p = presets.optJSONObject(i);
            if (p != null && name.equals(p.optString("name", "")))
            {
                preset = p;
                break;
            }
        }
        if (preset == null)
        {
            preset = new JSONObject();
            preset.put("name", name);
            presets.put(preset);
        }
        preset.put("log", log == null ? "" : log);
        preset.put("filter", filter == null ? "" : filter);
        if (LogSpoutMain.configFile != null)
            saveJSONObject(LogSpoutMain.configFile, LogSpoutMain.settings);
        return true;
    }

    public static synchronized boolean deletePreset(String name)
    {
        if (name == null || LogSpoutMain.settings == null)
            return false;
        JSONArray presets = LogSpoutMain.settings.optJSONArray("_presets");
        if (presets == null)
            return false;
        boolean removed = false;
        for (int i = presets.length() - 1; i >= 0; i--)
        {
            JSONObject p = presets.optJSONObject(i);
            if (p != null && name.equals(p.optString("name", "")))
            {
                presets.remove(i);
                removed = true;
            }
        }
        if (removed && LogSpoutMain.configFile != null)
            saveJSONObject(LogSpoutMain.configFile, LogSpoutMain.settings);
        return removed;
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
