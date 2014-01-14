package com.example.spdy.server.handler;

import com.example.spdy.client.SimpleClientProvider;
import com.example.spdy.server.SimpleServerProvider;
import org.apache.log4j.Logger;
import org.eclipse.jetty.npn.NextProtoNego;
import org.jboss.netty.channel.*;
import org.jboss.netty.handler.codec.http.HttpChunkAggregator;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.codec.spdy.*;
import org.jboss.netty.handler.ssl.SslHandler;

public class SecureServerProtocolSelectionHandler extends SimpleChannelUpstreamHandler
{
  private static final Logger LOG = Logger.getLogger(SecureServerProtocolSelectionHandler.class);

  @Override
  public void handleUpstream(ChannelHandlerContext ctx, ChannelEvent e) throws Exception
  {
    SslHandler handler = ctx.getPipeline().get(SslHandler.class);
    SimpleServerProvider provider = (SimpleServerProvider) NextProtoNego.get(handler.getEngine());

    if ("spdy/3".equals(provider.getSelectedProtocol()))
    {
      LOG.info("Chose spdy/3");

      ChannelPipeline pipeline = ctx.getPipeline();
      pipeline.addLast("spdyDecoder", new SpdyFrameDecoder(SpdyVersion.SPDY_3));
      pipeline.addLast("spdyEncoder", new SpdyFrameEncoder(SpdyVersion.SPDY_3));
      pipeline.addLast("spdySessionHandler", new SpdySessionHandler(SpdyVersion.SPDY_3, true));
      pipeline.addLast("spdyHttpEncoder", new SpdyHttpEncoder(SpdyVersion.SPDY_3));
      pipeline.addLast("spdyHttpDecoder", new SpdyHttpDecoder(SpdyVersion.SPDY_3, 1024 * 1024));
      pipeline.addLast("infoHandler", new HelloWorldHandler());

      pipeline.remove(this);
      ctx.sendUpstream(e);
    }
    else if ("http/1.1".equals(provider.getSelectedProtocol()))
    {
      LOG.info("Chose http/1.1");

      ChannelPipeline pipeline = ctx.getPipeline();
      pipeline.addLast("httpRequestDecoder", new HttpRequestDecoder());
      pipeline.addLast("httpChunkAggregator", new HttpChunkAggregator(1024 * 1024));
      pipeline.addLast("httpResponseEncoder", new HttpResponseEncoder());
      pipeline.addLast("infoHandler", new HelloWorldHandler());

      pipeline.remove(this);
      ctx.sendUpstream(e);
    }
    else
    {
      LOG.info("Negotiating...");
      // Still in protocol negotiation
    }
  }
}
