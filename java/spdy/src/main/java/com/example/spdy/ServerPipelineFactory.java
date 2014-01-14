package com.example.spdy;

import org.eclipse.jetty.npn.NextProtoNego;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.ssl.SslHandler;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.security.KeyStore;

public class ServerPipelineFactory implements ChannelPipelineFactory
{
  private final SSLContext _context;

  public ServerPipelineFactory()
  {
    try
    {
      KeyStore keyStore = KeyStore.getInstance("JKS");
      keyStore.load(ClassLoader.getSystemResourceAsStream("server_keystore.jks"), "secret".toCharArray());
      KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
      keyManagerFactory.init(keyStore, "secret".toCharArray());
      _context = SSLContext.getInstance("TLS");
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

    SSLEngine engine = _context.createSSLEngine();
    engine.setUseClientMode(false);

    NextProtoNego.put(engine, new SimpleServerProvider());
    NextProtoNego.debug = true;

    pipeline.addLast("sslHandler", new SslHandler(engine));
    pipeline.addLast("protocolSelectionHandler", new ProtocolSelectionHandler());

    return pipeline;
  }
}
