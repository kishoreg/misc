package com.example.spdy;

import static com.example.spdy.Constants.*;

import com.example.spdy.client.ClientPipelineFactory;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.*;
import org.jboss.netty.handler.ssl.SslHandler;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * A client that connects to a server and negotiates protocol via NPN
 *
 * @author Greg Brandt (brandt.greg@gmail.com)
 */
public class ClientDemo
{
  private static final Logger LOG = Logger.getLogger(ClientDemo.class);

  public static void main(String[] args) throws Exception
  {
    // Logger
    ConsoleAppender console = new ConsoleAppender();
    PatternLayout layout = new PatternLayout("%d{yyyy-MM-dd HH:mm:ss} %-5p %c{1}:%L - %m%n");
    console.setLayout(layout);
    console.activateOptions();
    Logger.getRootLogger().addAppender(console);

    // Port
    String customPort = System.getProperty(PROP_PORT);
    final int port = customPort == null ? DEFAULT_SERVER_PORT : Integer.parseInt(customPort);

    // Configure client
    final ClientBootstrap bootstrap = new ClientBootstrap(
            new NioClientSocketChannelFactory(
                    Executors.newCachedThreadPool(),
                    Executors.newCachedThreadPool()));
    bootstrap.setPipelineFactory(new ClientPipelineFactory());

    // Connect to server
    bootstrap.connect(new InetSocketAddress(port)).addListener(new ChannelFutureListener()
    {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception
      {
        if (future.isSuccess())
        {
          LOG.info("Connected to localhost:" + port);

          // Do handshake
          SslHandler sslHandler = future.getChannel().getPipeline().get(SslHandler.class);
          sslHandler.handshake().addListener(new ChannelFutureListener()
          {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception
            {
              // Write request
              LOG.info("Writing HTTP request");
              HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
              HttpHeaders.setHeader(httpRequest, "X-SPDY-Stream-ID", 1);
              HttpHeaders.setHeader(httpRequest, HttpHeaders.Names.HOST, "localhost");
              future.getChannel().write(httpRequest);
            }
          });
        }
        else
        {
          LOG.error("Could not connect to localhost:" + port);
        }
      }
    });

    // Add shutdown hook to release resources
    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable()
    {
      @Override
      public void run()
      {
        bootstrap.releaseExternalResources();
        LOG.info("Shutdown client");
      }
    }));
  }
}
