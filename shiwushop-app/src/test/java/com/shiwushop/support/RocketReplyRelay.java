package com.shiwushop.support;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/** 本地 TCP 故障夹具：请求仍转发，响应被拦住后断连，制造真实未知发送结果。 */
public final class RocketReplyRelay implements AutoCloseable {
    final ServerSocket server=new ServerSocket(28181,20,InetAddress.getLoopbackAddress());
    final ExecutorService threads=Executors.newCachedThreadPool();
    final java.util.List<Socket> sockets=new CopyOnWriteArrayList<>();
    volatile boolean blockReplies;
    public RocketReplyRelay() throws IOException {
        threads.submit(()->{
            try {
                while(!server.isClosed()) {
                    Socket client=server.accept(), upstream=new Socket("127.0.0.1",28081);
                    sockets.add(client);sockets.add(upstream);
                    threads.submit(()->copy(client,upstream,false));threads.submit(()->copy(upstream,client,true));
                }
            } catch(IOException closed) {if(!server.isClosed())throw new UncheckedIOException(closed);}
        });
    }
    void copy(Socket source,Socket target,boolean reply) {
        try {
            byte[] buffer=new byte[65536];int count;
            while((count=source.getInputStream().read(buffer))!=-1) {
                while(reply && blockReplies && !source.isClosed()) Thread.sleep(10);
                target.getOutputStream().write(buffer,0,count);
            }
        } catch(IOException disconnected) { /* fault injection intentionally closes both sockets */ }
        catch(InterruptedException stopped) {Thread.currentThread().interrupt();}
    }
    public void loseReplies() {blockReplies=true;}
    public void reconnect() throws IOException {
        for(var socket:sockets) socket.close();
        sockets.clear();blockReplies=false;
    }
    @Override public void close() throws IOException {server.close();reconnect();threads.shutdownNow();}
}
