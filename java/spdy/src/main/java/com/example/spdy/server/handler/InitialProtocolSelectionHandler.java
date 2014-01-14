package com.example.spdy.server.handler;

import com.example.spdy.server.SimpleServerProvider;
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

public class InitialProtocolSelectionHandler extends SimpleChannelUpstreamHandler
{
  private static final int MAX_HTTP_METHOD_LENGTH = 7;

  private static final String[] HTTP_METHODS = {
      "OPTIONS",
      "GET",
      "HEAD",
      "POST",
      "PUT",
      "DELETE",
      "TRACE",
      "CONNECT",
      "PATCH"
  }; // TODO: More if necessary

  private final SSLContext _context;

  public InitialProtocolSelectionHandler(SSLContext context)
  {
    _context = context;
  }

  @Override
  public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception
  {
    ChannelBuffer buf = (ChannelBuffer) e.getMessage();

    ChannelPipeline pipeline = ctx.getPipeline();
    if (shouldUseHttp(buf))
    {
      pipeline.addLast("httpRequestDecoder", new HttpRequestDecoder());
      pipeline.addLast("httpChunkAggregator", new HttpChunkAggregator(1024 * 1024));
      pipeline.addLast("httpResponseEncoder", new HttpResponseEncoder());
      pipeline.addLast("infoHandler", new HelloWorldHandler());
    }
    else
    {
      SSLEngine engine = _context.createSSLEngine();
      engine.setUseClientMode(false);
      NextProtoNego.put(engine, new SimpleServerProvider());
      NextProtoNego.debug = true;

      pipeline.addLast("sslHandler", new SslHandler(engine));
      pipeline.addLast("protocolSelectionHandler", new SecureProtocolSelectionHandler());
    }

    pipeline.remove(this);
    ctx.sendUpstream(e);
  }

  private static boolean shouldUseHttp(ChannelBuffer buf)
  {
    byte[] firstBytes = new byte[MAX_HTTP_METHOD_LENGTH];
    buf.readBytes(firstBytes);
    buf.resetReaderIndex();
    String s = new String(firstBytes);

    for (String method : HTTP_METHODS)
    {
      if (s.startsWith(method))
      {
        return true;
      }
    }

    return false;
  }
}
