#!/usr/bin/env sh
APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
DEFAULT_JVM_OPTS=""
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
exec "$JAVACMD" "${JVM_OPTS[@]}" $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"

##############################################################################
# Actual gradlew (auto-generated stub — GitHub Actions uses its own JVM)
##############################################################################

#!/usr/bin/env sh
set -e
GRADLE_OPTS="${GRADLE_OPTS:-""}"
exec gradle "$@"
