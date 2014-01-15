#!/bin/bash

# Ensure gradle is installed
if [ -z "$GRADLE_HOME" ]; then
  echo "GRADLE_HOME is not set! Please install gradle (http://www.gradle.org/) and set GRADLE_HOME to your installation directory."
  exit 1
fi

$GRADLE_HOME/bin/gradle clean build fatJar
