# Determine Java version
JAVA_VERSION=`$JAVA_HOME/bin/java -version 2>&1 | grep "java version" | awk '{ print $3 }' | sed -e 's/"//g'`
if [ `echo $JAVA_VERSION | sed -e 's/_.*//'` != "1.7.0" ]; then
  echo "Java 1.7.0 required"
  exit 1
fi

# Determine build
JAVA_BUILD=`echo $JAVA_VERSION | sed -e 's/1.7.0_//'`

# Determine correct NPN JAR
NPN_JAR=""
if [ $JAVA_BUILD -eq 9 ]; then
  NPN_JAR="npn-boot-1.1.3.v20130313.jar"
elif [ $JAVA_BUILD -eq 10 ]; then
  NPN_JAR="npn-boot-1.1.3.v20130313.jar"
elif [ $JAVA_BUILD -eq 11 ]; then
  NPN_JAR="npn-boot-1.1.3.v20130313.jar"
elif [ $JAVA_BUILD -eq 13 ]; then
  NPN_JAR="npn-boot-1.1.4.v20130313.jar"
elif [ $JAVA_BUILD -eq 15 ]; then
  NPN_JAR="npn-boot-1.1.5.v20130313.jar"
elif [ $JAVA_BUILD -eq 17 ]; then
  NPN_JAR="npn-boot-1.1.5.v20130313.jar"
elif [ $JAVA_BUILD -eq 21 ]; then
  NPN_JAR="npn-boot-1.1.5.v20130313.jar"
elif [ $JAVA_BUILD -eq 25 ]; then
  NPN_JAR="npn-boot-1.1.5.v20130313.jar"
elif [ $JAVA_BUILD -eq 40 ]; then
  NPN_JAR="npn-boot-1.1.6.v20130911.jar"
elif [ $JAVA_BUILD -eq 45 ]; then
  NPN_JAR="npn-boot-1.1.6.v20130911.jar"
else
  echo "Unsupported Java version $JAVA_VERSION"
  exit 2
fi

# Dump environment vars
echo "JAVA_HOME=$JAVA_HOME"
echo "JAVA_VERSION=$JAVA_VERSION"
echo "NPN_JAR=$NPN_JAR"
