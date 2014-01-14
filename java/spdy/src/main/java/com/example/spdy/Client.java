package com.example.spdy;

import com.example.spdy.client.NaiveTrustManager;
import com.example.spdy.client.SimpleClientProvider;
import com.example.spdy.client.handler.HandshakeHandler;
import com.example.spdy.client.handler.SecureClientProtocolSelectionHandler;
import org.apache.log4j.Logger;
import org.eclipse.jetty.npn.NextProtoNego;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.*;
import org.jboss.netty.handler.codec.spdy.SpdyFrameCodec;
import org.jboss.netty.handler.codec.spdy.SpdyHttpCodec;
import org.jboss.netty.handler.codec.spdy.SpdySessionHandler;
import org.jboss.netty.handler.codec.spdy.SpdyVersion;
import org.jboss.netty.handler.ssl.SslHandler;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

public class Client
{
  private static final Logger LOG = Logger.getLogger(Client.class);

  public static void main(String[] args) throws Exception
  {
    Utils.configureConsole();

    final CountDownLatch finished = new CountDownLatch(1);

    ClientBootstrap bootstrap = new ClientBootstrap(
            new NioClientSocketChannelFactory(
                    Executors.newCachedThreadPool(),
                    Executors.newCachedThreadPool()));

    final SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, new TrustManager[] { NaiveTrustManager.getInstance() }, null);

    bootstrap.setPipelineFactory(new ChannelPipelineFactory()
    {
      @Override
      public ChannelPipeline getPipeline() throws Exception
      {
        ChannelPipeline pipeline = Channels.pipeline();

        SSLEngine engine = sslContext.createSSLEngine();
        engine.setUseClientMode(true);
        NextProtoNego.put(engine, new SimpleClientProvider());
        NextProtoNego.debug = true;

        pipeline.addLast("ssl", new SslHandler(engine));
        pipeline.addLast("handshakeHandler", new HandshakeHandler());
        pipeline.addLast("negotiationHandler", new SecureClientProtocolSelectionHandler());

        return pipeline;
      }
    });

    bootstrap.connect(new InetSocketAddress(9000)).addListener(new ChannelFutureListener()
    {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception
      {
        if (future.isSuccess())
        {
          LOG.info("Connected to localhost:9000");
        }
        else
        {
          LOG.error("Could not connect to localhost:9000");
        }
      }
    });

    finished.await();
    bootstrap.releaseExternalResources();
  }
}
