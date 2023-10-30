package org.openstatic;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;

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
        options.addOption(new Option("?", "help", false, "Shows help"));
        options.addOption(new Option("c", "config", true, "Specify config file"));
        try
        {
            cmd = parser.parse(options, args);

            if (cmd.hasOption("?"))
            {
                showHelp(options);
            }
            if (cmd.hasOption("c"))
            {
                File file = new File(cmd.getOptionValue("c"));
                JSONObject o = loadJSONObject(file);
                LogConnection lc = LogConnectionParser.parse(o);
                lc.addLogConnectionListener(new LogConnectionListener() {

                    @Override
                    public void onLine(String line) {
                        System.err.println(line);
                    }
                    
                });
                lc.connect();
            }
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }
    
    public static void showHelp(Options options)
    {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp( "log-spout", "Log Spout: A program for tapping into several log streams over ssh", options, "" );
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
