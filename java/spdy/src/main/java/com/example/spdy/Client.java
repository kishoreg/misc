package com.example.spdy;

import com.example.spdy.client.ClientPipelineFactory;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.*;
import org.jboss.netty.handler.ssl.SslHandler;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
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
  }

  private final URI _baseUri;
  private final InetSocketAddress _remoteAddress;
  private final Protocol _protocol;
  private final ClientBootstrap _clientBootstrap;
  private final AtomicReference<Channel> _channel; // used for SPDY only
  private final ReentrantLock _lock;

  public Client(URI baseUri)
  {
    _baseUri = baseUri;
    _remoteAddress = new InetSocketAddress(_baseUri.getHost(), _baseUri.getPort());
    _protocol = Protocol.valueOf(_baseUri.getScheme().toUpperCase());
    _clientBootstrap = new ClientBootstrap(
            new NioClientSocketChannelFactory(
                    Executors.newCachedThreadPool(),
                    Executors.newCachedThreadPool()));
    _clientBootstrap.setPipelineFactory(new ClientPipelineFactory());
    _channel = new AtomicReference<Channel>();
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
    // The channel
    Channel channel = getChannel();

    // The future
    final HttpResponseFuture future = new HttpResponseFuture();

    // Adds a handler to the pipeline
    // TODO: Demux SPDY responses
    // TODO: Ensure only one HTTPS connection writes to channel at a time
    channel.getPipeline().addLast("futureHandler", new SimpleChannelUpstreamHandler() {
      @Override
      public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception
      {
        HttpResponse httpResponse = (HttpResponse) e.getMessage();
        future.setResponse(httpResponse);
        future.complete();
        releaseChannel(ctx.getChannel());
      }

      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception
      {
        future.setError(e.getCause());
        future.complete();
        releaseChannel(ctx.getChannel());
      }
    });

    channel.write(httpRequest);

    return future;
  }

  public void shutdown()
  {
    _clientBootstrap.releaseExternalResources();
    LOG.info("Shutdown client to " + _baseUri);
  }

  private Channel getChannel() throws Exception
  {
    _lock.lock();

    try
    {
      switch (_protocol)
      {
        case SPDY:
          if (_channel.get() != null && _channel.get().isWritable())
          {
            return _channel.get();
          }
        default:
          CountDownLatch connected = new CountDownLatch(1);
          _clientBootstrap.connect(_remoteAddress).addListener(new HandshakeListener(_channel, connected));
          connected.await();
          return _channel.get();
      }
    }
    finally
    {
      _lock.unlock();
    }
  }

  private void releaseChannel(Channel channel) throws Exception
  {
    switch (_protocol)
    {
      case HTTPS:
        Channels.close(channel);
        break;
      default:
        // do nothing
    }
  }

  /**
   * Performs SSL handshake when channel is connected
   */
  private static class HandshakeListener implements ChannelFutureListener
  {
    private final AtomicReference<Channel> _channel;
    private final CountDownLatch _connected;

    HandshakeListener(AtomicReference<Channel> channel, CountDownLatch connected)
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

  private static class HttpResponseFuture implements Future<HttpResponse>
  {
    private final CountDownLatch _latch = new CountDownLatch(1);
    private final AtomicBoolean _isCancelled = new AtomicBoolean(false);
    private final AtomicReference<HttpResponse> _response = new AtomicReference<HttpResponse>();
    private final AtomicReference<Throwable> _error = new AtomicReference<Throwable>();

    @Override
    public boolean cancel(boolean mayInterruptIfRunning)
    {
      _error.set(new InterruptedException());
      _latch.countDown();
      _isCancelled.set(true);
      return true;
    }

    @Override
    public boolean isCancelled()
    {
      return _isCancelled.get();
    }

    @Override
    public boolean isDone()
    {
      return _latch.getCount() == 0;
    }

    @Override
    public HttpResponse get() throws InterruptedException, ExecutionException
    {
      _latch.await();
      if (_error.get() != null)
      {
        throw new ExecutionException(_error.get());
      }
      return _response.get();
    }

    @Override
    public HttpResponse get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
    {
      _latch.await(timeout, unit);
      if (_error.get() != null)
      {
        throw new ExecutionException(_error.get());
      }
      return _response.get();
    }

    void setResponse(HttpResponse httpResponse)
    {
      _response.set(httpResponse);
    }

    void setError(Throwable error)
    {
      _error.set(error);
    }

    void complete()
    {
      _latch.countDown();
    }
  }

  public static void main(String[] args) throws Exception
  {
    // Logger
    ConsoleAppender console = new ConsoleAppender();
    PatternLayout layout = new PatternLayout("%d{yyyy-MM-dd HH:mm:ss} %-5p %c{1}:%L - %m%n");
    console.setLayout(layout);
    console.activateOptions();
    Logger.getRootLogger().addAppender(console);

    Client client = new Client(URI.create("https://localhost:9000"));

    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    HttpHeaders.setHeader(request, Constants.SPDY_STREAM_ID, 1);
    HttpHeaders.setHeader(request, HttpHeaders.Names.HOST, "localhost");

    Future<HttpResponse> response = client.execute(request);
    response.get();

    LOG.info(response.get());
  }
}
