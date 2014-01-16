#!/bin/bash

# Init
source `pwd`/bin/setup.sh

# Run client demo
$JAVA_HOME/bin/java \
  -Xbootclasspath/p:`pwd`/libs/$NPN_JAR \
  -cp `pwd`/build/libs/spdy.jar \
  com.example.spdy.ClientDemo
