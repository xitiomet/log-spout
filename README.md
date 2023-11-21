## Log Spout

A program for tapping into several log streams from different sources.


### Example
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
This will connect to each log using tail. Variables can be anything you want, except for ones that conflict with the underscore prepended keywords.
Variables are also passed down to each level of a log container, so if you define "foo":"bar" at the root object you can use "$(foo)" in any part of
the sub config.

The above example could be rewritten as:

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
            "_execute": ["tail", "-f", "$(file)""]
        }
    ]
}
```