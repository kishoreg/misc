package com.example.spdy;

import com.example.spdy.client.ClientPipelineFactory;
import com.example.spdy.utils.NaiveTrustManager;
import com.example.spdy.utils.MiscUtils;
import org.apache.log4j.Logger;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public class Client
{
  private static final Logger LOG = Logger.getLogger(Client.class);

  public static void main(String[] args) throws Exception
  {
    MiscUtils.configureConsole();

    String customPort = System.getProperty("port");
    final int port = customPort == null ? Server.DEFAULT_PORT : Integer.parseInt(customPort);

    final ClientBootstrap bootstrap = new ClientBootstrap(
            new NioClientSocketChannelFactory(
                    Executors.newCachedThreadPool(),
                    Executors.newCachedThreadPool()));

    final SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, new TrustManager[] { NaiveTrustManager.getInstance() }, null);

    bootstrap.setPipelineFactory(new ClientPipelineFactory());

    bootstrap.connect(new InetSocketAddress(port)).addListener(new ChannelFutureListener()
    {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception
      {
        if (future.isSuccess())
        {
          LOG.info("Connected to localhost:" + port);
        }
        else
        {
          LOG.error("Could not connect to localhost:" + port);
        }
      }
    });

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
