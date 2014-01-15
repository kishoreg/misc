package com.example.spdy;

import static com.example.spdy.Constants.*;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.jboss.netty.handler.codec.http.*;

import java.net.URI;
import java.util.concurrent.Future;

/**
 * A client that connects to a server and negotiates protocol via NPN
 *
 * @author Greg Brandt (brandt.greg@gmail.com)
 */
public class ClientDemo
{
  private static final Logger LOG = Logger.getLogger(ClientDemo.class);

  public static void main(String[] args) throws Exception
  {
    // Logger
    ConsoleAppender console = new ConsoleAppender();
    PatternLayout layout = new PatternLayout("%d{yyyy-MM-dd HH:mm:ss} %-5p %c{1}:%L - %m%n");
    console.setLayout(layout);
    console.activateOptions();
    Logger.getRootLogger().addAppender(console);

    // Port
    String customPort = System.getProperty(PROP_PORT);
    final int port = customPort == null ? DEFAULT_SERVER_PORT : Integer.parseInt(customPort);

    // Client
    final Client client = new Client(URI.create(String.format("https://localhost:%d", port)));

    // Write request
    LOG.info("Writing HTTP request");
    HttpRequest httpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    HttpHeaders.setHeader(httpRequest, "X-SPDY-Stream-ID", 1);
    HttpHeaders.setHeader(httpRequest, HttpHeaders.Names.HOST, "localhost");
    Future<HttpResponse> responseFuture = client.execute(httpRequest);

    // Print out response
    HttpResponse response = responseFuture.get();
    byte[] content = new byte[response.getContent().readableBytes()];
    response.getContent().readBytes(content);
    response.getContent().resetReaderIndex();
    LOG.info(response.getStatus());
    LOG.info(new String(content));

    // We're done
    client.shutdown();
  }
}
