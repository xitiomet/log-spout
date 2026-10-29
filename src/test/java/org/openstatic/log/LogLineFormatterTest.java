package org.openstatic.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class LogLineFormatterTest
{
    @Test
    public void preservesDefaults()
    {
        assertEquals("a\tb!%20\u001b[31m",
            LogLineFormatter.format("a\\tb\\x21%20#033[31m", new JSONObject()));
    }

    @Test
    public void leavesMalformedUrlEncodingReadable()
    {
        assertEquals("invalid%zz", LogLineFormatter.format("invalid%zz",
            new JSONObject().put("_urldecode", true)));
    }

    @Test
    public void substitutesVariablesLiterally()
    {
        assertEquals("C:\\logs\\$access.log",
            LogConnectionParser.replaceVariables("$(file)",
                new JSONObject().put("file", "C:\\logs\\$access.log")));
    }

    @Test
    public void processStillUsesSharedFormatting() throws Exception
    {
        BlockingQueue<String> lines = new LinkedBlockingQueue<String>();
        BlockingQueue<String> disconnects = new LinkedBlockingQueue<String>();
        JSONObject config = new JSONObject().put("_type", "process")
            .put("_execute", new JSONArray().put(Path.of(System.getProperty("java.home"), "bin", "java").toString())
                .put("-cp").put(System.getProperty("java.class.path")).put(LogLineFormatterTest.class.getName()))
            .put("_urldecode", true)
            .put("_highlight", new JSONObject().put("hello world", "\u001b[33m"));
        LogConnection process = LogConnectionParser.parse(config);
        process.addLogConnectionListener(new LogConnectionListener()
        {
            @Override
            public void onLine(String line, ArrayList<String> logPath, LogConnection source)
            {
                lines.add(line);
            }

            @Override
            public void onLogDisconnectError(LogConnection source, String message, Exception exception)
            {
                disconnects.add(message);
            }
        });
        try
        {
            process.connect();
            assertEquals("\u001b[33mhello world!\u001b[31m\u001b[0m", lines.poll(5, TimeUnit.SECONDS));
            assertNotNull(disconnects.poll(5, TimeUnit.SECONDS));
        } finally {
            process.disconnect();
        }
    }

    public static void main(String[] args)
    {
        System.out.println("hello%20world\\x21#033[31m");
    }
}
