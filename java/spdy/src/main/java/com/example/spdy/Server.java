package com.example.spdy;

import static com.example.spdy.Constants.DEFAULT_SERVER_PORT;
import static com.example.spdy.Constants.PROP_PORT;

import com.example.spdy.server.ServerPipelineFactory;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * A server that supports HTTP, HTTPS, and SPDY
 *
 * @author Greg Brandt (brandt.greg@gmail.com)
 */
public class Server
{
  private static final Logger LOG = Logger.getLogger(Server.class);

  public static void main(String[] args)
  {
    // Logger
    ConsoleAppender console = new ConsoleAppender();
    PatternLayout layout = new PatternLayout("%d{yyyy-MM-dd HH:mm:ss} %-5p %c{1}:%L - %m%n");
    console.setLayout(layout);
    console.activateOptions();
    Logger.getRootLogger().addAppender(console);

    // Port
    String customPort = System.getProperty(PROP_PORT);
    int port = customPort == null ? DEFAULT_SERVER_PORT : Integer.parseInt(customPort);

    // Configure server
    final ServerBootstrap bootstrap = new ServerBootstrap(
            new NioServerSocketChannelFactory(Executors.newCachedThreadPool(),
                                              Executors.newCachedThreadPool()));
    bootstrap.setPipelineFactory(new ServerPipelineFactory());

    // Release server resources on shutdown
    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable()
    {
      @Override
      public void run()
      {
        bootstrap.releaseExternalResources();
        LOG.info("Shutdown server complete");
      }
    }));

    // Start server
    bootstrap.bind(new InetSocketAddress(port));
    LOG.info("Listening on " + port);
  }
}
