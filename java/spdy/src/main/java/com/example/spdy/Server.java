package com.example.spdy;

import com.example.spdy.server.ServerPipelineFactory;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class Server
{
  private static final Logger LOG = Logger.getLogger(Server.class);
  private static final int DEFAULT_PORT = 9000;

  public static void main(String[] args)
  {
    Utils.configureConsole();

    // Init

    String customPort = System.getProperty("port");
    int port = customPort == null ? DEFAULT_PORT : Integer.parseInt(customPort);

    // Configure and start server

    final ServerBootstrap bootstrap = new ServerBootstrap(
            new NioServerSocketChannelFactory(Executors.newCachedThreadPool(),
                                              Executors.newCachedThreadPool()));

    bootstrap.setPipelineFactory(new ServerPipelineFactory());

    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable()
    {
      @Override
      public void run()
      {
        bootstrap.releaseExternalResources();
        LOG.info("Shutdown server complete");
      }
    }));

    bootstrap.bind(new InetSocketAddress(port));

    LOG.info("Listening on " + port);
  }
}
