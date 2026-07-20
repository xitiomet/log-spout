#!/bin/bash
echo -n "Uploading files to openstatic.org..."
{
    scp target/log-spout-*.jar openstatic.org:openstatic.org/projects/logspout/
    scp target/log-spout-*.deb openstatic.org:openstatic.org/projects/logspout/
    scp README.md openstatic.org:openstatic.org/projects/logspout/
} &> /dev/null
echo " Done."
