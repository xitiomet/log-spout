## Log Spout

A program for tapping into several log streams from different sources at once.
Log Spout reads a JSON config that describes a tree of log sources, streams the
lines they produce, and optionally exposes them through a small web UI /
WebSocket API so other instances (or browsers) can subscribe to them.

- Load config: `log-spout -f my-config.json`
- Turn on the web/API on port 8662: `log-spout -f my-config.json -a`
- Pipe combined output to stdout: `log-spout -f my-config.json -s`
- Connect to another Log Spout server as a remote client: `log-spout -c ws://host:8662 -p secret -s`

Run `log-spout -?` for the full option list.

### Generate a config from a directory

`-d`, or `--directory`, recursively discovers regular files ending in `.log`,
`.out`, or `.err`, and files named exactly `syslog`, `dmesg`, or `messages`.
Matching is case-sensitive; rotated/compressed files such as `syslog.1` and
`access.log.gz` are excluded. Directory symlinks are not traversed.
Each candidate's first 8 KiB is sampled: NUL bytes or more than 10% non-text
control bytes mark it as likely binary and exclude it with
`Skipping binary log: <absolute path>` on stderr. Tabs, CR/LF, ANSI escape
bytes, and non-ASCII bytes are allowed; empty files remain eligible.
This is a heuristic, not encoding validation: UTF-16 files containing NULs
are excluded, and binary content beyond the sample may go undetected.
The generated root `_name` is the directory path exactly as supplied
(including a trailing slash), except bare `-d` or `-d .` uses the full absolute
current-directory path. Each source uses `_type: "tail"` with an
absolute `_file` path and a root-relative `_name` using `/` separators on all
platforms (for example, `dmesg` or `nginx/access.log`). Subdirectories containing
accepted logs are nested containers with root-relative names ending in `/`
(for example, `nginx/` or `nginx/archive/`) and their logs and child folders
in `_sources`. Ancestors needed to reach logs
are retained; branches with no accepted logs are omitted. Logs directly in the
scanned directory remain in the root `_sources`. Sources are sorted by
path; an empty directory produces an empty `_sources` array. Paths reporting
filesystem errors (including access denial and Windows sharing locks) are
skipped with `Skipping inaccessible path: <path>` on stderr, allowing the
scan to continue, including files that cannot be opened for sampling. Each
accepted file emits `Found Log: <absolute path>` on stderr, keeping stdout
reserved for the generated JSON. Invalid directories and other I/O failures
report an error instead of starting Log Spout.

```sh
# Print JSON to stdout and exit (does not start the server or stream logs).
log-spout -d /var/log/

# Save the generated config, replacing it if it exists, and start normally.
log-spout -d /var/log/ -f system-logs.json -a

# Save the config and generate an executable launch script instead of starting.
log-spout -d /var/log/ -f system-logs.json -g system-logs.sh -a

# Save .log-spout.json in the current directory and generate a launch script.
log-spout -d /var/log/ -g system-logs.sh

# Scan the current directory (directory argument is optional).
log-spout -d

# Generate a launch script that creates a fresh temporary config at each launch.
log-spout -d /var/log/ -f _TEMP_FILE_ -g system-logs.sh

# Rescan, write a temporary config, and start without saving a permanent config.
log-spout -d /var/log/ -f _TEMP_FILE_ -a

# Shortcuts: -s or -a without -f automatically uses a temporary config.
log-spout -d -s
log-spout -d /var/log/ -a
```

When `-d` is used without `-f` or `-g`, either `-s` or `-a` creates a temporary
config and starts normally instead of printing JSON. Existing default configs
are left untouched. Without either output option, the generated JSON is printed.
`-g` takes priority over this shortcut and retains the rules below.

With `-g -d` and no `-f`, the config is saved as `.log-spout.json` in the
current working directory, and the script loads its absolute path without
rescanning. If that config exists, a warning and `[y/N]` confirmation appear
on stderr before overwriting. Only `y` or `yes` confirms; no or end-of-input
cancels without changing the config or generating the script.
Bare `-d` defaults to `.` (the current directory), including when used with `-g`.

With `-g -d -f _TEMP_FILE_`, the generated command retains
`-d` and uses `-f _TEMP_FILE_`. Each launch rescans the directory and creates
a fresh random config such as `/tmp/log-spout-152451.json`, so clearing the
temp directory between launches does not break the script. The temporary
config is removed on normal process shutdown. `_TEMP_FILE_` requires `-d`.
With an explicit config filename, scripts still load the saved config without
rescanning. As with other `-g` commands, `-s` is added when neither `-s` nor
`-a` was supplied. Other CLI overrides, such as `-e`, are applied to the
generated config before it is printed or saved.

Generated launch scripts target Bash on POSIX systems and CMD on Windows.
On Windows, `-g local.sh` (or `-g local`) creates `local.cmd`, using CRLF
line endings and Windows argument escaping. For example:

```cmd
log-spout -d "C:\logs" -g local.cmd
local.cmd
```

Both platforms test generated-script execution and temporary-config restarts;
POSIX permissions, Bash quoting, and renaming open files are tested only
on systems supporting those operations.

### Windows installer

The NSIS installer installs the x64 native `log-spout.exe`, the native-image
DLLs, and the x64 Visual C++ runtime DLLs into `C:\Program Files\Log Spout`.
It requires administrator privileges, adds the install directory to **system
PATH**, and registers an uninstaller in Windows Installed Apps. It creates
no Start Menu or desktop shortcuts. Open a new terminal after installation
to use `log-spout`. Uninstall removes the PATH entry only if the installer
added it, preserving a pre-existing entry.

The automatically activated Maven `windows` profile compiles
`src/main/resources/log-spout.ico` into a Win32 resource, links it into the
native executable, and builds the NSIS installer after native-image finishes.
Linux Debian packaging is isolated in the auto-activated `linux` profile.
Use GraalVM, NSIS 3, and the Visual Studio x64 Native Tools command prompt:

```powershell
mvn package
```

NSIS defaults to `C:\Program Files (x86)\NSIS\makensis.exe`; override it with
`-Dwindows.makeNSIS=...` if installed elsewhere. `-DskipNative=true` skips
native-image, Windows icon compilation, and installer packaging.
`-DskipWindowsInstaller=true` builds the native executable without packaging.
To rebuild only the installer from an existing native executable:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File src\windows\Build-Installer.ps1 `
  -MakeNSIS "C:\Program Files (x86)\NSIS\makensis.exe"
```

The script finds the x64 CRT directory through `VCToolsRedistDir`. If that
environment variable is unavailable, pass `-VCRuntimeDirectory` with the
Visual Studio redistributable directory containing `vcruntime140.dll`
(for example, `...\VC\Redist\MSVC\<version>\x64\Microsoft.VC143.CRT`).
Only redistribute the release runtime DLLs under their applicable license.
All DLLs emitted alongside the native executable or under `target\natives`
are included; conflicting DLL filenames fail packaging rather than silently
selecting one. The installer is written to `target\log-spout-1.0-setup.exe`;
use `-Version` to change its version label.

The PATH helper preserves long PATH values and their registry value type.
Its isolated tests do not modify system PATH:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File src\windows\Test-SystemPath.ps1
```

---

### Config overview

A config is a single JSON object. Keys that start with `_` are reserved
directives that Log Spout understands; every other key is treated as a
**user-defined variable** that is inherited by child sources and can be
substituted into strings using `$(varName)`.

Top-level keys used by the runtime:

| Key            | Purpose                                                                    |
| -------------- | -------------------------------------------------------------------------- |
| `_name`        | Display name for the root container.                                       |
| `_sources`     | Array of child log-source configs (see [`_sources`](#_sources)).           |
| `_remote`      | If set at the root, connect to a remote Log Spout server (shortcut for a single `remote` source). |
| `_filter`      | JEXL expression applied to every line (also inherited into children).      |
| `_select`      | Restrict output to a single named sub-log (also inherited).                |
| `_highlight`   | `{ "substring": "\u001b[...m" }` — ANSI prefix injected when a line matches. |
| `apiPort`      | Port the web UI / WebSocket API listens on (default `8662`).               |
| `apiPassword`  | Password required by remote clients and the web UI.                        |
| `hostname`     | Identifier advertised to remote clients (auto-detected if omitted).        |

---

### <a id="_sources"></a>`_sources` — composing multiple log sources

`_sources` is how you attach more than one log to a container. It is an array
of nested config objects. Each element is parsed independently and can be:

- A **leaf source** — an object with a `_type` (see below), producing lines.
- A **nested container** — an object that itself has `_sources` (and no
  `_type`), letting you group sources into sub-trees.

Every container merges these things into its children before parsing them:

1. **All non-`_` keys are inherited** into the child if the child hasn't
   already set them. That means a variable defined at the root — for example
   `"host": "app-01"` — is visible to every source underneath and can be
   referenced with `$(host)`.
2. `_filter` and `_select`, if present on the parent, are copied onto each
   child so they apply uniformly across the whole tree.

Containers can be nested to any depth, which lets you group related logs and
filter/name them as a unit. The example further down uses nesting to keep
per-host logs together.

#### Minimal example

```json
{
    "_name": "Local System",
    "apiPassword": "changeme",
    "_sources": [
        {
            "_name": "/var/log/syslog",
            "_type": "process",
            "_execute": ["tail", "-f", "/var/log/syslog"]
        },
        {
            "_name": "/var/log/dmesg",
            "_type": "process",
            "_execute": ["tail", "-f", "/var/log/dmesg"]
        },
        {
            "_name": "/var/log/kern",
            "_type": "process",
            "_execute": ["tail", "-f", "/var/log/kern"]
        }
    ]
}
```

#### Same thing with variables

The `file` key below is not reserved (no leading `_`), so it's inherited by
each child and can be interpolated with `$(file)`:

```json
{
    "_name": "Local System",
    "apiPassword": "changeme",
    "_sources": [
        {
            "file": "/var/log/syslog",
            "_name": "$(file)",
            "_type": "process",
            "_execute": ["tail", "-f", "$(file)"]
        },
        {
            "file": "/var/log/dmesg",
            "_name": "$(file)",
            "_type": "process",
            "_execute": ["tail", "-f", "$(file)"]
        },
        {
            "file": "/var/log/kern",
            "_name": "$(file)",
            "_type": "process",
            "_execute": ["tail", "-f", "$(file)"]
        }
    ]
}
```

---

### `_type` values

Every leaf source declares a `_type`. The parser recognizes four:

#### `tail`

Follows a local file directly in Java, without launching an external `tail`
command. Like `tail -f`, it emits the last 10 lines on connection and then
streams appended lines. Empty files are supported, and if the file is
truncated, reading resumes from the beginning. It follows the open file
descriptor, not a replacement file after rename/rotation (like `tail -f`,
not `tail -F`). The file must exist and be readable when connecting; failures
are reported through the usual disconnect-error callbacks.

| Key           | Purpose |
| ------------- | ------- |
| `_file`        | **Required.** Local file path; supports inherited variables and `$(var)` substitutions. |
| `_name`       | Display name (supports `$(var)`). |
| `_unescape`   | Decode `\xNN` and Java-style escapes (default `true`). |
| `_fixescape`  | Decode `#033[` terminal escapes (default `true`). |
| `_urldecode`  | URL-decode each line (default `false`). |
| `_highlight`  | Same substring-based ANSI highlighting as `process`. |

```json
{
    "_file": "/var/log/apache2/parlor.me.access.log",
    "_name": "$(_file)",
    "_type": "tail",
    "_urldecode": true
}
```

No `_execute` is needed. Lines use the platform's default charset, just like
`process` output, and incomplete lines are held until a line ending arrives.
Reconnecting emits the last 10 lines again.

#### `process`

Runs a subprocess and streams every line it writes to stdout/stderr. This is
the workhorse — anything you can pipe through `tail -f`, `journalctl -f`,
`docker logs -f`, `kubectl logs -f`, `ssh host tail -f ...`, etc. becomes a
log source.

| Key           | Purpose                                                                      |
| ------------- | ---------------------------------------------------------------------------- |
| `_execute`    | **Required.** Array of command + args. `$(var)` substitutions are expanded.  |
| `_name`       | Display name (also supports `$(var)`).                                       |
| `_unescape`   | Decode `\xNN` and Java-style escapes in each line (default `true`).          |
| `_urldecode`  | URL-decode each line (default `false`).                                      |
| `_highlight`  | `{ "substring": "\u001b[...m" }` — inject an ANSI color prefix when a line contains the substring; a reset is appended. |

```json
{
    "_name": "nginx access",
    "_type": "process",
    "_execute": ["tail", "-F", "/var/log/nginx/access.log"],
    "_highlight": {
        " 500 ": "\u001b[31m",
        " 404 ": "\u001b[33m"
    }
}
```

#### `remote`

Connects to another Log Spout instance over its WebSocket API and re-streams
its logs. Useful for aggregating logs from many machines into one place.

| Key                 | Purpose                                                                 |
| ------------------- | ----------------------------------------------------------------------- |
| `_remote`           | **Required.** WebSocket URL of the remote server, e.g. `ws://host:8662`. Log Spout appends `/logspout/` automatically. |
| `_remote_password`  | Sent as `apiPassword` when authenticating with the remote.              |
| `_select`           | Ask the remote to send only this named sub-log.                         |
| `_filter`           | JEXL filter forwarded to the remote so filtering happens upstream.      |
| `_name`             | Display name for this remote (defaults to the URL).                     |

```json
{
    "_type": "remote",
    "_name": "prod-api-01",
    "_remote": "ws://prod-api-01.internal:8662",
    "_remote_password": "changeme"
}
```

Note: setting `_remote` at the **root** of the config is a shortcut — Log
Spout will build a single `RemoteLogConnection` for you without you having to
wrap it in `_sources`. That's the mode used by `log-spout -c ws://host:8662`.

#### `forEachLine`

A **dynamic fan-out** source. It runs a command once, and for every line the
command produces it stamps out a copy of a template source, substituting
`$(line)` (and any inherited variables) into that template. Great for things
that come and go — Docker containers, Kubernetes pods, systemd units, remote
hosts read from a file, etc.

| Key         | Purpose                                                                          |
| ----------- | -------------------------------------------------------------------------------- |
| `_execute`  | **Required.** Command whose stdout lines drive the fan-out. Runs once per (re)connect. |
| `_ignore`   | Array of literal line values to skip.                                            |
| `_source`   | **Required.** Template source object; parsed once per output line with `line` set to the current value. |
| `_name`     | Display name for the container that holds the generated children.                |

If any generated child disconnects unexpectedly, the container waits ~10s and
re-runs `_execute` to rebuild the fan-out — so newly-appeared containers /
pods / hosts get picked up automatically.

```json
{
    "_type": "forEachLine",
    "_name": "docker containers",
    "_execute": ["docker", "ps", "--format", "{{.Names}}"],
    "_ignore": ["log-spout"],
    "_source": {
        "_type": "process",
        "_name": "docker: $(line)",
        "_execute": ["docker", "logs", "-f", "--tail", "0", "$(line)"]
    }
}
```

#### Container (no `_type`)

An object that has `_sources` but no `_type` is treated as a plain container.
Use it to group related sources, apply a shared `_filter` / `_highlight`,
attach a `_name`, and nest arbitrarily deep.

```json
{
    "_name": "webservers",
    "_filter": "\"ERROR\" || \"WARN\"",
    "_sources": [
        { "_type": "process", "_name": "web1", "_execute": ["ssh", "web1", "tail", "-F", "/var/log/nginx/error.log"] },
        { "_type": "process", "_name": "web2", "_execute": ["ssh", "web2", "tail", "-F", "/var/log/nginx/error.log"] }
    ]
}
```

---

### `_filter` expressions

`_filter` is a JEXL boolean expression where **quoted strings are treated as
`line.contains(...)` tests**. Combine them with `&&`, `||`, `!`, and
parentheses:

```
"ERROR" && !"heartbeat"
("timeout" || "refused") && "db-"
```

You can also pass a filter on the command line with `-e`, which is equivalent
to setting `_filter` on the root config, and it is inherited by every source
in the tree.

---

### Putting it all together

```json
{
    "_name": "prod",
    "apiPassword": "changeme",
    "_filter": "!\"kube-probe\"",
    "_sources": [
        {
            "_name": "app hosts",
            "_sources": [
                {
                    "host": "app-01",
                    "_type": "process",
                    "_name": "$(host) syslog",
                    "_execute": ["ssh", "$(host)", "tail", "-F", "/var/log/syslog"]
                },
                {
                    "host": "app-02",
                    "_type": "process",
                    "_name": "$(host) syslog",
                    "_execute": ["ssh", "$(host)", "tail", "-F", "/var/log/syslog"]
                }
            ]
        },
        {
            "_type": "forEachLine",
            "_name": "k8s pods (default ns)",
            "_execute": ["kubectl", "get", "pods", "-n", "default", "-o", "name"],
            "_source": {
                "_type": "process",
                "_name": "$(line)",
                "_execute": ["kubectl", "logs", "-f", "--tail", "0", "-n", "default", "$(line)"]
            }
        },
        {
            "_type": "remote",
            "_name": "edge-01",
            "_remote": "ws://edge-01.internal:8662",
            "_remote_password": "changeme"
        }
    ]
}
```

Launch with `log-spout -f prod.json -a` and browse to `http://<host>:8662/`
(password: `changeme`) to view / filter the combined stream, or connect
another Log Spout to it with `log-spout -c ws://<host>:8662 -p changeme -s`.
