#!/bin/sh
# Maven Wrapper launcher. The wrapper JAR is committed after bootstrap.
BASEDIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java -classpath "$BASEDIR/.mvn/wrapper/maven-wrapper.jar" \
  -Dmaven.multiModuleProjectDirectory="$BASEDIR" \
  org.apache.maven.wrapper.MavenWrapperMain "$@"
