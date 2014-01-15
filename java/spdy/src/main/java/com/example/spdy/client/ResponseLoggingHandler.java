package com.example.spdy.client;

import org.apache.log4j.Logger;
import org.jboss.netty.channel.*;
import org.jboss.netty.handler.codec.http.HttpResponse;

/**
 * Dummy handler that just logs HTTP responses.
 *
 * @author Greg Brandt (brandt.greg@gmail.com)
 */
public class ResponseLoggingHandler extends SimpleChannelUpstreamHandler
{
  private static final Logger LOG = Logger.getLogger(ResponseLoggingHandler.class);

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
    Channels.close(ctx.getChannel());
    LOG.error(e);
  }
}
