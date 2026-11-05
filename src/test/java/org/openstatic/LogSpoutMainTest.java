package org.openstatic;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LogSpoutMainTest
{
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void recursivelyDiscoversLogFilesInSortedOrder() throws Exception
    {
        Path directory = this.temporary.newFolder("logs with spaces").toPath();
        Path nested = Files.createDirectory(directory.resolve("nested"));
        List<Path> expected = new ArrayList<Path>();
        for (String name : Arrays.asList("app.log", "app.out", "app.err", "syslog", "dmesg", "messages"))
            expected.add(Files.createFile(directory.resolve(name)));
        expected.add(Files.createFile(nested.resolve("worker.log")));
        expected.add(Files.createFile(nested.resolve("worker.out")));
        expected.add(Files.createFile(nested.resolve("worker.err")));
        for (String name : Arrays.asList("syslog.1", "app.log.gz", "app.out.1", "app.err.gz", "notes.txt"))
            Files.createFile(directory.resolve(name));
        Files.createDirectory(directory.resolve("not-a-file.log"));
        expected.sort(null);

        String suppliedPath = directory + "/";
        JSONObject config = LogSpoutMain.generateDirectoryConfig(suppliedPath);
        assertEquals(suppliedPath, config.getString("_name"));
        JSONArray sources = config.getJSONArray("_sources");
        assertEquals(expected.size(), sources.length());
        for (int i = 0; i < expected.size(); i++)
        {
            JSONObject source = sources.getJSONObject(i);
            assertEquals("tail", source.getString("_type"));
            assertEquals(expected.get(i).toString(), source.getString("_file"));
            assertEquals(expected.get(i).getFileName().toString(), source.getString("_name"));
            assertFalse(source.has("_execute"));
        }
    }

    @Test
    public void emptyDirectoryProducesEmptySources() throws Exception
    {
        Path directory = this.temporary.newFolder().toPath();
        assertEquals(0, LogSpoutMain.generateDirectoryConfig(directory.toString())
            .getJSONArray("_sources").length());
    }

    @Test
    public void skipsAccessDeniedDirectoriesAndContinuesScanning() throws Exception
    {
        Path directory = this.temporary.newFolder().toPath();
        Assume.assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path privateDirectory = Files.createDirectory(directory.resolve("private"));
        Files.createFile(privateDirectory.resolve("hidden.log"));
        Path accessible = Files.createDirectory(directory.resolve("public"));
        Path log = Files.createFile(accessible.resolve("app.log"));
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(privateDirectory);
        try
        {
            Files.setPosixFilePermissions(privateDirectory, Set.of());
            Assume.assumeFalse("Requires a user subject to directory permissions",
                Files.isReadable(privateDirectory));
            File errors = this.temporary.newFile();
            Process process = startCli(errors, "-d", directory + "/");
            try
            {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                assertEquals(Files.readString(errors.toPath()), 0, process.exitValue());
                JSONObject config = new JSONObject(new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8));
                assertEquals(directory + "/", config.getString("_name"));
                JSONArray sources = config.getJSONArray("_sources");
                assertEquals(1, sources.length());
                assertEquals(log.toString(), sources.getJSONObject(0).getString("_file"));
                assertTrue(Files.readString(errors.toPath())
                    .contains("Skipping inaccessible path: " + privateDirectory));
            } finally {
                stop(process);
            }
        } finally {
            Files.setPosixFilePermissions(privateDirectory, permissions);
        }
    }

    @Test
    public void directoryOnlyPrintsJsonAndExits() throws Exception
    {
        Path directory = this.temporary.newFolder().toPath();
        Files.createFile(directory.resolve("app.log"));
        Process process = runCli("--directory", directory + "/", "-e", "ERROR", "-a");
        JSONObject output = new JSONObject(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(directory + "/", output.getString("_name"));
        assertEquals(1, output.getJSONArray("_sources").length());
        assertEquals("ERROR", output.getString("_filter"));
        assertEquals(8662, output.getInt("apiPort"));
    }

    @Test
    public void invalidDirectoryFailsWithoutOutput() throws Exception
    {
        File errors = this.temporary.newFile();
        Process process = startCli(errors, "-d", this.temporary.getRoot().toPath().resolve("missing").toString());
        try
        {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(1, process.exitValue());
            assertEquals(0, process.getInputStream().readAllBytes().length);
            assertTrue(Files.readString(errors.toPath()).contains("Not a directory:"));
        } finally {
            stop(process);
        }
    }

    @Test
    public void configWriteFailureDoesNotStartTailing() throws Exception
    {
        Path directory = this.temporary.newFolder().toPath();
        Files.writeString(directory.resolve("app.log"), "should-not-be-streamed\n");
        File errors = this.temporary.newFile();
        Path config = this.temporary.getRoot().toPath().resolve("missing-parent/config.json");
        Process process = startCli(errors, "-d", directory.toString(), "-f", config.toString(), "-s");
        try
        {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(1, process.exitValue());
            assertEquals(0, process.getInputStream().readAllBytes().length);
            assertTrue(Files.readString(errors.toPath()).contains(config.toString()));
            assertFalse(Files.exists(config));
        } finally {
            stop(process);
        }
    }

    @Test
    public void savesConfigAndStartsTailingNormally() throws Exception
    {
        Path directory = this.temporary.newFolder().toPath();
        Files.writeString(directory.resolve("app.log"), "directory-tail-started\n");
        Path config = this.temporary.getRoot().toPath().resolve("saved config.json");
        Files.writeString(config, "{\"_name\":\"old config\"}");
        File errors = this.temporary.newFile();
        Process process = startCli(errors, "-d", directory + "/", "-f", config.toString(), "-s");
        try
        {
            StringBuilder output = new StringBuilder();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!output.toString().contains("directory-tail-started\n") && System.nanoTime() < deadline)
            {
                int available = process.getInputStream().available();
                if (available > 0)
                    output.append(new String(process.getInputStream().readNBytes(available), StandardCharsets.UTF_8));
                if (!process.isAlive())
                    fail("Log Spout exited: " + Files.readString(errors.toPath()));
                Thread.sleep(20);
            }
            assertTrue("No tail output: " + Files.readString(errors.toPath()),
                output.toString().contains("directory-tail-started\n"));
            JSONObject saved = new JSONObject(Files.readString(config));
            assertEquals(directory + "/", saved.getString("_name"));
            assertEquals("tail", saved.getJSONArray("_sources").getJSONObject(0).getString("_type"));
            assertEquals("app.log", saved.getJSONArray("_sources").getJSONObject(0).getString("_name"));
            assertTrue(process.isAlive());
        } finally {
            stop(process);
        }
    }

    @Test
    public void directoryAndGenerateRescanUsingTemporaryConfig() throws Exception
    {
        Path directory = this.temporary.newFolder().toPath();
        Files.createFile(directory.resolve("app.err"));
        Path script = this.temporary.getRoot().toPath().resolve("launch.sh");
        Process process = runCli("--directory=" + directory, "--generate=" + script);
        assertEquals(0, process.getInputStream().readAllBytes().length);
        String contents = Files.readString(script);
        assertTrue(contents.contains(" -f _TEMP_FILE_"));
        assertTrue(contents.contains(" -d '" + directory + "'"));
        assertFalse(contents.contains("-g "));
        assertTrue(contents.endsWith(" -s\n"));
        assertTrue(Files.isExecutable(script));
    }

    @Test
    public void explicitTemporaryConfigIsRetainedInGeneratedScript() throws Exception
    {
        Path directory = this.temporary.newFolder().toPath();
        Path script = this.temporary.getRoot().toPath().resolve("launch.sh");
        runCli("-d", directory.toString(), "-f", "_TEMP_FILE_", "-g", script.toString());
        assertEquals("#!/bin/bash\nlog-spout -d '" + directory + "' -f '_TEMP_FILE_' -s\n",
            Files.readString(script));
        assertFalse(Files.exists(this.temporary.getRoot().toPath().resolve("_TEMP_FILE_")));
    }

    @Test
    public void generatedScriptRestartsAfterTemporaryConfigIsDeleted() throws Exception
    {
        Path directory = this.temporary.newFolder("logs").toPath();
        Files.writeString(directory.resolve("app.log"), "first-launch\n");
        Path tempDirectory = this.temporary.newFolder("temp configs").toPath();
        Path script = this.temporary.getRoot().toPath().resolve("launch.sh");
        runCli("-d", directory + "/", "-g", script.toString());
        Path executable = this.temporary.getRoot().toPath().resolve("log-spout");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Files.writeString(executable, "#!/bin/bash\nexec " + quote(java) + " "
            + quote("-Djava.io.tmpdir=" + tempDirectory) + " -cp " + quote(classpath)
            + " org.openstatic.LogSpoutMain \"$@\"\n");
        assertTrue(executable.toFile().setExecutable(true));
        Path previousConfig = null;
        for (String expectedLine : Arrays.asList("first-launch", "second-launch"))
        {
            if (previousConfig != null)
                Files.writeString(directory.resolve("new.err"), "second-launch\n");
            File errors = this.temporary.newFile();
            ProcessBuilder builder = new ProcessBuilder("bash", script.toString())
                .directory(this.temporary.getRoot()).redirectError(errors);
            builder.environment().put("PATH", this.temporary.getRoot() + File.pathSeparator
                + builder.environment().get("PATH"));
            Process process = builder.start();
            Path config;
            try
            {
                StringBuilder output = new StringBuilder();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!output.toString().contains(expectedLine + "\n") && System.nanoTime() < deadline)
                {
                    int available = process.getInputStream().available();
                    if (available > 0)
                        output.append(new String(process.getInputStream().readNBytes(available),
                            StandardCharsets.UTF_8));
                    if (!process.isAlive())
                        fail("Script exited: " + Files.readString(errors.toPath()));
                    Thread.sleep(20);
                }
                assertTrue("Missing tail output: " + Files.readString(errors.toPath()),
                    output.toString().contains(expectedLine + "\n"));
                try (java.util.stream.Stream<Path> configs = Files.list(tempDirectory))
                {
                    List<Path> paths = configs.toList();
                    assertEquals(1, paths.size());
                    config = paths.get(0);
                }
                assertTrue(config.getFileName().toString().matches("log-spout-[0-9]+\\.json"));
                assertNotEquals(previousConfig, config);
                JSONObject saved = new JSONObject(Files.readString(config));
                assertEquals(directory + "/", saved.getString("_name"));
                assertEquals(previousConfig == null ? 1 : 2, saved.getJSONArray("_sources").length());
                Files.delete(config);
                previousConfig = config;
            } finally {
                stop(process);
            }
        }
        assertFalse(Files.exists(this.temporary.getRoot().toPath().resolve("_TEMP_FILE_")));
    }

    @Test
    public void temporaryConfigWithoutDirectoryFails() throws Exception
    {
        File errors = this.temporary.newFile();
        Process process = startCli(errors, "-f", "_TEMP_FILE_");
        try
        {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(1, process.exitValue());
            assertTrue(Files.readString(errors.toPath()).contains("_TEMP_FILE_ requires -d"));
        } finally {
            stop(process);
        }
    }

    @Test
    public void directoryAndGenerateHonorExplicitConfigAndOptions() throws Exception
    {
        Path directory = this.temporary.newFolder("logs").toPath();
        Files.createFile(directory.resolve("app.out"));
        Path config = this.temporary.getRoot().toPath().resolve("saved config.json");
        Path script = this.temporary.getRoot().toPath().resolve("launch.sh");
        runCli("-d", directory.toString(), "-f", config.toString(), "-g", script.toString(), "-a", "9876");
        String contents = Files.readString(script);
        assertTrue(contents.contains(" -f '" + config + "'"));
        assertTrue(contents.contains(" -a '9876'"));
        assertFalse(contents.contains(" -s"));
        assertFalse(contents.contains(" -d"));
        assertFalse(contents.contains(" -g"));
        JSONObject saved = new JSONObject(Files.readString(config));
        assertEquals(9876, saved.getInt("apiPort"));
        assertEquals(1, saved.getJSONArray("_sources").length());
    }

    @Test
    public void existingScriptOptionsArePreservedAndShellQuoted() throws Exception
    {
        CommandLine cmd = new DefaultParser().parse(LogSpoutMain.commandLineOptions(),
            new String[] {"--config=log's config.json", "--generate=launch.sh",
                "--expression", "(ERROR || WARN) && !DEBUG", "--api", "--password", "a $value"});
        String script = LogSpoutMain.generateLaunchScript(cmd);
        assertTrue(script.contains(" -f 'log'\"'\"'s config.json'"));
        assertTrue(script.contains(" -e '(ERROR || WARN) && !DEBUG'"));
        assertTrue(script.contains(" -a"));
        assertTrue(script.contains(" -p 'a $value'"));
        assertFalse(script.contains(" -s"));
        assertFalse(script.contains(" -g"));

        Path executable = this.temporary.getRoot().toPath().resolve("log-spout");
        Files.writeString(executable, "#!/bin/bash\nprintf '%s\\n' \"$@\"\n");
        assertTrue(executable.toFile().setExecutable(true));
        Path launch = this.temporary.getRoot().toPath().resolve("launch.sh");
        Files.writeString(launch, script);
        ProcessBuilder builder = new ProcessBuilder("bash", launch.toString());
        builder.environment().put("PATH", this.temporary.getRoot() + File.pathSeparator
            + builder.environment().get("PATH"));
        Process process = builder.start();
        try
        {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertEquals("-f\nlog's config.json\n-e\n(ERROR || WARN) && !DEBUG\n-a\n-p\na $value\n",
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            stop(process);
        }
    }

    private Process runCli(String... args) throws Exception
    {
        File errors = this.temporary.newFile();
        Process process = startCli(errors, args);
        try
        {
            assertTrue("CLI timed out", process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(Files.readString(errors.toPath()), 0, process.exitValue());
            return process;
        } finally {
            stop(process);
        }
    }

    private String quote(String value)
    {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private Process startCli(File errors, String... args) throws Exception
    {
        List<String> command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(LogSpoutMain.class.getName());
        command.addAll(Arrays.asList(args));
        return new ProcessBuilder(command).directory(this.temporary.getRoot()).redirectError(errors).start();
    }

    private void stop(Process process) throws Exception
    {
        if (process.isAlive())
        {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS))
            {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }
}
