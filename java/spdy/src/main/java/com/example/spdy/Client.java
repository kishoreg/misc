package com.example.spdy;

import com.example.spdy.api.Constants;
import com.example.spdy.client.ClientPipelineFactory;
import com.example.spdy.client.HandshakeListener;
import com.example.spdy.client.HttpResponseFuture;
import com.example.spdy.npn.SimpleClientProvider;
import org.apache.log4j.Logger;
import org.eclipse.jetty.npn.NextProtoNego;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.*;
import org.jboss.netty.handler.ssl.SslHandler;

import javax.net.ssl.SSLEngine;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An asynchronous NPN-enabled HTTPS / SPDY client
 *
 * @author Greg Brandt (brandt.greg@gmail.com)
 */
public class Client
{
  private static final Logger LOG = Logger.getLogger(Client.class);

  private enum Protocol
  {
    HTTPS,
    SPDY;

    static Protocol fromNegotiated(String protocol)
    {
      if (Constants.SPDY_3.equals(protocol))
      {
        return SPDY;
      }
      else if (Constants.HTTP_1_1.equals(protocol))
      {
        return HTTPS;
      }
      else
      {
        throw new IllegalArgumentException("Unsupported protocol " + protocol);
      }
    }
  }

  private final URI _baseUri;
  private final InetSocketAddress _remoteAddress;
  private final ClientBootstrap _clientBootstrap;
  private final AtomicReference<Channel> _channel; // used for SPDY only
  private final ReentrantLock _lock;
  private final ConcurrentMap<String, HttpResponseFuture> _spdyFutures;
  private final ChannelLocal<HttpResponseFuture> _httpsFutures;

  public Client(URI baseUri)
  {
    _baseUri = baseUri;
    _remoteAddress = new InetSocketAddress(_baseUri.getHost(), _baseUri.getPort());
    _clientBootstrap = new ClientBootstrap(
            new NioClientSocketChannelFactory(
                    Executors.newCachedThreadPool(),
                    Executors.newCachedThreadPool()));
    _clientBootstrap.setPipelineFactory(new ClientPipelineFactory());
    _channel = new AtomicReference<Channel>();
    _lock = new ReentrantLock();
    _spdyFutures = new ConcurrentHashMap<String, HttpResponseFuture>();
    _httpsFutures = new ChannelLocal<HttpResponseFuture>(true);
  }

  /**
   * Executes an HTTP request asynchronously over a persistent connection.
   *
   * @param httpRequest
   *  The HTTP request to execute
   * @return
   *  A future response
   * @throws Exception
   *  If there were any errors during execution
   */
  public Future<HttpResponse> execute(HttpRequest httpRequest) throws Exception
  {
    Channel channel = getChannel();

    final HttpResponseFuture future = new HttpResponseFuture();

    // Record future in protocol-appropriate data structure
    switch (getNegotiatedProtocol(channel))
    {
      case SPDY:
        String streamId = HttpHeaders.getHeader(httpRequest, Constants.SPDY_STREAM_ID);
        if (streamId == null)
        {
          throw new IllegalArgumentException("Must specify SPDY stream ID");
        }
        _spdyFutures.put(streamId, future);
        break;
      case HTTPS:
        _httpsFutures.setIfAbsent(channel, future);
        break;
    }

    channel.write(httpRequest);

    return future;
  }

  /** Disconnects client */
  public void shutdown()
  {
    _clientBootstrap.releaseExternalResources();
    LOG.info("Shutdown client to " + _baseUri);
  }

  /** @return A protocol-appropriate channel on which to write */
  private Channel getChannel() throws Exception
  {
    _lock.lock();

    try
    {
      Channel channel = null;

      if (_channel.get() != null && _channel.get().isWritable())
      {
        return _channel.get(); // short circuit if we're already connected
      }

      CountDownLatch connected = new CountDownLatch(1);
      _clientBootstrap.connect(_remoteAddress).addListener(new HandshakeListener(_channel, connected));
      connected.await();
      channel = _channel.get();

      switch (getNegotiatedProtocol(channel))
      {
        case HTTPS:
          // immediately remove from reference so we can connect again
          _channel.set(null);
      }

      // Add a handler to fulfill the futures
      channel.getPipeline().addLast("futureHandler", new SimpleChannelUpstreamHandler()
      {
        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception
        {
          HttpResponse httpResponse = (HttpResponse) e.getMessage();

          switch (getNegotiatedProtocol(ctx.getChannel()))
          {
            case SPDY:

              String streamId = HttpHeaders.getHeader(httpResponse, Constants.SPDY_STREAM_ID);
              if (streamId == null)
              {
                throw new IllegalStateException("Stream ID not present in response");
              }

              HttpResponseFuture spdyFuture = _spdyFutures.get(streamId);
              spdyFuture.setResponse(httpResponse);
              spdyFuture.complete();
              _spdyFutures.remove(streamId);
              break;

            case HTTPS:

              HttpResponseFuture httpsFuture = _httpsFutures.get(ctx.getChannel());
              httpsFuture.setResponse(httpResponse);
              httpsFuture.complete();
              break;
          }

          releaseChannel(ctx.getChannel());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception
        {
          switch (getNegotiatedProtocol(ctx.getChannel()))
          {
            case SPDY:
              for (HttpResponseFuture future : _spdyFutures.values())
              {
                future.setError(e.getCause());
                future.complete();
              }
              _spdyFutures.clear();
              break;
            case HTTPS:
              Iterator<Map.Entry<Channel, HttpResponseFuture>> itr = _httpsFutures.iterator();
              while (itr.hasNext())
              {
                HttpResponseFuture future = itr.next().getValue();
                future.setError(e.getCause());
                future.complete();
              }
              // n.b. futures will be removed when channel close
          }

          releaseChannel(ctx.getChannel());
        }
      });

      return channel;
    }
    finally
    {
      _lock.unlock();
    }
  }

  /** Signals that this channel is done being used */
  private void releaseChannel(Channel channel) throws Exception
  {
    switch (getNegotiatedProtocol(channel))
    {
      case HTTPS:
        Channels.close(channel);
        break;
      default:
        // do nothing
    }
  }

  /** @return The NPN-negotiated protocol as an enum */
  private static Protocol getNegotiatedProtocol(Channel channel)
  {
    SSLEngine engine = channel.getPipeline().get(SslHandler.class).getEngine();
    SimpleClientProvider provider = (SimpleClientProvider) NextProtoNego.get(engine);
    return Protocol.fromNegotiated(provider.getSelectedProtocol());
  }
}
