package com.example.spdy.client;

import com.example.spdy.npn.SimpleClientProvider;
import org.apache.log4j.Logger;
import org.eclipse.jetty.npn.NextProtoNego;
import org.jboss.netty.channel.*;
import org.jboss.netty.handler.codec.http.*;
import org.jboss.netty.handler.ssl.SslHandler;

public class HandshakeHandler extends SimpleChannelUpstreamHandler
{
  private static final Logger LOG = Logger.getLogger(HandshakeHandler.class);

  @Override
  public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception
  {
    final SslHandler sslHandler = ctx.getPipeline().get(SslHandler.class);
    sslHandler.handshake().addListener(new ChannelFutureListener()
    {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception
      {
        if (future.isSuccess())
        {
          SimpleClientProvider provider = (SimpleClientProvider) NextProtoNego.get(sslHandler.getEngine());
          if (provider.getSelectedProtocol() == null)
          {
            provider.unsupported();
          }
          LOG.info("Handshake done, negotiated protocol " + provider.getSelectedProtocol());

          // Write request
          HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
          HttpHeaders.setHeader(httpRequest, "X-SPDY-Stream-ID", 1);
          HttpHeaders.setHeader(httpRequest, HttpHeaders.Names.HOST, "localhost");
          future.getChannel().write(httpRequest);
        }
        else
        {
          LOG.error("Handshake failed");
        }
      }
    });
  }
}
