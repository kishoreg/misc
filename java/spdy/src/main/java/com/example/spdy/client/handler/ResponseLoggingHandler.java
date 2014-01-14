package com.example.spdy.client.handler;

import com.example.spdy.client.SimpleClientProvider;
import org.apache.log4j.Logger;
import org.eclipse.jetty.npn.NextProtoNego;
import org.jboss.netty.channel.*;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.ssl.SslHandler;

import java.util.concurrent.CountDownLatch;

public class ResponseLoggingHandler extends SimpleChannelUpstreamHandler
{
  private static final Logger LOG = Logger.getLogger(ResponseLoggingHandler.class);

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
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception
  {
    LOG.error(e);
  }
}
