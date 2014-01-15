package com.example.spdy.client;

import org.jboss.netty.handler.codec.http.HttpResponse;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TODO: Description
 *
 * @author Greg Brandt (brandt.greg@gmail.com)
 */
public class HttpResponseFuture implements Future<HttpResponse>
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

  public void setResponse(HttpResponse httpResponse)
  {
    _response.set(httpResponse);
  }

  public void setError(Throwable error)
  {
    _error.set(error);
  }

  public void complete()
  {
    _latch.countDown();
  }
}
