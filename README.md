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

Every leaf source declares a `_type`. The parser recognizes three:

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
