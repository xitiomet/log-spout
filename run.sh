#!/bin/bash
mvn compile exec:java -Dexec.mainClass="org.openstatic.LogSpoutMain" -Dexec.args="$*"
