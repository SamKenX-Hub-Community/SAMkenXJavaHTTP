/*
 * Copyright (c) 2022, FusionAuth, All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package io.fusionauth.http;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import com.inversoft.net.ssl.SSLTools;
import com.inversoft.rest.RESTClient;
import com.inversoft.rest.TextResponseHandler;
import io.fusionauth.http.HTTPValues.Connections;
import io.fusionauth.http.HTTPValues.Headers;
import io.fusionauth.http.server.CountingInstrumenter;
import io.fusionauth.http.server.HTTPHandler;
import io.fusionauth.http.server.HTTPListenerConfiguration;
import io.fusionauth.http.server.HTTPServer;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.fail;

/**
 * Tests the HTTP server.
 *
 * @author Brian Pontarelli
 */
public class CoreTest extends BaseTest {
  public static final String ExpectedResponse = "{\"version\":\"42\"}";

  public static final String RequestBody = "{\"message\":\"Hello World\"";

  static {
    System.setProperty("sun.net.http.retryPost", "false");
    System.setProperty("jdk.httpclient.allowRestrictedHeaders", "connection");
  }

  @Test
  public void badPreambleButReset() throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertNull(req.getHeader("Bad-Header"));
      assertEquals(req.getHeader("Good-Header"), "Good-Header");
      res.setStatus(200);
    };

    var instrumenter = new CountingInstrumenter();
    try (HTTPServer ignore = makeServer("http", handler, instrumenter).start()) {
      sendBadRequest("""
          GET / HTTP/1.1\r
          X-Bad-Header: Bad-Header\r\r
          """);

      var client = HttpClient.newHttpClient();
      URI uri = makeURI("http", "");
      HttpRequest request = HttpRequest.newBuilder()
                                       .uri(uri)
                                       .header("Good-Header", "Good-Header")
                                       .GET()
                                       .build();
      var response = client.send(request, r -> BodySubscribers.ofString(StandardCharsets.UTF_8));
      assertEquals(response.statusCode(), 200);
    }

    assertEquals(instrumenter.getBadRequests(), 1);
  }

  @Test(dataProvider = "schemes")
  public void clientTimeout(String scheme) {
    HTTPHandler handler = (req, res) -> {
      System.out.println("Handling");
      res.setStatus(200);
      res.setContentLength(0L);
      res.getOutputStream().close();
    };

    try (HTTPServer ignore = makeServer(scheme, handler).withClientTimeout(Duration.ofSeconds(1)).start()) {
      SSLTools.disableSSLValidation();
      URI uri = makeURI(scheme, "");
      try {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setDoInput(true);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.addRequestProperty("Content-Length", "42");
        connection.connect();
        var os = connection.getOutputStream();
        os.write("start".getBytes());
        os.flush();
        sleep(3_500L);
        os.write("more".getBytes());
        connection.getResponseCode(); // Should fail on the read
        fail("Should have timed out");
      } catch (Exception e) {
        // Expected
        e.printStackTrace();
      }
    } finally {
      SSLTools.enableSSLValidation();
    }
  }

  @Test(dataProvider = "schemes")
  public void emptyContentType(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertNull(req.getContentType());
      res.setStatus(200);
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "").POST(BodyPublishers.noBody()).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
    }
  }

  @Test(dataProvider = "schemes")
  public void emptyContentTypeWithEncoding(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertEquals(req.getContentType(), "");
      assertEquals(req.getCharacterEncoding(), StandardCharsets.UTF_16);
      res.setStatus(200);
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "; charset=UTF-16").POST(BodyPublishers.noBody()).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
    }
  }

  @Test(dataProvider = "schemes")
  public void handlerFailureGet(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      throw new IllegalStateException("Bad state");
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).GET().build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 500);
    }
  }

  @Test(dataProvider = "schemes")
  public void handlerFailurePost(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      throw new IllegalStateException("Bad state");
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "application/json").POST(BodyPublishers.ofString(RequestBody)).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 500);
    }
  }

  @Test(dataProvider = "schemes")
  public void hugeHeaders(String scheme) throws Exception {
    // 260 characters for a total of 16,640 bytes per header value. 5 headers for a total of 83,200 bytes
    String headerValue = "12345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890".repeat(64);

    HTTPHandler handler = (req, res) -> {
      res.setHeader(Headers.ContentType, "text/plain");
      res.setHeader("Content-Length", "16");
      res.setHeader("X-Huge-Header-1", headerValue);
      res.setHeader("X-Huge-Header-2", headerValue);
      res.setHeader("X-Huge-Header-3", headerValue);
      res.setHeader("X-Huge-Header-4", headerValue);
      res.setHeader("X-Huge-Header-5", headerValue);
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
      var response = client.send(
          HttpRequest.newBuilder()
                     .uri(uri)
                     .header("X-Huge-Header-1", headerValue)
                     .header("X-Huge-Header-2", headerValue)
                     .header("X-Huge-Header-3", headerValue)
                     .header("X-Huge-Header-4", headerValue)
                     .header("X-Huge-Header-5", headerValue)
                     .POST(BodyPublishers.ofString(RequestBody))
                     .build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
    }
  }

  @Test(dataProvider = "schemes", groups = "performance")
  public void performance(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setHeader(Headers.ContentType, "text/plain");
      res.setHeader("Content-Length", "16");
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    int iterations = 100_000;
    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (HTTPServer ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
      long start = System.currentTimeMillis();
      for (int i = 0; i < iterations; i++) {
        var response = client.send(
            HttpRequest.newBuilder().uri(uri).GET().build(),
            r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
        );

        assertEquals(response.statusCode(), 200);
        assertEquals(response.body(), ExpectedResponse);

        if (i % 1_000 == 0) {
          System.out.println(i);
        }
      }

      long end = System.currentTimeMillis();
      double average = (end - start) / (double) iterations;
      System.out.println("Average linear request time is [" + average + "]ms");
    }

    assertEquals(instrumenter.getConnections(), 1);
  }

  @Test(dataProvider = "schemes", groups = "performance")
  public void performanceNoKeepAlive(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setHeader(Headers.ContentType, "text/plain");
      res.setHeader("Content-Length", "16");
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    int iterations = 1_000;
    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (HTTPServer ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
      long start = System.currentTimeMillis();
      for (int i = 0; i < iterations; i++) {
        var response = client.send(
            HttpRequest.newBuilder().uri(uri).header(Headers.Connection, Connections.Close).GET().build(),
            r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
        );

        assertEquals(response.statusCode(), 200);
        assertEquals(response.body(), ExpectedResponse);
      }

      long end = System.currentTimeMillis();
      double average = (end - start) / (double) iterations;
      System.out.println("Average linear request time without keep-alive is [" + average + "]ms");
    }

    assertEquals(instrumenter.getConnections(), iterations);
  }

  /**
   * This test uses Restify in order to leverage the URLConnection implementation of the JDK. That implementation is not smart enough to
   * realize that a socket in the connection pool that was using Keep-Alives with the server is potentially dead. Since we are shutting down
   * the server and doing another request, this ensures that the server itself is sending a socket close signal back to the URLConnection
   * and removing the socket form the connection pool.
   */
  @Test(dataProvider = "schemes")
  public void serverClosesSockets(String scheme) {
    HTTPHandler handler = (req, res) -> {
      res.setHeader(Headers.ContentType, "text/plain");
      res.setHeader("Content-Length", "16");
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      SSLTools.disableSSLValidation();
      URI uri = makeURI(scheme, "");
      var response = new RESTClient<>(String.class, String.class).url(uri.toString())
                                                                 .connectTimeout(600_000)
                                                                 .readTimeout(600_000)
                                                                 .get()
                                                                 .successResponseHandler(new TextResponseHandler())
                                                                 .errorResponseHandler(new TextResponseHandler())
                                                                 .go();
      assertEquals(response.status, 200);
      assertEquals(response.successResponse, ExpectedResponse);
    } finally {
      SSLTools.enableSSLValidation();
    }

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      SSLTools.disableSSLValidation();
      URI uri = makeURI(scheme, "");
      var response = new RESTClient<>(String.class, String.class).url(uri.toString())
                                                                 .connectTimeout(600_000)
                                                                 .readTimeout(600_000)
                                                                 .get()
                                                                 .successResponseHandler(new TextResponseHandler())
                                                                 .errorResponseHandler(new TextResponseHandler())
                                                                 .go();
      assertEquals(response.status, 200);
      assertEquals(response.successResponse, ExpectedResponse);
    } finally {
      SSLTools.enableSSLValidation();
    }
  }

  @Test(dataProvider = "schemes")
  public void simpleGet(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertEquals(req.getAcceptEncodings(), List.of("deflate", "compress", "identity", "gzip", "br"));
      assertEquals(req.getBaseURL(), scheme.equals("http") ? "http://localhost:4242" : "https://local.fusionauth.io:4242");
      assertEquals(req.getContentType(), "text/plain");
      assertEquals(req.getCharacterEncoding(), StandardCharsets.ISO_8859_1);
      assertEquals(req.getHeader(Headers.Origin), "https://example.com");
      assertEquals(req.getHeader(Headers.Referer), "foobar.com");
      assertEquals(req.getHeader(Headers.UserAgent), "java-http test");
      assertEquals(req.getHost(), scheme.equals("http") ? "localhost" : "local.fusionauth.io");
      assertEquals(req.getIPAddress(), "127.0.0.1");
      assertEquals(req.getLocales(), List.of(Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH));
      assertEquals(req.getMethod(), HTTPMethod.GET);
      assertEquals(req.getParameter("foo "), "bar ");
      assertEquals(req.getPath(), "/api/system/version");
      assertEquals(req.getPort(), 4242);
      assertEquals(req.getProtocol(), "HTTP/1.1");
      assertEquals(req.getQueryString(), "foo%20=bar%20");
      assertEquals(req.getScheme(), scheme);
      assertEquals(req.getURLParameter("foo "), "bar ");

      res.setHeader(Headers.ContentType, "text/plain");
      res.setHeader("Content-Length", "16");
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      var client = makeClient(scheme, null);
      URI uri = makeURI(scheme, "?foo%20=bar%20");
      HttpRequest request = HttpRequest.newBuilder()
                                       .uri(uri)
                                       .header(Headers.AcceptEncoding, "deflate, compress, br;q=0.5, gzip;q=0.8, identity;q=1.0")
                                       .header(Headers.AcceptLanguage, "en, fr;q=0.7, de;q=0.8")
                                       .header(Headers.ContentType, "text/plain; charset=ISO8859-1")
                                       .header(Headers.Origin, "https://example.com")
                                       .header(Headers.Referer, "foobar.com")
                                       .header(Headers.UserAgent, "java-http test")
                                       .GET()
                                       .build();
      var response = client.send(request, r -> BodySubscribers.ofString(StandardCharsets.UTF_8));

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), ExpectedResponse);
    }
  }

  @Test
  public void simpleGetMultiplePorts() throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setHeader(Headers.ContentType, "text/plain");
      res.setHeader("Content-Length", "16");
      res.setStatus(200);

      try {
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    keyPair = generateNewRSAKeyPair();
    certificate = generateSelfSignedCertificate(keyPair.getPublic(), keyPair.getPrivate());
    try (HTTPServer ignore = new HTTPServer().withHandler(handler)
                                             .withNumberOfWorkerThreads(1)
                                             .withListener(new HTTPListenerConfiguration(4242))
                                             .withListener(new HTTPListenerConfiguration(4243))
                                             .withListener(new HTTPListenerConfiguration(4244, certificate, keyPair.getPrivate()))
                                             .start()) {
      var client = makeClient("https", null);
      URI uri = URI.create("http://localhost:4242/api/system/version?foo=bar");
      HttpRequest request = HttpRequest.newBuilder().uri(uri).GET().build();
      var response = client.send(request, r -> BodySubscribers.ofString(StandardCharsets.UTF_8));

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), ExpectedResponse);

      // Try the other port
      uri = URI.create("http://localhost:4243/api/system/version?foo=bar");
      request = HttpRequest.newBuilder().uri(uri).GET().build();
      response = client.send(request, r -> BodySubscribers.ofString(StandardCharsets.UTF_8));

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), ExpectedResponse);

      // Try the TLS port
      uri = URI.create("https://local.fusionauth.io:4244/api/system/version?foo=bar");
      request = HttpRequest.newBuilder().uri(uri).GET().build();
      response = client.send(request, r -> BodySubscribers.ofString(StandardCharsets.UTF_8));

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), ExpectedResponse);
    }
  }

  @Test(dataProvider = "schemes")
  public void simplePost(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      System.out.println("Handling");
      assertEquals(req.getHeader(Headers.ContentType), "application/json"); // Mixed case

      try {
        System.out.println("Reading");
        byte[] body = req.getInputStream().readAllBytes();
        assertEquals(new String(body), RequestBody);
      } catch (IOException e) {
        fail("Unable to parse body", e);
      }

      System.out.println("Done");
      res.setHeader(Headers.ContentType, "text/plain");
      res.setHeader("Content-Length", "16");
      res.setStatus(200);

      try {
        System.out.println("Writing");
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      var client = makeClient(scheme, null);
      URI uri = makeURI(scheme, "?foo=bar");
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "application/json").POST(BodyPublishers.ofString(RequestBody)).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), ExpectedResponse);
    }
  }

  @Test(dataProvider = "schemes")
  public void statusOnly(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> res.setStatus(200);

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "application/json").POST(BodyPublishers.ofString(RequestBody)).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
    }
  }

  @Test(dataProvider = "schemes")
  public void writer(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      try {
        req.getInputStream().readAllBytes();

        res.setHeader(Headers.ContentType, "text/plain; charset=UTF-16");
        res.setHeader("Content-Length", "" + ExpectedResponse.getBytes(StandardCharsets.UTF_16).length); // Recalculate the byte length using UTF-16
        res.setStatus(200);

        Writer writer = res.getWriter();
        writer.write(ExpectedResponse);
        writer.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      URI uri = makeURI(scheme, "");
      var client = makeClient(scheme, null);
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(Headers.ContentType, "application/json").POST(BodyPublishers.ofString(RequestBody)).build(),
          r -> BodySubscribers.ofString(StandardCharsets.UTF_16)
      );

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), ExpectedResponse);
    }
  }

  private void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      // Ignore
    }
  }
}