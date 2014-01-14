package com.example.spdy;

import com.example.spdy.client.NaiveTrustManager;
import com.example.spdy.client.SimpleClientProvider;
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

        pipeline.addLast("ssl", new SslHandler(engine));
        pipeline.addLast("spdyFrameCodec", new SpdyFrameCodec(SpdyVersion.SPDY_3));
        pipeline.addLast("spdySessionHandler", new SpdySessionHandler(SpdyVersion.SPDY_3, false));
        pipeline.addLast("spdyHttpCodec", new SpdyHttpCodec(SpdyVersion.SPDY_3, 1024 * 1024));
        pipeline.addLast("responseHandler", new SimpleChannelUpstreamHandler() {
          @Override
          public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception
          {
            SslHandler sslHandler = ctx.getPipeline().get(SslHandler.class);
            sslHandler.handshake().addListener(new ChannelFutureListener()
            {
              @Override
              public void operationComplete(ChannelFuture future) throws Exception
              {
                if (future.isSuccess())
                {
                  SslHandler h = future.getChannel().getPipeline().get(SslHandler.class);
                  SimpleClientProvider provider = (SimpleClientProvider) NextProtoNego.get(h.getEngine());

                  LOG.info("Handshake done. Negotiated protocol " + provider.getSelectedProtocol());

                  if (provider.getSelectedProtocol() == null)
                  {
                    provider.unsupported();
                  }
                }
                else
                {
                  LOG.error("Failed to handshake");
                }
              }
            });
          }

          @Override
          public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception
          {
            HttpResponse httpResponse = (HttpResponse) e.getMessage();
            LOG.info(httpResponse.getStatus());

            byte[] content = new byte[httpResponse.getContent().readableBytes()];
            httpResponse.getContent().readBytes(content);
            httpResponse.getContent().resetReaderIndex();
            LOG.info(new String(content));

            finished.countDown();
          }

          @Override
          public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception
          {
            LOG.error(e);
            finished.countDown();
          }
        });

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
          HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
          HttpHeaders.setHeader(httpRequest, "X-SPDY-Stream-ID", 1);
          HttpHeaders.setHeader(httpRequest, HttpHeaders.Names.HOST, "localhost");
          future.getChannel().write(httpRequest);
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
