package org.openstatic;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.regex.Pattern;

import org.apache.commons.cli.*;
import org.json.*;
import org.openstatic.log.*;

public class LogSpoutMain
{
    public static void main(String[] args)
    {
        CommandLine cmd = null;
        Options options = new Options();
        CommandLineParser parser = new DefaultParser();
        JSONObject baseConfig = new JSONObject();
        options.addOption(new Option("?", "help", false, "Shows help"));
        options.addOption(new Option("f", "config", true, "Specify config file"));
        options.addOption(new Option("c", "contains", true, "Specify a comma seperated list of strings to match each line against. Must contain one of the strings provided."));
        try
        {
            cmd = parser.parse(options, args);

            if (cmd.hasOption("?"))
            {
                showHelp(options);
            }
            if (cmd.hasOption("f"))
            {
                File file = new File(cmd.getOptionValue("f"));
                baseConfig = loadJSONObject(file);
            }

            if (cmd.hasOption("c"))
            {
                baseConfig.put("_contains", new JSONArray(cmd.getOptionValue("c").split(Pattern.quote(","))));
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }

        final LogConnection lc = LogConnectionParser.parse(baseConfig);
        lc.addLogConnectionListener(new LogConnectionListener() {

            @Override
            public void onLine(String line, JSONObject config) {
                System.err.println(line);
            }
            
        });
        lc.connect();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run()
            {
                lc.disconnect();
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
}
