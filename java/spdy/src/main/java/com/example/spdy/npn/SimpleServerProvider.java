package com.example.spdy.npn;

import static com.example.spdy.api.Constants.*;

import org.eclipse.jetty.npn.NextProtoNego;

import java.util.Arrays;
import java.util.List;

/**
 * Server-side protocol negotiation via NPN
 *
 * Supports spdy/3 and http/1.1 over SSL
 *
 * @author Greg Brandt (brandt.greg@gmail.com)
 */
public class SimpleServerProvider implements NextProtoNego.ServerProvider
{
  private String _protocol = null;

  @Override
  public void unsupported()
  {
    _protocol = HTTP_1_1;
  }

  @Override
  public List<String> protocols()
  {
    return Arrays.asList(SPDY_3, HTTP_1_1);
//    return Arrays.asList(HTTP_1_1); // only HTTP
  }

  @Override
  public void protocolSelected(String protocol)
  {
    _protocol = protocol;
  }

  /** @return The protocol that was selected via NPN */
  public String getSelectedProtocol()
  {
    return _protocol;
  }
}
