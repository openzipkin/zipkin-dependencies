/*
 * Copyright The OpenZipkin Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package zipkin2.dependencies;

import java.io.IOException;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ZipkinElasticsearchStorage {
  private static final Logger LOG = LoggerFactory.getLogger(ZipkinElasticsearchStorage.class);
  private static final Pattern DISTRIBUTION = Pattern.compile("\"distribution\"\s*[:]\s*\"([^\"]+)\"");

  static final String HOSTS = getEnv("ES_HOSTS", "127.0.0.1");
  static final String USERNAME = getEnv("ES_USERNAME", null);
  static final String PASSWORD = getEnv("ES_PASSWORD", null);

  static TrustManager[] TRUST_ALL = new TrustManager [] {
    new X509TrustManager() {
      @Override
      public X509Certificate[] getAcceptedIssuers() {
        return null;
      }
      
      @Override
      public void checkClientTrusted(X509Certificate[] certs, String authType) {
      }
      
      @Override
      public void checkServerTrusted(X509Certificate[] certs, String authType) {
      }
    }
  };
  
  static String flavor() {
    final HttpClient.Builder builder = HttpClient
      .newBuilder()
      .connectTimeout(Duration.ofSeconds(5));

    if (USERNAME != null && PASSWORD != null) {
      builder.authenticator(new Authenticator() {
         @Override
         protected PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(USERNAME, PASSWORD.toCharArray());
        }
      });
    }

    try {
      final SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, TRUST_ALL, new SecureRandom());

      final HttpClient client = builder.sslContext(sslContext).build();
      try {
        for (String host: parseHosts(HOSTS)) {
          final HttpRequest request = HttpRequest.newBuilder().GET().uri(URI.create(host)).build();
          try {
            final HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            final Matcher matcher = DISTRIBUTION.matcher(response.body());
            if (matcher.find()) {
              return matcher.group(1).toLowerCase();
            }
          } catch (InterruptedException | IOException ex) {
            LOG.warn("Unable issue HTTP GET request to '" + host + "'", ex);
          }
        }
      } finally {
        if (client instanceof AutoCloseable) {
          try {
            // Since JDK-21, the HttpClient is AutoCloseable
            ((AutoCloseable) client).close();
          } catch (Exception ex) {
            /* Ignore */
          }
        }
      }
    } catch (final NoSuchAlgorithmException | KeyManagementException ex) {
      LOG.warn("Unable to configure HttpClient", ex);
    }

    return "elasticsearch";
  }

  private static String getEnv(String key, String defaultValue) {
    String result = System.getenv(key);
    return result != null && !result.isEmpty() ? result : defaultValue;
  }

  static String[] parseHosts(String hosts) {
    final String[] hostParts = hosts.split(",", -1);

    // Detect default scheme to use if not specified
    String defaultScheme = "http";
    for (int i = 0; i < hostParts.length; i++) {
      String host = hostParts[i];
      if (host.startsWith("https")) {
        defaultScheme = "https";
        break;
      }
    }

    Collection<String> list = new ArrayList<>();
    for (int i = 0; i < hostParts.length; i++) {
      String host = hostParts[i];
      URI httpUri = host.startsWith("http") ? URI.create(host) : URI.create(defaultScheme + "://" + host);

      int port = httpUri.getPort();
      if (port == -1) {
        port = 9200; /* default Elasticsearch / OpenSearch port */
      }

      list.add(httpUri.getScheme() + "://" + httpUri.getHost() + ":" + port);
    }

    return list.toArray(new String[0]);
  }
  
  public static void main(String[] s) {
      System.out.println(flavor());
  }
}
