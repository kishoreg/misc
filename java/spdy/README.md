# SPDY in Netty

To build and run (e.g. using JDK 1.7.0_45):

```
$GRADLE_HOME/bin/gradle clean build fatJar

# Run the server
$JAVA_HOME/bin/java \
  -Xbootclasspath/p:`pwd`/libs/npn-boot-1.1.6.v20130911.jar \
  -cp build/libs/spdy.jar \
  com.example.spdy.Server

# Run a client demo (make sure server running)
$JAVA_HOME/bin/java \
  -Xbootclasspath/p:`pwd`/libs/npn-boot-1.1.6.v20130911.jar \
  -cp build/libs/spdy.jar \
  com.example.spdy.ClientDemo
```

The NPN JAR added to the boot classpath has to be a specific version for the
JDK version you're using:

```
NPN version     | OpenJDK version                           
-------------   | ---------------                           
1.0.0.v20120402 | 1.7.0 - 1.7.0u2 - 1.7.0u3                 
1.1.0.v20120525 | 1.7.0u4 - 1.7.0u5                         
1.1.1.v20121030 | 1.7.0u6 - 1.7.0u7                         
1.1.3.v20130313 | 1.7.0u9 - 1.7.0u10 - 1.7.0u11             
1.1.4.v20130313 | 1.7.0u13                                  
1.1.5.v20130313 | 1.7.0u15 - 1.7.0u17 - 1.7.0u21 - 1.7.0u25 
1.1.6.v20130911 | 1.7.0u40 - 1.7.0u45                       
```

Table copied from [Jetty docs](http://www.eclipse.org/jetty/documentation/current/npn-chapter.html#npn-build)

Example based on [jos.dirksen's article](http://www.smartjava.org/content/using-spdy-and-http-transparently-using-netty)
