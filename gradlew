#!/bin/sh

APP_HOME=$(cd "${0%/*}" && pwd -P)
APP_NAME="Gradle"
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi

if [ ! -x "$JAVACMD" ]; then
    echo "ERROR: JAVA_HOME points to an invalid Java installation: $JAVA_HOME" >&2
    exit 1
fi

exec "$JAVACMD" \
    "-Xmx64m" "-Xms64m" \
    -Dorg.gradle.appname="$APP_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
