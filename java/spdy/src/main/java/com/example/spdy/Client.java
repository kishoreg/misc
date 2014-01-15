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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * TODO: Description
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

  public Future<HttpResponse> execute(HttpRequest httpRequest) throws Exception
  {
    // The channel
    Channel channel = getChannel();

    // The future
    final CountDownLatch _latch = new CountDownLatch(1);
    final AtomicReference<HttpResponse> _response = new AtomicReference<HttpResponse>();
    final AtomicReference<Throwable> _error = new AtomicReference<Throwable>();
    final Future<HttpResponse> httpResponseFuture = new Future<HttpResponse>()
    {
      @Override
      public boolean cancel(boolean mayInterruptIfRunning)
      {
        return false; // can't cancel?
      }

      @Override
      public boolean isCancelled()
      {
        return false; // can't cancel?
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
    };

    // Fulfills the future
    channel.getPipeline().addLast("futureHandler", new SimpleChannelUpstreamHandler() {
      @Override
      public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception
      {
        HttpResponse httpResponse = (HttpResponse) e.getMessage();
        _response.set(httpResponse);
        _latch.countDown();
      }

      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception
      {
        _error.set(e.getCause());
        _latch.countDown();
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
