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

  private final URI _baseUri;
  private final ClientBootstrap _clientBootstrap;
  private final AtomicReference<Channel> _channel;
  private final ReentrantLock _lock;

  public Client(URI baseUri)
  {
    _baseUri = baseUri;
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
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicBoolean isCancelled = new AtomicBoolean(false);
    final AtomicReference<HttpResponse> response = new AtomicReference<HttpResponse>();
    final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
    final Future<HttpResponse> httpResponseFuture = new Future<HttpResponse>()
    {
      @Override
      public boolean cancel(boolean mayInterruptIfRunning)
      {
        error.set(new InterruptedException());
        latch.countDown();
        isCancelled.set(true);
        return true;
      }

      @Override
      public boolean isCancelled()
      {
        return isCancelled.get();
      }

      @Override
      public boolean isDone()
      {
        return latch.getCount() == 0;
      }

      @Override
      public HttpResponse get() throws InterruptedException, ExecutionException
      {
        latch.await();
        if (error.get() != null)
        {
          throw new ExecutionException(error.get());
        }
        return response.get();
      }

      @Override
      public HttpResponse get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
      {
        latch.await(timeout, unit);
        if (error.get() != null)
        {
          throw new ExecutionException(error.get());
        }
        return response.get();
      }
    };

    // Adds a handler to the pipeline
    // TODO: Demux SPDY responses
    // TODO: Ensure only one HTTPS connection writes to channel at a time
    channel.getPipeline().addLast("futureHandler", new SimpleChannelUpstreamHandler() {
      @Override
      public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception
      {
        HttpResponse httpResponse = (HttpResponse) e.getMessage();
        response.set(httpResponse);
        latch.countDown();
      }

      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception
      {
        error.set(e.getCause());
        latch.countDown();
      }
    });

    channel.write(httpRequest);

    return httpResponseFuture;
  }

  public void shutdown()
  {
    _clientBootstrap.releaseExternalResources();
    LOG.info("Shutdown client to " + _baseUri);
  }

  private Channel getChannel() throws Exception
  {
    try
    {
      _lock.lock();
      if (_channel.get() == null || !_channel.get().isWritable())
      {
        final CountDownLatch connected = new CountDownLatch(1);
        _clientBootstrap.connect(new InetSocketAddress(_baseUri.getHost(), _baseUri.getPort()))
                        .addListener(new HandshakeListener(_baseUri, _channel, connected));
        connected.await();
      }
      return _channel.get();
    }
    finally
    {
      _lock.unlock();
    }
  }

  // TODO: Release channel method?

  private static class HandshakeListener implements ChannelFutureListener
  {
    private final URI _baseUri;
    private final AtomicReference<Channel> _channel;
    private final CountDownLatch _connected;

    HandshakeListener(URI baseUri, AtomicReference<Channel> channel, CountDownLatch connected)
    {
      _baseUri = baseUri;
      _channel = channel;
      _connected = connected;
    }

    @Override
    public void operationComplete(ChannelFuture future) throws Exception
    {
      if (future.isSuccess())
      {
        LOG.info("Connected to localhost:" + _baseUri.getPort());

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
        LOG.error("Could not connect to localhost:" + _baseUri.getPort());
      }
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

    Client client = new Client(URI.create("http://localhost:9000"));

    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    HttpHeaders.setHeader(request, Constants.SPDY_STREAM_ID, 1);
    HttpHeaders.setHeader(request, HttpHeaders.Names.HOST, "localhost");

    Future<HttpResponse> response = client.execute(request);
    response.get();

    LOG.info(response.get());
  }
}
