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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An asynchronous NPN-enabled HTTPS / SPDY client
 *
 * For SPDY, each stream must have a unique, monotonically increasing, and odd ID.
 * Use {@link com.example.spdy.Client#getNextSpdyStreamId()} from a Client user perspective
 * to generate a valid ID.
 *
 * There should be a unique stream ID per concurrent request.
 *
 * @author Greg Brandt (brandt.greg@gmail.com)
 */
public class Client
{
  private static final Logger LOG = Logger.getLogger(Client.class);

  /** The negotiated protocol */
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

  /** The base server URI */
  private final URI _baseUri;
  /** The server address */
  private final InetSocketAddress _remoteAddress;
  /** Generates client channels */
  private final ClientBootstrap _clientBootstrap;
  /** A persistent channel (used for SPDY) */
  private final AtomicReference<Channel> _channel;
  /** Enforces mutual exclusion when manipulating this client's channel(s) */
  private final ReentrantLock _lock;
  /** A mapping of SPDY stream ID to uncompleted future */
  private final ConcurrentMap<String, HttpResponseFuture> _spdyFutures;
  /** A mapping of channel to uncompleted future (HTTPS uses many channels for concurrency) */
  private final ChannelLocal<HttpResponseFuture> _httpsFutures;
  /** The next safe SPDY stream ID to use over _channel */
  private final AtomicInteger _nextSpdyStreamId;

  public Client(URI baseUri)
  {
    // Address
    _baseUri = baseUri;
    _remoteAddress = new InetSocketAddress(_baseUri.getHost(), _baseUri.getPort());

    // Netty
    _clientBootstrap = new ClientBootstrap(
            new NioClientSocketChannelFactory(
                    Executors.newCachedThreadPool(),
                    Executors.newCachedThreadPool()));
    _clientBootstrap.setPipelineFactory(new ClientPipelineFactory());

    // Channel and futures
    _channel = new AtomicReference<Channel>();
    _spdyFutures = new ConcurrentHashMap<String, HttpResponseFuture>();
    _httpsFutures = new ChannelLocal<HttpResponseFuture>(true);

    // Misc
    _nextSpdyStreamId = new AtomicInteger(1);
    _lock = new ReentrantLock();
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

  /** @return The next odd, monotonically increasing stream ID */
  public int getNextSpdyStreamId()
  {
    return _nextSpdyStreamId.getAndAdd(2);
  }

  /** @return A protocol-appropriate channel on which to write */
  private Channel getChannel() throws Exception
  {
    _lock.lock();

    try
    {
      Channel channel = null;

      // Short circuit if we're already connected
      if (_channel.get() != null && _channel.get().isWritable())
      {
        return _channel.get();
      }

      // Connect
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

      // Add future handler
      channel.getPipeline().addLast("futureHandler", new FutureHandler(this));

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
        // do nothing, SPDY can re-use channel
    }
  }

  /** @return The NPN-negotiated protocol as an enum */
  private static Protocol getNegotiatedProtocol(Channel channel)
  {
    SSLEngine engine = channel.getPipeline().get(SslHandler.class).getEngine();
    SimpleClientProvider provider = (SimpleClientProvider) NextProtoNego.get(engine);
    return Protocol.fromNegotiated(provider.getSelectedProtocol());
  }

  /** Completes a Client's futures */
  private static class FutureHandler extends SimpleChannelUpstreamHandler
  {
    private final Client _client;

    FutureHandler(Client client)
    {
      _client = client;
    }

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

          HttpResponseFuture spdyFuture = _client._spdyFutures.get(streamId);
          spdyFuture.setResponse(httpResponse);
          spdyFuture.complete();
          _client._spdyFutures.remove(streamId);
          break;

        case HTTPS:

          HttpResponseFuture httpsFuture = _client._httpsFutures.get(ctx.getChannel());
          httpsFuture.setResponse(httpResponse);
          httpsFuture.complete();
          break;
      }

      _client.releaseChannel(ctx.getChannel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception
    {
      switch (getNegotiatedProtocol(ctx.getChannel()))
      {
        case SPDY:
          for (HttpResponseFuture future : _client._spdyFutures.values())
          {
            future.setError(e.getCause());
            future.complete();
          }
          _client._spdyFutures.clear();
          break;
        case HTTPS:
          Iterator<Map.Entry<Channel, HttpResponseFuture>> itr = _client._httpsFutures.iterator();
          while (itr.hasNext())
          {
            HttpResponseFuture future = itr.next().getValue();
            future.setError(e.getCause());
            future.complete();
          }
          // n.b. futures will be removed when channel close
      }

      _client.releaseChannel(ctx.getChannel());
    }
  }
}
