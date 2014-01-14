package com.example.spdy.npn;

import org.eclipse.jetty.npn.NextProtoNego;

import java.util.List;

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
    _protocol = "http/1.1";
  }

  @Override
  public String selectProtocol(List<String> protocols)
  {
    String protocol = null;
    if (protocols.contains("spdy/3"))
    {
      protocol = "spdy/3";
    }
    else if (protocols.contains("http/1.1"))
    {
      protocol = "http/1.1";
    }
    _protocol = protocol;
    return protocol;
  }

  public String getSelectedProtocol()
  {
    return _protocol;
  }
}
