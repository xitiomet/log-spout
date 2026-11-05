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
        List<JSONObject> sources = tailSources(config);
        assertEquals(expected.size(), sources.size());
        for (int i = 0; i < expected.size(); i++)
        {
            JSONObject source = sources.get(i);
            assertEquals("tail", source.getString("_type"));
            assertEquals(expected.get(i).toString(), source.getString("_file"));
            assertEquals(directory.relativize(expected.get(i)).toString().replace(File.separatorChar, '/'),
                source.getString("_name"));
            assertFalse(source.has("_execute"));
        }
    }

    @Test
    public void directoryContainersMirrorTreeAndExcludeEmptyBranches() throws Exception
    {
        Path root = this.temporary.newFolder().toPath();
        Path direct = Files.createFile(root.resolve("root.log"));
        Path service = Files.createDirectory(root.resolve("service"));
        Path serviceLog = Files.createFile(service.resolve("app.log"));
        Path deep = Files.createDirectories(service.resolve("ancestor/deep"));
        Path deepLog = Files.createFile(deep.resolve("app.log"));
        Files.createDirectory(root.resolve("empty"));
        Path invalid = Files.createDirectory(root.resolve("binary-only"));
        Files.write(invalid.resolve("bad.log"), new byte[] {0});
        Files.writeString(invalid.resolve("notes.txt"), "not a log");

        JSONObject config = LogSpoutMain.generateDirectoryConfig(root.toString());
        JSONArray sources = config.getJSONArray("_sources");
        assertEquals(2, sources.length());
        assertEquals(direct.toString(), sources.getJSONObject(0).getString("_file"));
        assertEquals("root.log", sources.getJSONObject(0).getString("_name"));
        JSONObject folder = sources.getJSONObject(1);
        assertEquals("service/", folder.getString("_name"));
        assertFalse(folder.has("_type"));
        assertFalse(folder.has("_file"));
        JSONArray children = folder.getJSONArray("_sources");
        assertEquals(2, children.length());
        JSONObject ancestor = children.getJSONObject(0);
        assertEquals("service/ancestor/", ancestor.getString("_name"));
        JSONObject deepest = ancestor.getJSONArray("_sources").getJSONObject(0);
        assertEquals("service/ancestor/deep/", deepest.getString("_name"));
        assertEquals(deepLog.toString(), deepest.getJSONArray("_sources").getJSONObject(0).getString("_file"));
        assertEquals("service/ancestor/deep/app.log",
            deepest.getJSONArray("_sources").getJSONObject(0).getString("_name"));
        assertEquals(serviceLog.toString(), children.getJSONObject(1).getString("_file"));
        assertEquals("service/app.log", children.getJSONObject(1).getString("_name"));
        assertEquals(3, tailSources(config).size());
        org.openstatic.log.LogConnection connection = org.openstatic.log.LogConnectionParser.parse(config);
        assertTrue(connection instanceof org.openstatic.log.LogConnectionContainer);
        connection.disconnect();
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
                assertEquals("public/", sources.getJSONObject(0).getString("_name"));
                assertEquals(log.toString(), tailSources(config).get(0).getString("_file"));
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
        Process process = runCli("--directory", directory + "/", "-e", "ERROR");
        JSONObject output = new JSONObject(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(directory + "/", output.getString("_name"));
        assertEquals(1, output.getJSONArray("_sources").length());
        assertEquals("ERROR", output.getString("_filter"));
        assertFalse(output.has("apiPort"));
    }

    @Test
    public void reportsFoundLogsOnStderrWithoutContaminatingJson() throws Exception
    {
        Path directory = this.temporary.newFolder().toPath();
        Path nested = Files.createDirectory(directory.resolve("nested"));
        List<Path> logs = new ArrayList<Path>();
        for (String name : Arrays.asList("app.log", "app.out", "app.err", "syslog", "dmesg", "messages"))
            logs.add(Files.createFile(directory.resolve(name)));
        logs.add(Files.createFile(nested.resolve("worker.log")));
        Files.createFile(directory.resolve("ignored.txt"));
        File errors = this.temporary.newFile();
        Process process = startCli(errors, "-d", directory.toString());
        try
        {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            String diagnostics = Files.readString(errors.toPath());
            assertEquals(diagnostics, 0, process.exitValue());
            JSONObject output = new JSONObject(new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8));
            assertEquals(logs.size(), tailSources(output).size());
            List<String> found = diagnostics.lines().filter(line -> line.startsWith("Found Log: ")).toList();
            assertEquals(logs.size(), found.size());
            for (Path log : logs)
                assertTrue(found.contains("Found Log: " + log));
            assertFalse(diagnostics.contains("ignored.txt"));
        } finally {
            stop(process);
        }
    }

    @Test
    public void binaryDetectionHonorsThresholdAndSampleLimit() throws Exception
    {
        Path file = this.temporary.newFile("sample.log").toPath();
        assertFalse(LogSpoutMain.isLikelyBinary(file));
        Files.writeString(file, "\ttext\r\n\u001b[31m\u00e9\u4e16\u754c\u001b[0m", StandardCharsets.UTF_8);
        assertFalse(LogSpoutMain.isLikelyBinary(file));

        byte[] sample = new byte[100];
        Arrays.fill(sample, (byte) 'a');
        sample[99] = 0;
        Files.write(file, sample);
        assertTrue(LogSpoutMain.isLikelyBinary(file));
        Arrays.fill(sample, (byte) 'a');
        Arrays.fill(sample, 0, 10, (byte) 1);
        Files.write(file, sample);
        assertFalse("Exactly 10% is allowed", LogSpoutMain.isLikelyBinary(file));
        sample[10] = 127;
        Files.write(file, sample);
        assertTrue("More than 10% is excluded", LogSpoutMain.isLikelyBinary(file));

        sample = new byte[8193];
        Arrays.fill(sample, (byte) 'a');
        sample[8192] = 0;
        Files.write(file, sample);
        assertFalse("Only the first 8192 bytes are sampled", LogSpoutMain.isLikelyBinary(file));
        sample[8191] = 0;
        Files.write(file, sample);
        assertTrue("The last sampled byte is inspected", LogSpoutMain.isLikelyBinary(file));
    }

    @Test
    public void directoryScanExcludesBinaryCandidatesAndReportsThem() throws Exception
    {
        Path directory = this.temporary.newFolder().toPath();
        Path text = Files.writeString(directory.resolve("text.log"), "readable\n");
        Path empty = Files.createFile(directory.resolve("empty.out"));
        Path unicode = Files.writeString(directory.resolve("unicode.err"), "\u00e9\u4e16\u754c\n");
        List<Path> binary = new ArrayList<Path>();
        for (String name : Arrays.asList("binary.log", "binary.err", "binary.out", "syslog", "dmesg", "messages"))
            binary.add(Files.write(directory.resolve(name), new byte[] {1, 2, 3, 0, 127}));
        File errors = this.temporary.newFile();
        Process process = startCli(errors, "-d", directory.toString());
        try
        {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            String diagnostics = Files.readString(errors.toPath());
            assertEquals(diagnostics, 0, process.exitValue());
            JSONObject output = new JSONObject(new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8));
            JSONArray sources = output.getJSONArray("_sources");
            assertEquals(3, sources.length());
            List<String> included = new ArrayList<String>();
            for (int i = 0; i < sources.length(); i++)
                included.add(sources.getJSONObject(i).getString("_file"));
            for (Path accepted : Arrays.asList(text, empty, unicode))
            {
                assertTrue(included.contains(accepted.toString()));
                assertTrue(diagnostics.contains("Found Log: " + accepted));
            }
            for (Path excluded : binary)
            {
                assertFalse(included.contains(excluded.toString()));
                assertTrue(diagnostics.contains("Skipping binary log: " + excluded));
                assertFalse(diagnostics.contains("Found Log: " + excluded));
            }
        } finally {
            stop(process);
        }
    }

    @Test
    public void skipsLogFilesThatCannotBeOpenedForSampling() throws Exception
    {
        Path directory = this.temporary.newFolder().toPath();
        Assume.assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path denied = Files.writeString(directory.resolve("private.log"), "private\n");
        Path accessible = Files.writeString(directory.resolve("public.log"), "public\n");
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(denied);
        try
        {
            Files.setPosixFilePermissions(denied, Set.of());
            Assume.assumeFalse("Requires a user subject to file permissions", Files.isReadable(denied));
            JSONObject config = LogSpoutMain.generateDirectoryConfig(directory.toString());
            assertEquals(1, config.getJSONArray("_sources").length());
            assertEquals(accessible.toString(), config.getJSONArray("_sources").getJSONObject(0).getString("_file"));
        } finally {
            Files.setPosixFilePermissions(denied, permissions);
        }
    }

    @Test
    public void skipsExclusivelyLockedWindowsLogFiles() throws Exception
    {
        Assume.assumeTrue(isWindows());
        Path directory = this.temporary.newFolder().toPath();
        Path locked = Files.writeString(directory.resolve("locked.log"), "locked\n");
        Path readable = Files.writeString(directory.resolve("readable.log"), "readable\n");
        Path ready = this.temporary.getRoot().toPath().resolve("lock-ready");
        File lockErrors = this.temporary.newFile();
        Process locker = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
            "$ErrorActionPreference='Stop'; $file=[System.IO.File]::Open('"
                + locked.toString().replace("'", "''")
                + "',[System.IO.FileMode]::Open,[System.IO.FileAccess]::Read,[System.IO.FileShare]::None); "
                + "try { [System.IO.File]::WriteAllText('" + ready.toString().replace("'", "''")
                + "','ready'); [Console]::ReadLine() | Out-Null } finally { $file.Dispose() }")
            .redirectError(lockErrors).start();
        try
        {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.exists(ready) && locker.isAlive() && System.nanoTime() < deadline)
                Thread.sleep(20);
            assertTrue("Could not lock file: " + Files.readString(lockErrors.toPath()), Files.exists(ready));
            File errors = this.temporary.newFile();
            Process process = startCli(errors, "-d", directory.toString());
            try
            {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                String diagnostics = Files.readString(errors.toPath());
                assertEquals(diagnostics, 0, process.exitValue());
                JSONObject config = new JSONObject(new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8));
                JSONArray sources = config.getJSONArray("_sources");
                assertEquals(1, sources.length());
                assertEquals(readable.toString(), sources.getJSONObject(0).getString("_file"));
                assertTrue(diagnostics.contains("Skipping inaccessible path: " + locked));
                assertFalse(diagnostics.contains("Found Log: " + locked));
            } finally {
                stop(process);
            }
        } finally {
            locker.getOutputStream().close();
            stop(locker);
        }
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
            while (!normalizeLineEndings(output.toString()).contains("directory-tail-started\n")
                && System.nanoTime() < deadline)
            {
                int available = process.getInputStream().available();
                if (available > 0)
                    output.append(new String(process.getInputStream().readNBytes(available), StandardCharsets.UTF_8));
                if (!process.isAlive())
                    fail("Log Spout exited: " + Files.readString(errors.toPath()));
                Thread.sleep(20);
            }
            assertTrue("No tail output: " + Files.readString(errors.toPath()),
                normalizeLineEndings(output.toString()).contains("directory-tail-started\n"));
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
    public void directoryAndGenerateSaveDefaultConfig() throws Exception
    {
        Path directory = this.temporary.newFolder().toPath();
        Files.createFile(directory.resolve("app.err"));
        Path script = this.temporary.getRoot().toPath().resolve("launch.sh");
        Process process = runCli("--directory=" + directory, "--generate=" + script);
        assertEquals(0, process.getInputStream().readAllBytes().length);
        assertEquals(isWindows() ? "launch.cmd" : "launch.sh",
            LogSpoutMain.launchScriptFile(script.toString()).getName());
        script = LogSpoutMain.launchScriptFile(script.toString()).toPath();
        String contents = Files.readString(script);
        Path config = this.temporary.getRoot().toPath().resolve(".log-spout.json");
        assertTrue(Files.exists(config));
        assertEquals(directory.toString(), new JSONObject(Files.readString(config)).getString("_name"));
        assertEquals(expectedScript("-f", config.toString(), "-g", script.toString()), contents);
        assertFalse(contents.contains(" -d "));
        assertFalse(contents.contains("_TEMP_FILE_"));
        assertFalse(contents.contains("-g "));
        assertTrue(contents.endsWith(isWindows() ? " -s\r\n" : " -s\n"));
        if (!isWindows())
            assertTrue(Files.isExecutable(script));
    }

    @Test
    public void bareDirectoryPrintsCurrentDirectoryConfig() throws Exception
    {
        Files.writeString(this.temporary.getRoot().toPath().resolve("app.log"), "hello\n");
        Process process = runCli("-d");
        JSONObject config = new JSONObject(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(this.temporary.getRoot().toPath().toString(), config.getString("_name"));
        assertEquals(1, config.getJSONArray("_sources").length());
        assertEquals(this.temporary.getRoot().toPath().resolve("app.log").toString(),
            config.getJSONArray("_sources").getJSONObject(0).getString("_file"));
    }

    @Test
    public void explicitDotDirectoryUsesFullPathAsRootName() throws Exception
    {
        Process process = runCli("-d", ".");
        JSONObject config = new JSONObject(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(this.temporary.getRoot().toPath().toString(), config.getString("_name"));
    }

    @Test
    public void bareDirectoryAndGenerateSaveCurrentDirectoryConfig() throws Exception
    {
        Path config = this.temporary.getRoot().toPath().resolve(".log-spout.json");
        Path script = this.temporary.getRoot().toPath().resolve("launch.sh");
        runCli("-d", "-g", script.toString());
        assertEquals(this.temporary.getRoot().toPath().toString(),
            new JSONObject(Files.readString(config)).getString("_name"));
        assertEquals(expectedScript("-f", config.toString(), "-g", script.toString()),
            Files.readString(LogSpoutMain.launchScriptFile(script.toString()).toPath()));
    }

    @Test
    public void defaultConfigOverwriteRequiresExplicitConfirmation() throws Exception
    {
        Path config = this.temporary.getRoot().toPath().resolve(".log-spout.json");
        Path script = this.temporary.getRoot().toPath().resolve("launch.sh");
        Path generatedScript = LogSpoutMain.launchScriptFile(script.toString()).toPath();
        String original = "{\"_name\":\"keep me\"}";
        for (String response : Arrays.asList("n\n", "", "yes\n"))
        {
            Files.writeString(config, original);
            File errors = this.temporary.newFile();
            Process process = startCli(errors, "-d", "-g", script.toString());
            try
            {
                process.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().close();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                assertEquals(Files.readString(errors.toPath()), 0, process.exitValue());
                assertTrue(Files.readString(errors.toPath()).contains("Overwrite? [y/N]"));
                assertEquals(0, process.getInputStream().readAllBytes().length);
                if (response.equals("yes\n"))
                {
                    assertEquals(this.temporary.getRoot().toPath().toString(),
                        new JSONObject(Files.readString(config)).getString("_name"));
                    assertTrue(Files.exists(generatedScript));
                } else {
                    assertEquals(original, Files.readString(config));
                    assertFalse(Files.exists(generatedScript));
                }
            } finally {
                stop(process);
            }
        }
    }

    @Test
    public void bareDirectoryTemporaryScriptUsesExplicitCurrentDirectoryArgument() throws Exception
    {
        String script = expectedScript("-d", "-f", "_TEMP_FILE_", "-g", "launch");
        assertTrue(script.contains(" -d "));
        assertFalse(script.contains(" -d -f"));
        assertTrue(script.contains("_TEMP_FILE_"));
    }

    @Test
    public void directoryOutputOptionsUseTemporaryConfigsUnlessGeneratingScript() throws Exception
    {
        Path directory = this.temporary.getRoot().toPath();
        Files.writeString(directory.resolve("app.log"), "hello\n");
        Path defaultConfig = directory.resolve(".log-spout.json");
        String original = "{\"_name\":\"keep me\"}";
        Files.writeString(defaultConfig, original);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (boolean bare : Arrays.asList(false, true))
        {
            for (String option : Arrays.asList("-s", "-a"))
            {
                Path tempDirectory = this.temporary.newFolder().toPath();
                List<String> command = new ArrayList<String>(Arrays.asList(java,
                    "-Djava.io.tmpdir=" + tempDirectory, "-cp", classpath, LogSpoutMain.class.getName(), "-d"));
                if (!bare)
                    command.add(directory.toString());
                command.add(option);
                if (option.equals("-a"))
                    command.add("0");
                File errors = this.temporary.newFile();
                Process process = new ProcessBuilder(command).directory(this.temporary.getRoot())
                    .redirectError(errors).start();
                try
                {
                    Path config = null;
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (config == null && System.nanoTime() < deadline)
                    {
                        try (java.util.stream.Stream<Path> files = Files.list(tempDirectory))
                        {
                            config = files.filter(path -> path.getFileName().toString().endsWith(".json"))
                                .findFirst().orElse(null);
                        }
                        Thread.sleep(50);
                    }
                    assertNotNull("No temporary config: " + Files.readString(errors.toPath()), config);
                    JSONObject saved = new JSONObject(Files.readString(config));
                    assertEquals(directory.toString(), saved.getString("_name"));
                    assertEquals(1, saved.getJSONArray("_sources").length());
                    assertEquals(option.equals("-a"), saved.has("apiPort"));
                    assertTrue(process.isAlive());
                    assertEquals(original, Files.readString(defaultConfig));
                    assertFalse(Files.readString(errors.toPath()).contains("Overwrite?"));
                } finally {
                    stop(process);
                }
            }
        }
        for (String option : Arrays.asList("-s", "-a"))
        {
            File errors = this.temporary.newFile();
            Path script = directory.resolve("launch.sh");
            Process process = startCli(errors, "-d", "-g", script.toString(), option);
            try
            {
                process.getOutputStream().close();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue());
                assertTrue(Files.readString(errors.toPath()).contains("Overwrite? [y/N]"));
                assertEquals(original, Files.readString(defaultConfig));
                assertFalse(Files.exists(LogSpoutMain.launchScriptFile(script.toString()).toPath()));
            } finally {
                stop(process);
            }
        }
    }

    @Test
    public void explicitTemporaryConfigIsRetainedInGeneratedScript() throws Exception
    {
        Path directory = this.temporary.newFolder().toPath();
        Path script = this.temporary.getRoot().toPath().resolve("launch.sh");
        runCli("-d", directory.toString(), "-f", "_TEMP_FILE_", "-g", script.toString());
        assertEquals(expectedScript("-d", directory.toString(), "-f", "_TEMP_FILE_", "-g", script.toString()),
            Files.readString(LogSpoutMain.launchScriptFile(script.toString()).toPath()));
        assertFalse(Files.exists(this.temporary.getRoot().toPath().resolve("_TEMP_FILE_")));
    }

    @Test
    public void generatedScriptRestartsAfterTemporaryConfigIsDeleted() throws Exception
    {
        if (!isWindows())
            assumePosixFileSystem();
        verifyRestartsAfterTemporaryConfigIsDeleted(true);
    }

    @Test
    public void temporaryConfigRestartsAfterPreviousConfigIsDeleted() throws Exception
    {
        verifyRestartsAfterTemporaryConfigIsDeleted(false);
    }

    private void verifyRestartsAfterTemporaryConfigIsDeleted(boolean runScript) throws Exception
    {
        Path directory = this.temporary.newFolder("logs").toPath();
        Files.writeString(directory.resolve("app.log"), "first-launch\n");
        Path tempDirectory = this.temporary.newFolder("temp configs").toPath();
        Path script = this.temporary.getRoot().toPath().resolve("launch.sh");
        runCli("-d", directory + "/", "-f", "_TEMP_FILE_", "-g", script.toString());
        script = LogSpoutMain.launchScriptFile(script.toString()).toPath();
        Path executable = this.temporary.getRoot().toPath().resolve("log-spout");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        if (runScript && !isWindows())
        {
            Files.writeString(executable, "#!/bin/bash\nexec " + quote(java) + " "
                + quote("-Djava.io.tmpdir=" + tempDirectory) + " -cp " + quote(classpath)
                + " org.openstatic.LogSpoutMain \"$@\"\n");
            assertTrue(executable.toFile().setExecutable(true));
        }
        Path previousConfig = null;
        for (String expectedLine : Arrays.asList("first-launch", "second-launch"))
        {
            if (previousConfig != null)
                Files.writeString(directory.resolve("new.err"), "second-launch\n");
            File errors = this.temporary.newFile();
            ProcessBuilder builder;
            if (runScript)
            {
                if (isWindows())
                {
                    String contents = Files.readString(script);
                    Files.writeString(script, contents.replace("log-spout.exe", "\"" + java + "\" "
                        + "\"-Djava.io.tmpdir=" + tempDirectory + "\" -cp \"" + classpath + "\" "
                        + LogSpoutMain.class.getName()));
                    builder = new ProcessBuilder("cmd.exe", "/d", "/c", script.getFileName().toString());
                } else {
                    builder = new ProcessBuilder("bash", script.toString());
                    builder.environment().put("PATH", this.temporary.getRoot() + File.pathSeparator
                        + builder.environment().get("PATH"));
                }
            } else {
                builder = new ProcessBuilder(java, "-Djava.io.tmpdir=" + tempDirectory, "-cp",
                    classpath, LogSpoutMain.class.getName(), "-d", directory + "/",
                    "-f", "_TEMP_FILE_", "-s");
            }
            builder.directory(this.temporary.getRoot()).redirectError(errors);
            Process process = builder.start();
            Path config;
            try
            {
                StringBuilder output = new StringBuilder();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!normalizeLineEndings(output.toString()).contains(expectedLine + "\n")
                    && System.nanoTime() < deadline)
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
                    normalizeLineEndings(output.toString()).contains(expectedLine + "\n"));
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
        script = LogSpoutMain.launchScriptFile(script.toString()).toPath();
        String contents = Files.readString(script);
        assertEquals(expectedScript("-d", directory.toString(), "-f", config.toString(),
            "-g", script.toString(), "-a", "9876"), contents);
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
        assumePosixFileSystem();
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
    }

    @Test
    public void windowsScriptPreservesArgumentsWhenExecuted() throws Exception
    {
        Assume.assumeTrue(isWindows());
        String[] arguments = {"space here", "(ERROR || WARN) && !DEBUG", "%PATH%", "a^b&c|d<e>f",
            "embedded\"quote", "C:\\logs with spaces\\", "", "apostrophe's", "quote\" & echo unexpected"};
        String[] cli = new String[arguments.length + 2];
        cli[0] = "-g";
        cli[1] = "launch.sh";
        System.arraycopy(arguments, 0, cli, 2, arguments.length);
        String script = expectedScript(cli);
        assertTrue(script.startsWith("@echo off\r\nsetlocal DisableDelayedExpansion\r\n"));
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path launch = this.temporary.getRoot().toPath().resolve("launch.cmd");
        Files.writeString(launch, script.replace("log-spout.exe",
            "\"" + java + "\" -cp \"" + classpath + "\" " + ArgumentPrinter.class.getName()));
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "launch.cmd")
            .directory(this.temporary.getRoot()).redirectErrorStream(true).start();
        try
        {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            assertEquals(output, 0, process.exitValue());
            JSONArray received = new JSONArray(output);
            assertEquals(arguments.length + 1, received.length());
            assertEquals("-s", received.getString(0));
            for (int i = 0; i < arguments.length; i++)
                assertEquals(arguments[i], received.getString(i + 1));
        } finally {
            stop(process);
        }
    }

    public static class ArgumentPrinter
    {
        public static void main(String[] args)
        {
            System.out.println(new JSONArray(Arrays.asList(args)));
        }
    }

    @Test
    public void generatedScriptPreservesArgumentsWhenExecuted() throws Exception
    {
        assumePosixFileSystem();
        CommandLine cmd = new DefaultParser().parse(LogSpoutMain.commandLineOptions(),
            new String[] {"--config=log's config.json", "--generate=launch.sh",
                "--expression", "(ERROR || WARN) && !DEBUG", "--api", "--password", "a $value"});
        String script = LogSpoutMain.generateLaunchScript(cmd);
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

    private List<JSONObject> tailSources(JSONObject config)
    {
        List<JSONObject> sources = new ArrayList<JSONObject>();
        for (Object value : config.getJSONArray("_sources"))
        {
            JSONObject source = (JSONObject) value;
            if (source.has("_sources"))
                sources.addAll(tailSources(source));
            else
                sources.add(source);
        }
        return sources;
    }

    private String expectedScript(String... args) throws Exception
    {
        return LogSpoutMain.generateLaunchScript(new DefaultParser().parse(LogSpoutMain.commandLineOptions(), args));
    }

    private static boolean isWindows()
    {
        return File.separatorChar == '\\';
    }

    private static String normalizeLineEndings(String output)
    {
        return output.replace("\r\n", "\n");
    }

    private void assumePosixFileSystem()
    {
        Assume.assumeTrue("Requires native POSIX paths and executable shell scripts",
            File.separatorChar == '/' && Files.isExecutable(Path.of("/bin/bash")));
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
