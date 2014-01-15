package com.example.spdy.server;

import static com.example.spdy.Constants.*;

import org.apache.log4j.Logger;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;
import org.jboss.netty.handler.codec.http.*;

/**
 * A dummy handler that says "Hello, World!"
 *
 * @author Greg Brandt (brandt.greg@gmail.com)
 */
public class HelloWorldHandler extends SimpleChannelUpstreamHandler
{
  private static final Logger LOG = Logger.getLogger(HelloWorldHandler.class);

  @Override
  public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception
  {
    // Request
    HttpRequest httpRequest = (HttpRequest) e.getMessage();
    LOG.info(httpRequest.getMethod() + " " + httpRequest.getUri());

    // Response
    DefaultHttpResponse httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    byte[] content = "Hello, World!".getBytes();
    httpResponse.setContent(ChannelBuffers.wrappedBuffer(content));
    HttpHeaders.setContentLength(httpResponse, content.length);

    // SPDY parts
    String streamId = HttpHeaders.getHeader(httpRequest, SPDY_STREAM_ID);
    if (streamId != null)
    {
      HttpHeaders.addHeader(httpResponse, SPDY_STREAM_ID, streamId);
      HttpHeaders.addHeader(httpResponse, SPDY_STREAM_PRIORITY, 0);
    }

    Channels.write(ctx.getChannel(), httpResponse);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception
  {
    Channels.close(ctx.getChannel());
    LOG.error(e);
  }
}
