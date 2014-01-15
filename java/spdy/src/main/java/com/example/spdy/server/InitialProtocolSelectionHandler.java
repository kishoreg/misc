package com.example.spdy.server;

import com.example.spdy.npn.SimpleServerProvider;
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

/**
 * Determines whether to use a plain-text- (i.e. HTTP), or SSL-based
 * protocol (i.e. HTTPS, SPDY).
 *
 * If the first few readable bytes are ASCII, and match a declared HTTP method,
 * we assume that the client is speaking HTTP.
 *
 * It will not be the case that the first bytes are printable characters if SSL used.
 *
 * Thanks for this nice little hack, Antony Curtis.
 *
 * @author Greg Brandt (brandt.greg@gmail.com)
 */
public class InitialProtocolSelectionHandler extends SimpleChannelUpstreamHandler
{
  // Some HTTP methods
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

  // Figure out the longest HTTP method name
  private static final int MAX_HTTP_METHOD_LENGTH;
  static
  {
    int max = 0;
    for (String method : HTTP_METHODS)
    {
      if (method.length() > max)
        max = method.length();
    }
    MAX_HTTP_METHOD_LENGTH = max;
  }

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
      // Simple HTTP processing pipeline
      pipeline.addLast("httpRequestDecoder", new HttpRequestDecoder());
      pipeline.addLast("httpChunkAggregator", new HttpChunkAggregator(1024 * 1024));
      pipeline.addLast("httpResponseEncoder", new HttpResponseEncoder());
      pipeline.addLast("helloWorldHandler", new HelloWorldHandler());
    }
    else
    {
      // SSL
      SSLEngine engine = _context.createSSLEngine();
      engine.setUseClientMode(false);

      // NPN
      NextProtoNego.put(engine, new SimpleServerProvider());
      NextProtoNego.debug = true;

      // Initial pipeline state
      pipeline.addLast("sslHandler", new SslHandler(engine));
      pipeline.addLast("protocolSelectionHandler", new SecureServerProtocolSelectionHandler());
    }

    pipeline.remove(this);
    ctx.sendUpstream(e);
  }

  /** @return true if the first few bytes are an HTTP method in ASCII */
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
