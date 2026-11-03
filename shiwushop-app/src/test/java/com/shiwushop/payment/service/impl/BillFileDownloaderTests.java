package com.shiwushop.payment.service.impl;
import com.shiwushop.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.*;
import java.net.http.*;
import java.util.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BillFileDownloaderTests {
 @Test void rejectsUntrustedAddressWithoutNetwork() {
  HttpClient client=mock(HttpClient.class); var downloader=new BillFileDownloader(client);
  for(String url : List.of("http://dwbillcenter.alipay.com/x","https://dwbillcenter.alipay.com.evil.test/x",
       "https://user@dwbillcenter.alipay.com/x","https://dwbillcenter.alipay.com:444/x","https://127.0.0.1/x"))
   assertEquals(503,assertThrows(BusinessException.class,()->downloader.download(url)).getCode());
  verifyNoInteractions(client);
 }
 @Test void acceptsBoundedBodyAndRejectsRedirectOversizeAndErrorImages() throws Exception {
  HttpClient client=mock(HttpClient.class); var downloader=new BillFileDownloader(client);
  ReflectionTestUtils.setField(downloader,"maxBytes",4);
  @SuppressWarnings("unchecked") HttpResponse<byte[]> response=mock(HttpResponse.class);
  when(client.sendAsync(any(),any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(response));
  when(response.statusCode()).thenReturn(200);
  when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type",List.of("application/octet-stream")),(a,b)->true));
  when(response.body()).thenReturn(new byte[]{1,2,3,4});
  assertArrayEquals(new byte[]{1,2,3,4},downloader.download("https://dwbillcenter.alipay.com/file?signature=private"));
  when(response.body()).thenReturn(new byte[]{1,2,3,4,5});
  assertThrows(BusinessException.class,()->downloader.download("https://dwbillcenter.alipay.com/file"));
  when(response.statusCode()).thenReturn(302);
  when(response.body()).thenReturn(new byte[]{1});
  assertThrows(BusinessException.class,()->downloader.download("https://dwbillcenter.alipay.com/file"));
  when(response.statusCode()).thenReturn(200);
  when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type",List.of("image/png")),(a,b)->true));
  when(response.body()).thenReturn(new byte[]{1});
  assertThrows(BusinessException.class,()->downloader.download("https://dwbillcenter.alipay.com/file"));
 }
 @Test void stalledResponseBodyIsBoundedByTotalDeadline() throws Exception {
  CountDownLatch bodyStarted=new CountDownLatch(1), release=new CountDownLatch(1);
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/", exchange -> {
   exchange.sendResponseHeaders(200,0);
   try (var body=exchange.getResponseBody()) {
    body.write(1); body.flush(); bodyStarted.countDown();
    if (!release.await(5,TimeUnit.SECONDS)) throw new IOException("test server timed out");
   } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
   finally { exchange.close(); }
  });
  server.start();
  HttpClient delegate=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  HttpClient client=mock(HttpClient.class);
  when(client.sendAsync(any(),any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
   HttpRequest local=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"))
           .GET().build();
   return delegate.sendAsync(local,call.getArgument(1));
  });
  var downloader=new BillFileDownloader(client,Duration.ofSeconds(2));
  try (var worker=Executors.newSingleThreadExecutor()) {
   var result=worker.submit(() -> assertThrows(BusinessException.class,
           () -> downloader.download("https://dwbillcenter.alipay.com/file")));
   assertTrue(bodyStarted.await(5,TimeUnit.SECONDS));
   assertEquals(503,result.get(4,TimeUnit.SECONDS).getCode());
  } finally { release.countDown(); server.stop(0); delegate.close(); }
 }
 @Test void slowTrickleStillTimesOutAndOversizeBodyIsRejected() throws Exception {
  CountDownLatch started=new CountDownLatch(1), release=new CountDownLatch(1);
  AtomicInteger writes=new AtomicInteger();
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/slow", exchange -> {
   exchange.sendResponseHeaders(200,0);
   try (var body=exchange.getResponseBody()) {
    while (release.getCount()!=0) {
     body.write(1); body.flush(); writes.incrementAndGet(); started.countDown();
     if (release.await(100,TimeUnit.MILLISECONDS)) break;
    }
   } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
   catch (IOException disconnected) { /* Cancellation may close the test connection. */ }
   finally { exchange.close(); }
  });
  server.createContext("/large", exchange -> {
   exchange.sendResponseHeaders(200,5);
   try (var body=exchange.getResponseBody()) { body.write(new byte[]{1,2,3,4,5}); }
   finally { exchange.close(); }
  });
  server.start();
  HttpClient delegate=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  HttpClient client=mock(HttpClient.class);
  when(client.sendAsync(any(),any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
   HttpRequest original=call.getArgument(0);
   HttpRequest local=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+original.uri().getPath())).GET().build();
   return delegate.sendAsync(local,call.getArgument(1));
  });
  try (var worker=Executors.newSingleThreadExecutor()) {
   var downloader=new BillFileDownloader(client,Duration.ofSeconds(2));
   var result=worker.submit(() -> assertThrows(BusinessException.class,()->downloader.download("https://dwbillcenter.alipay.com/slow")));
   assertTrue(started.await(5,TimeUnit.SECONDS));
   assertEquals(503,result.get(4,TimeUnit.SECONDS).getCode());
   assertTrue(writes.get()>1,"test must deliver multiple body chunks");
   release.countDown();
   ReflectionTestUtils.setField(downloader,"maxBytes",4);
   assertEquals(503,assertThrows(BusinessException.class,()->downloader.download("https://dwbillcenter.alipay.com/large")).getCode());
  } finally { release.countDown(); server.stop(0); delegate.close(); }
 }

}
