package com.example.spdy.server;

import org.eclipse.jetty.npn.NextProtoNego;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.HttpChunkAggregator;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.ssl.SslHandler;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

public class SslSelectionHandler extends SimpleChannelUpstreamHandler
{
  private final SSLContext _context;

  public SslSelectionHandler(SSLContext context)
  {
    _context = context;
  }

  @Override
  public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception
  {
    ChannelBuffer buf = (ChannelBuffer) e.getMessage();

    // If the first couple bytes are printable, we use HTTP (SSL bytes will be non printable)
    // Clearly there is a better way, but this works...
    byte[] firstCoupleBytes = new byte[2];
    buf.readBytes(firstCoupleBytes);
    buf.resetReaderIndex();
    boolean useHttp = Character.isLetter(firstCoupleBytes[0]) && Character.isLetter(firstCoupleBytes[1]);

    ChannelPipeline pipeline = ctx.getPipeline();
    if (useHttp)
    {
      pipeline.addLast("httpRequestDecoder", new HttpRequestDecoder());
      pipeline.addLast("httpChunkAggregator", new HttpChunkAggregator(1024 * 1024));
      pipeline.addLast("httpResponseEncoder", new HttpResponseEncoder());
      pipeline.addLast("infoHandler", new InfoHandler());
    }
    else // is HTTPS or SPDY, set up SSL then negotiate
    {
      SSLEngine engine = _context.createSSLEngine();
      engine.setUseClientMode(false);
      NextProtoNego.put(engine, new SimpleServerProvider());
      NextProtoNego.debug = true;

      pipeline.addLast("sslHandler", new SslHandler(engine));
      pipeline.addLast("protocolSelectionHandler", new ProtocolSelectionHandler());
    }

    pipeline.remove(this);
    ctx.sendUpstream(e);
  }
}
