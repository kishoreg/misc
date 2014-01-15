package com.example.spdy.api;

/**
 * Misc. constants
 *
 * @author Greg Brandt (brandt.greg@gmail.com)
 */
public final class Constants
{
  // Protocols
  public static final String SPDY_3 = "spdy/3";
  public static final String HTTP_1_1 = "http/1.1";
  public static final String SSL_PROTOCOL = "TLS";
  public static final String SSL_ALGORITHM = "SunX509";

  // Server
  public static final String PROP_PORT = "port";
  public static final int DEFAULT_SERVER_PORT = 9000;
  public static final String SERVER_KEYSTORE_RESOURCE_NAME = "server_keystore.jks";
  public static final String SERVER_KEYSTORE_TYPE = "JKS";
  public static final String SERVER_KEYSTORE_SECRET = "secret";

  // Headers
  public static final String SPDY_STREAM_ID = "X-SPDY-Stream-ID";
  public static final String SPDY_STREAM_PRIORITY = "X-SPDY-Stream-Priority";
}
