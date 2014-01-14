package com.example.spdy.client;

import com.example.spdy.npn.SimpleClientProvider;
import org.apache.log4j.Logger;
import org.eclipse.jetty.npn.NextProtoNego;
import org.jboss.netty.channel.*;
import org.jboss.netty.handler.codec.http.HttpClientCodec;
import org.jboss.netty.handler.codec.spdy.SpdyFrameCodec;
import org.jboss.netty.handler.codec.spdy.SpdyHttpCodec;
import org.jboss.netty.handler.codec.spdy.SpdySessionHandler;
import org.jboss.netty.handler.codec.spdy.SpdyVersion;
import org.jboss.netty.handler.ssl.SslHandler;

public class SecureClientProtocolSelectionHandler extends SimpleChannelUpstreamHandler
{
  private static final Logger LOG = Logger.getLogger(SecureClientProtocolSelectionHandler.class);

  @Override
  public void handleUpstream(ChannelHandlerContext ctx, ChannelEvent e) throws Exception
  {
    SslHandler handler = ctx.getPipeline().get(SslHandler.class);
    SimpleClientProvider provider = (SimpleClientProvider) NextProtoNego.get(handler.getEngine());

    if ("spdy/3".equals(provider.getSelectedProtocol()))
    {
      LOG.info("Negotiated spdy/3");

      ChannelPipeline pipeline = ctx.getPipeline();
      pipeline.addLast("spdyFrameCodec", new SpdyFrameCodec(SpdyVersion.SPDY_3));
      pipeline.addLast("spdySessionHandler", new SpdySessionHandler(SpdyVersion.SPDY_3, false));
      pipeline.addLast("spdyHttpCodec", new SpdyHttpCodec(SpdyVersion.SPDY_3, 1024 * 1024));
      pipeline.addLast("responseLoggingHandler", new ResponseLoggingHandler());
      pipeline.remove(this);
      ctx.sendUpstream(e);
    }
    else if ("http/1.1".equals(provider.getSelectedProtocol()))
    {
      LOG.info("Negotiated http/1.1");

      ChannelPipeline pipeline = ctx.getPipeline();
      pipeline.addLast("httpCodec", new HttpClientCodec());
      pipeline.addLast("responseLoggingHandler", new ResponseLoggingHandler());
      pipeline.remove(this);
      ctx.sendUpstream(e);
    }
    else
    {
      LOG.info("Negotiating...");
    }
  }
}
