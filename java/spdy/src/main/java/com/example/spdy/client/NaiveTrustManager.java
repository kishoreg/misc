package com.example.spdy.client;

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * An X509TrustManager that trusts anything
 *
 * @author Greg Brandt (brandt.greg@gmail.com)
 */
public class NaiveTrustManager implements X509TrustManager
{
  private static final X509Certificate[] ACCEPTED_ISSUERS = new X509Certificate[0];
  private static final NaiveTrustManager INSTANCE = new NaiveTrustManager();

  // Singleton

  private NaiveTrustManager() {}

  public static NaiveTrustManager getInstance() { return INSTANCE; }

  @Override
  public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException
  {
    // Always trust
  }

  @Override
  public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException
  {
    // Always trust
  }

  @Override
  public X509Certificate[] getAcceptedIssuers()
  {
    return ACCEPTED_ISSUERS;
  }
}
