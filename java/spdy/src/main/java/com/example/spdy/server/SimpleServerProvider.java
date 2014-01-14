package com.example.spdy.server;

import org.eclipse.jetty.npn.NextProtoNego;

import java.util.Arrays;
import java.util.List;

public class SimpleServerProvider implements NextProtoNego.ServerProvider
{
  private String _protocol = null;

  @Override
  public void unsupported()
  {
    _protocol = "http/1.1";
  }

  @Override
  public List<String> protocols()
  {
    return Arrays.asList("spdy/3", "http/1.1");
  }

  @Override
  public void protocolSelected(String protocol)
  {
    _protocol = protocol;
  }

  public String getSelectedProtocol()
  {
    return _protocol;
  }
}
