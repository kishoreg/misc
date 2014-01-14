package com.example.spdy.utils;

import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

public class NaiveTrustManager implements X509TrustManager
{
  private static final X509Certificate[] ACCEPTED_ISSUERS = new X509Certificate[0];

  private NaiveTrustManager() {}

  private static final NaiveTrustManager INSTANCE = new NaiveTrustManager();

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
