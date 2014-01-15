package com.example.spdy.client;

import static com.example.spdy.Constants.SSL_PROTOCOL;

import com.example.spdy.npn.SimpleClientProvider;
import org.eclipse.jetty.npn.NextProtoNego;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.handler.ssl.SslHandler;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;

/**
 * Constructs the initial state of the pipeline, to be augmented after NPN
 *
 * @author Greg Brandt (brandt.greg@gmail.com)
 */
public class ClientPipelineFactory implements ChannelPipelineFactory
{
  private final SSLContext _sslContext;

  public ClientPipelineFactory()
  {
    try
    {
      _sslContext = SSLContext.getInstance(SSL_PROTOCOL);
      _sslContext.init(null, new TrustManager[] { NaiveTrustManager.getInstance() }, null);
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

    // SSL
    SSLEngine engine = _sslContext.createSSLEngine();
    engine.setUseClientMode(true);

    // NPN
    NextProtoNego.put(engine, new SimpleClientProvider());
    NextProtoNego.debug = true;

    // Initial pipeline state (just SSL and NPN)
    pipeline.addLast("ssl", new SslHandler(engine));
    pipeline.addLast("negotiationHandler", new SecureClientProtocolSelectionHandler());

    return pipeline;
  }
}
