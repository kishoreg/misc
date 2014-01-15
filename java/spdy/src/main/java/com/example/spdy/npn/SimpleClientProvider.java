package com.example.spdy.npn;

import static com.example.spdy.api.Constants.*;

import org.eclipse.jetty.npn.NextProtoNego;

import java.util.List;

/**
 * Client-side protocol negotiation via NPN
 *
 * Supports spdy/3 and http/1.1 over SSL
 *
 * @author Greg Brandt (brandt.greg@gmail.com)
 */
public class SimpleClientProvider implements NextProtoNego.ClientProvider
{
  private String _protocol = null;

  @Override
  public boolean supports()
  {
    return true;
  }

  @Override
  public void unsupported()
  {
    _protocol = HTTP_1_1;
  }

  @Override
  public String selectProtocol(List<String> protocols)
  {
    String protocol = null;

    if (protocols.contains(SPDY_3))
      protocol = SPDY_3;
    else if (protocols.contains(HTTP_1_1))
      protocol = HTTP_1_1;

    _protocol = protocol;

    return protocol;
  }

  /** @return The protocol selected via NPN */
  public String getSelectedProtocol()
  {
    return _protocol;
  }
}
