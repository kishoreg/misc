package com.example.spdy.server;

import static com.example.spdy.api.Constants.*;

import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Configures server's SSL stuff and constructs initial state of pipeline.
 *
 * @author Greg Brandt (brandt.greg@gmail.com)
 */
public class ServerPipelineFactory implements ChannelPipelineFactory
{
  private final SSLContext _context;

  public ServerPipelineFactory()
  {
    try
    {
      KeyStore keyStore = KeyStore.getInstance(SERVER_KEYSTORE_TYPE);
      keyStore.load(ClassLoader.getSystemResourceAsStream(SERVER_KEYSTORE_RESOURCE_NAME),
                    SERVER_KEYSTORE_SECRET.toCharArray());
      KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(SSL_ALGORITHM);
      keyManagerFactory.init(keyStore, SERVER_KEYSTORE_SECRET.toCharArray());
      _context = SSLContext.getInstance(SSL_PROTOCOL);
      _context.init(keyManagerFactory.getKeyManagers(), null, null);
    }
    catch (Exception e)
    {
      throw new RuntimeException(e);
    }
  }

  @Override
  public ChannelPipeline getPipeline() throws Exception
  {
    ChannelPipeline pipeline = Channels.pipeline();

    List<ChannelHandler> finalHandlers = new ArrayList<ChannelHandler>();
    finalHandlers.add(new HelloWorldHandler());

    pipeline.addLast("sslSelectionHandler", new InitialProtocolSelectionHandler(_context, finalHandlers));
    return pipeline;
  }
}
