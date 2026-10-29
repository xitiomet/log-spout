package org.openstatic.log;

import static org.junit.Assert.*;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TailLogConnectionTest
{
    private Path file;
    private LogConnection connection;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<String>();
    private final BlockingQueue<Exception> errors = new LinkedBlockingQueue<Exception>();

    @Before
    public void createFile() throws Exception
    {
        this.file = Files.createTempFile("log-spout-$-tail-", ".log");
    }

    @After
    public void cleanup() throws Exception
    {
        if (this.connection != null)
            this.connection.disconnect();
        Files.deleteIfExists(this.file);
    }

    private JSONObject config()
    {
        return new JSONObject().put("_type", "tail")
            .put("file", this.file.toString()).put("_name", "$(file)");
    }

    private void connect(JSONObject config)
    {
        this.connection = LogConnectionParser.parse(config);
        this.connection.addLogConnectionListener(new LogConnectionListener()
        {
            @Override
            public void onLine(String line, ArrayList<String> logPath, LogConnection source)
            {
                assertSame(connection, source);
                assertEquals(connection.getName(), logPath.get(0));
                lines.add(line);
            }

            @Override
            public void onLogDisconnectError(LogConnection source, String message, Exception exception)
            {
                errors.add(exception);
            }
        });
        this.connection.connect();
    }

    private void write(String value, StandardOpenOption... options) throws Exception
    {
        Files.writeString(this.file, value, Charset.defaultCharset(), options);
    }

    private String nextLine() throws Exception
    {
        String line = this.lines.poll(5, TimeUnit.SECONDS);
        assertNotNull("Timed out waiting for a line; errors: " + this.errors, line);
        return line;
    }

    @Test
    public void emitsLastTenLinesAndFollowsAppends() throws Exception
    {
        StringBuilder contents = new StringBuilder();
        for (int i = 0; i < 30; i++)
            contents.append(i).append(":").append("x".repeat(1000)).append("\n");
        write(contents.toString());
        connect(config());
        assertEquals("tail", this.connection.getType());
        assertEquals(this.file.toString(), this.connection.getName());
        assertTrue(this.connection.getContainedNames().isEmpty());
        for (int i = 20; i < 30; i++)
            assertEquals(i + ":" + "x".repeat(1000), nextLine());
        this.connection.connect();
        write("appended\n", StandardOpenOption.APPEND);
        assertEquals("appended", nextLine());
        assertTrue(this.lines.isEmpty());
        assertTrue(this.connection.isConnected());
    }

    @Test
    public void holdsUnterminatedLastLineUntilAppend() throws Exception
    {
        StringBuilder contents = new StringBuilder();
        for (int i = 0; i < 11; i++)
            contents.append(i).append("\n");
        contents.append("partial");
        write(contents.toString());
        connect(config());
        for (int i = 2; i < 11; i++)
            assertEquals(String.valueOf(i), nextLine());
        assertNull(this.lines.poll(200, TimeUnit.MILLISECONDS));
        write(" end\n", StandardOpenOption.APPEND);
        assertEquals("partial end", nextLine());
    }

    @Test
    public void followsEmptyFileAndHandlesSplitCharacters() throws Exception
    {
        connect(config());
        assertNull(this.lines.poll(200, TimeUnit.MILLISECONDS));
        write("first\n", StandardOpenOption.APPEND);
        assertEquals("first", nextLine());
        byte[] encoded = "\u20ac\n".getBytes(Charset.defaultCharset());
        Files.write(this.file, new byte[] {encoded[0]}, StandardOpenOption.APPEND);
        assertNull(this.lines.poll(200, TimeUnit.MILLISECONDS));
        Files.write(this.file, java.util.Arrays.copyOfRange(encoded, 1, encoded.length),
            StandardOpenOption.APPEND);
        assertEquals("\u20ac", nextLine());
    }

    @Test
    public void resumesAfterTruncationAndReconnects() throws Exception
    {
        write("a sufficiently long original line\n");
        connect(config());
        assertEquals("a sufficiently long original line", nextLine());
        write("new\n", StandardOpenOption.TRUNCATE_EXISTING);
        assertEquals("new", nextLine());
        this.connection.disconnect();
        assertFalse(this.connection.isConnected());
        write("offline\n", StandardOpenOption.APPEND);
        this.connection.connect();
        assertEquals("new", nextLine());
        assertEquals("offline", nextLine());
        assertTrue(this.errors.isEmpty());
    }

    @Test
    public void followsDescriptorRatherThanReplacementFile() throws Exception
    {
        write("original\n");
        connect(config());
        assertEquals("original", nextLine());
        Path moved = this.file.resolveSibling(this.file.getFileName() + ".old");
        try
        {
            Files.move(this.file, moved);
            write("replacement\n");
            Files.writeString(moved, "old descriptor\n", Charset.defaultCharset(), StandardOpenOption.APPEND);
            assertEquals("old descriptor", nextLine());
            assertNull(this.lines.poll(200, TimeUnit.MILLISECONDS));
        } finally {
            this.connection.disconnect();
            Files.deleteIfExists(moved);
        }
    }

    @Test
    public void reportsMissingFile() throws Exception
    {
        Files.delete(this.file);
        connect(config());
        assertNotNull(this.errors.poll(5, TimeUnit.SECONDS));
        assertFalse(this.connection.isConnected());
        write("recovered\n");
        this.connection.connect();
        assertEquals("recovered", nextLine());
    }

    @Test
    public void parsesInheritedFileAndExpandsPathVariables() throws Exception
    {
        JSONObject source = new JSONObject().put("_type", "tail")
            .put("file", "$(directory)/$(filename)").put("_name", "$(filename)");
        JSONObject parent = new JSONObject().put("directory", this.file.getParent().toString())
            .put("filename", this.file.getFileName().toString())
            .put("_sources", new JSONArray().put(source));
        LogConnectionContainer root = LogConnectionParser.parseInitial(parent);
        this.connection = root;
        LogConnection leaf = root.getLogConnections().iterator().next();
        assertTrue(leaf instanceof TailLogConnection);
        assertEquals(this.file.getFileName().toString(), leaf.getName());
    }

    @Test
    public void appliesSameFormattingAsProcess() throws Exception
    {
        write("hello%20world\\x21#033[31m\n");
        connect(config().put("_urldecode", "true")
            .put("_highlight", new JSONObject().put("hello world", "\u001b[33m")));
        assertEquals("\u001b[33mhello world!\u001b[31m\u001b[0m", nextLine());
    }

    @Test
    public void canDisableLineTransformations() throws Exception
    {
        String raw = "hello%20world\\x21#033[31m";
        write(raw + "\r\n\n");
        connect(config().put("_unescape", false).put("_fixescape", false));
        assertEquals(raw, nextLine());
        assertEquals("", nextLine());
    }

    @Test(expected = org.json.JSONException.class)
    public void rejectsMissingFile()
    {
        LogConnectionParser.parse(new JSONObject().put("_type", "tail"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyFile()
    {
        LogConnectionParser.parse(new JSONObject().put("_type", "tail").put("file", ""));
    }
}
