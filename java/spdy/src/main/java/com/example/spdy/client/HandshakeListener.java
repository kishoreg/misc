package com.example.spdy.client;

import org.apache.log4j.Logger;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.handler.ssl.SslHandler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Performs an SSL handshake
 *
 * @author Greg Brandt (brandt.greg@gmail.com)
 */
public class HandshakeListener implements ChannelFutureListener
{
  private static final Logger LOG = Logger.getLogger(HandshakeListener.class);

  private final AtomicReference<Channel> _channel;
  private final CountDownLatch _connected;

  public HandshakeListener(AtomicReference<Channel> channel, CountDownLatch connected)
  {
    _channel = channel;
    _connected = connected;
  }

  @Override
  public void operationComplete(ChannelFuture future) throws Exception
  {
    if (future.isSuccess())
    {
      LOG.info("Connected to server");

      // Do handshake
      SslHandler sslHandler = future.getChannel().getPipeline().get(SslHandler.class);
      sslHandler.handshake().addListener(new ChannelFutureListener()
      {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception
        {
          _channel.set(future.getChannel());
          _connected.countDown();
        }
      });
    }
    else
    {
      LOG.error("Could not connect to server");
    }
  }
}
