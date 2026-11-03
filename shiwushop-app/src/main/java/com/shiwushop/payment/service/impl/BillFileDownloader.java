package com.shiwushop.payment.service.impl;

import com.shiwushop.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class BillFileDownloader {
 private final HttpClient client;
 private final Duration timeout;
 public BillFileDownloader() { this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
         .connectTimeout(Duration.ofSeconds(5)).build(),Duration.ofSeconds(15)); }
 BillFileDownloader(HttpClient client) { this(client,Duration.ofSeconds(15)); }
 BillFileDownloader(HttpClient client,Duration timeout) { this.client=client; this.timeout=timeout; }
 @Value("${shop.payment.bill-max-bytes:10485760}") private int maxBytes=10485760;
 /** 只访问已证实的正式 HTTPS 主机，限制完整响应时间和字节数。 */
 public byte[] download(String url) {
  URI uri;
  try { uri=URI.create(url); }
  catch (IllegalArgumentException e) { throw new BusinessException(503,"账单下载地址无效",e); }
  if (!"https".equalsIgnoreCase(uri.getScheme()) || !"dwbillcenter.alipay.com".equalsIgnoreCase(uri.getHost())
      || (uri.getPort()!=-1 && uri.getPort()!=443) || uri.getUserInfo()!=null || uri.getFragment()!=null)
   throw new BusinessException(503,"账单下载地址不受信");
  if (maxBytes<1 || maxBytes>10485760) throw new IllegalStateException("账单大小上限必须介于1和10485760字节");
  HttpRequest request=HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
  CompletableFuture<HttpResponse<byte[]>> pending=client.sendAsync(request, info -> new LimitedBodySubscriber(maxBytes));
  try {
   HttpResponse<byte[]> response=pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
   String contentType=response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
   if (response.statusCode()!=200 || contentType.startsWith("text/html") || contentType.startsWith("image/"))
    throw new BusinessException(503,"账单下载失败");
   byte[] data=response.body();
   if (data.length==0 || data.length>maxBytes) throw new BusinessException(503,"账单内容大小无效");
   return data;
  } catch (TimeoutException e) {
   throw new BusinessException(503,"账单下载超时",e);
  } catch (InterruptedException e) {
   Thread.currentThread().interrupt(); throw new BusinessException(503,"账单下载中断",e);
  } catch (ExecutionException e) {
   throw new BusinessException(503,"账单下载失败",e);
  } finally {
   if (!pending.isDone()) pending.cancel(true);
  }
 }

 /** 超过上限立即取消订阅，避免无界缓存渠道正文。 */
 private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
  private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
  private final int limit;
  private Flow.Subscription subscription;
  private int received;
  private boolean done;
  LimitedBodySubscriber(int limit) { this.limit=limit; }
  @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
  @Override public void onSubscribe(Flow.Subscription subscription) {
   this.subscription=subscription; delegate.onSubscribe(subscription);
  }
  @Override public void onNext(List<ByteBuffer> buffers) {
   if (done) return;
   for (ByteBuffer buffer : buffers) {
    if (buffer.remaining()>limit-received) {
     done=true; subscription.cancel(); delegate.onError(new IOException("账单内容超过大小上限")); return;
    }
    received+=buffer.remaining();
   }
   delegate.onNext(buffers);
  }
  @Override public void onError(Throwable error) { if (!done) { done=true; delegate.onError(error); } }
  @Override public void onComplete() { if (!done) { done=true; delegate.onComplete(); } }
 }
}
