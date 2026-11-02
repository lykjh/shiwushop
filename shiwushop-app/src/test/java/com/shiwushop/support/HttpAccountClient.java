package com.shiwushop.support;

import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/** 接口和系统测试共用的真实 HTTP 客户端；Cookie 与 CSRF 均由服务器签发。 */
public final class HttpAccountClient implements AutoCloseable {
    private final String base;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private String csrf;
    public HttpAccountClient(String base) { this.base = base; }
    public HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    public void csrf() throws Exception {
        var response = get("/users/csrf");
        if (response.statusCode() != 200) throw new IllegalStateException("CSRF HTTP " + response.statusCode());
        csrf = json.readTree(response.body()).path("data").asString();
    }
    public String sessionId() {
        String value = cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("MALLSESSION")).findFirst().orElseThrow().getValue();
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
    public HttpResponse<String> post(String path, Map<String, Object> body) throws Exception {
        return post(path, body, true);
    }
    public HttpResponse<String> post(String path, Map<String, Object> body, boolean withCsrf) throws Exception {
        return request("POST", path, body, withCsrf, Map.of());
    }
    public HttpResponse<String> request(String method, String path, Object body) throws Exception {
        return request(method, path, body, true, Map.of());
    }
    public HttpResponse<String> request(String method, String path, Object body, boolean withCsrf,
                                        Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json");
        if (withCsrf && csrf != null) request.header("X-CSRF-TOKEN", csrf);
        headers.forEach(request::header);
        return http.send(request.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    public HttpResponse<String> form(String path, Map<String,String> form) throws Exception {
        String body=form.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="
                +URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        return http.send(HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    public HttpResponse<String> upload(String path, byte[] bytes, String filename, String mime) throws Exception {
        String boundary="shiwushop-http-boundary";
        var body=new java.io.ByteArrayOutputStream();
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+filename+"\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes); body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type","multipart/form-data; boundary="+boundary).header("X-CSRF-TOKEN",csrf)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Override public void close() { http.close(); }
}
