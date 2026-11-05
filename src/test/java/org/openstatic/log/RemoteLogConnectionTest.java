package org.openstatic.log;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.openstatic.LogSpoutMain;

public class RemoteLogConnectionTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();
    private JSONObject previousSettings;
    private File previousFile;
    private APIWebServer previousServer;

    @Before
    public void prepare()
    {
        previousSettings = LogSpoutMain.settings;
        previousFile = LogSpoutMain.configFile;
        previousServer = LogSpoutMain.apiWebServer;
        LogSpoutMain.settings = new JSONObject().put("_remote", "ws://localhost:8662");
        LogSpoutMain.configFile = null;
        LogSpoutMain.apiWebServer = null;
    }

    @After
    public void restore()
    {
        LogSpoutMain.setRemotePresets(LogSpoutMain.settings, new JSONArray());
        LogSpoutMain.settings = previousSettings;
        LogSpoutMain.configFile = previousFile;
        LogSpoutMain.apiWebServer = previousServer;
    }

    private JSONObject preset(String name, String filter)
    {
        return new JSONObject().put("name", name).put("log", "server.log").put("filter", filter);
    }

    private JSONObject auth(JSONArray presets)
    {
        return new JSONObject().put("action", "authOk").put("name", "Server Logs")
            .put("termAuth", "test").put("logs", new JSONArray().put("Server Logs").put("server.log"))
            .put("presets", presets);
    }

    @Test
    public void sharesPresetsWithoutPersistingThemEvenAfterLocalSave() throws Exception
    {
        JSONObject local = preset("Same", "local");
        LogSpoutMain.settings.put("_presets", new JSONArray().put(local));
        File file = temporary.newFile("config.json");
        Files.writeString(file.toPath(), LogSpoutMain.settings.toString());
        LogSpoutMain.configFile = file;
        RemoteLogConnection remote = new RemoteLogConnection(LogSpoutMain.settings);
        RemoteLogConnection.EventsWebSocket socket = remote.new EventsWebSocket();
        socket.onText(null, auth(new JSONArray().put(preset("Same", "remote"))
            .put(preset("Shared", "original"))).toString());
        JSONArray combined = LogSpoutMain.getPresets();
        assertEquals(2, combined.length());
        assertEquals("local", combined.getJSONObject(0).getString("filter"));
        String before = Files.readString(file.toPath());
        assertTrue(LogSpoutMain.savePreset("Shared", "server.log", "edited"));
        assertEquals("edited", LogSpoutMain.getPresets().getJSONObject(1).getString("filter"));
        assertEquals(before, Files.readString(file.toPath()));
        assertTrue(LogSpoutMain.savePreset("New Local", "", "new"));
        JSONObject saved = new JSONObject(Files.readString(file.toPath()));
        assertEquals(2, saved.getJSONArray("_presets").length());
        assertFalse(saved.toString().contains("Shared"));
        assertTrue(LogSpoutMain.deletePreset("Shared"));
        assertEquals(2, LogSpoutMain.getPresets().length());
        socket.onText(null, new JSONObject().put("action", "presets")
            .put("presets", new JSONArray().put(preset("Updated", "fresh"))).toString());
        assertEquals("Updated", LogSpoutMain.getPresets().getJSONObject(2).getString("name"));
        assertFalse(Files.readString(file.toPath()).contains("Updated"));
    }

    @Test
    public void adoptsServerRootNameAndRefreshesItOnAuthentication() throws Exception
    {
        LogConnectionContainer root = new LogConnectionContainer(LogSpoutMain.settings);
        RemoteLogConnection remote = new RemoteLogConnection(LogSpoutMain.settings);
        RemoteLogConnection.EventsWebSocket socket = remote.new EventsWebSocket();
        socket.onText(null, "{\"hostname\":\"host\",\"name\":\"Greeting\"}");
        assertEquals("Greeting", root.getName());
        socket.onText(null, auth(new JSONArray()).toString());
        assertEquals("Server Logs", root.getName());
        assertEquals("Server Logs", remote.getName());
    }

    @Test
    public void preservesExplicitNameAndDoesNotImportNestedRemotePresets() throws Exception
    {
        LogSpoutMain.settings.put("_name", "Local");
        RemoteLogConnection remote = new RemoteLogConnection(LogSpoutMain.settings);
        remote.new EventsWebSocket().onText(null, auth(new JSONArray().put(preset("Shared", ""))).toString());
        assertEquals("Local", remote.getName());
        LogSpoutMain.setRemotePresets(LogSpoutMain.settings, new JSONArray());
        RemoteLogConnection nested = new RemoteLogConnection(new JSONObject().put("_remote", "ws://localhost"));
        nested.new EventsWebSocket().onText(null, auth(new JSONArray().put(preset("Nested", ""))).toString());
        assertEquals(0, LogSpoutMain.getPresets().length());
    }

    @Test
    public void guessesWebSocketUrlsForBareAndPartialAddresses()
    {
        assertEquals(List.of("wss://oscar.parlor.me/", "wss://oscar.parlor.me/logspout/",
            "ws://oscar.parlor.me/", "ws://oscar.parlor.me/logspout/"),
            RemoteLogConnection.candidateUrls("oscar.parlor.me"));
        assertEquals(List.of("wss://host:8662/", "wss://host:8662/logspout/",
            "ws://host:8662/", "ws://host:8662/logspout/"), RemoteLogConnection.candidateUrls("host:8662"));
        assertEquals(List.of("wss://oscar.parlor.me/", "wss://oscar.parlor.me/logspout/"),
            RemoteLogConnection.candidateUrls("wss://oscar.parlor.me"));
        assertEquals(List.of("ws://host:8662/", "ws://host:8662/logspout/"),
            RemoteLogConnection.candidateUrls("ws://host:8662/"));
        assertEquals(List.of("wss://proxy/logs", "wss://proxy/logs/logspout/"),
            RemoteLogConnection.candidateUrls("wss://proxy/logs"));
        assertEquals(List.of("ws://host:8662/logspout/"), RemoteLogConnection.candidateUrls("ws://host:8662/logspout/"));
    }
}
