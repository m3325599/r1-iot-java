package huan.diy.r1iot.free;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Sets;
import huan.diy.r1iot.direct.AIDirect;
import huan.diy.r1iot.direct.AiAssistant;
import huan.diy.r1iot.model.R1GlobalConfig;
import huan.diy.r1iot.util.R1IotUtils;
import huan.diy.r1iot.util.TcpChannelUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/trafficRouter")
public class TransparentProxyController {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AIDirect aiDirect;

    @Autowired
    private R1GlobalConfig globalConfig;

    private static final PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();

    private static final Set<String> WHITE_HEADERS;
    private static final Set<String> RESP_WHITE_HEADERS = new HashSet<>();

    static {
        connectionManager.setMaxTotal(100);
        connectionManager.setDefaultMaxPerRoute(20);
        WHITE_HEADERS = new HashSet<>();
        Arrays.asList(
                "ci, cryp, i, k, p, dt, remote-addr, ui, http-client-ip, t, u, host, connection, content-type, tp, sp, accept-encoding, user-agent"
                        .split(","))
                .forEach(s -> WHITE_HEADERS.add(s.trim()));
    }

    private static final CloseableHttpClient httpClient = HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(RequestConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(5))
                    .setResponseTimeout(Timeout.ofSeconds(30))
                    .build())
            .setRetryStrategy(new DefaultHttpRequestRetryStrategy(3, Timeout.ofSeconds(1)))
            .build();

    private static final Cache<String, String> SID_DEVICE_CACHE = CacheBuilder.newBuilder()
            .expireAfterWrite(50, TimeUnit.SECONDS)
            .maximumSize(200)
            .build();

    private final Pattern SID_PATTERN = Pattern.compile("\\[(.*?)\\]");

    private Map<String, StringBuffer> ASR_MAP = new ConcurrentHashMap<>();
    private Map<String, String> DEVICE_IP = new ConcurrentHashMap<>();

    @RequestMapping(value = "/{path:.*}", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> catchAllProxy(HttpServletRequest request, @PathVariable String path) {
        if ("cs".equals(path) && "POST".equalsIgnoreCase(request.getMethod())) {
            return proxyRequest(request);
        }
        
        log.info("Generic Proxying Path: {}, Method: {}", path, request.getMethod());
        
        String targetUrl = "http://" + TcpChannelUtils.REMOTE_HOST + "/trafficRouter/" + path;
        String queryString = request.getQueryString();
        if (StringUtils.hasLength(queryString)) {
            targetUrl += "?" + queryString;
        }

        HttpUriRequestBase proxyRequest;
        if ("POST".equalsIgnoreCase(request.getMethod())) {
            proxyRequest = new HttpPost(targetUrl);
            try {
                InputStream inputStream = request.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                inputStream.transferTo(baos);
                String contentTypeHeader = request.getContentType();
                ContentType contentType = contentTypeHeader != null ? ContentType.parse(contentTypeHeader) : ContentType.APPLICATION_OCTET_STREAM;
                ((HttpPost) proxyRequest).setEntity(new ByteArrayEntity(baos.toByteArray(), contentType));
            } catch (Exception e) {
                log.error("Failed to read POST body for generic proxy", e);
            }
        } else {
            proxyRequest = new HttpGet(targetUrl);
        }

        copyHeaders(request, proxyRequest);

        try (CloseableHttpResponse proxyResponse = httpClient.execute(proxyRequest)) {
            HttpEntity proxyEntity = proxyResponse.getEntity();
            byte[] body = proxyEntity != null ? proxyEntity.getContent().readAllBytes() : new byte[0];
            
            HttpHeaders responseHeaders = new HttpHeaders();
            for (Header header : proxyResponse.getHeaders()) {
                if (!header.getName().equalsIgnoreCase("Content-Length")) {
                    responseHeaders.add(header.getName(), header.getValue());
                }
            }
            byte[] fixedBody = R1IotUtils.fixHost(new String(body, StandardCharsets.UTF_8), globalConfig.getHostIp()).getBytes(StandardCharsets.UTF_8);
            responseHeaders.setContentLength(fixedBody.length);
            
            return ResponseEntity.status(proxyResponse.getCode()).headers(responseHeaders).body(fixedBody);
        } catch (Exception e) {
            log.error("Generic proxy error for path: " + path, e);
            return ResponseEntity.status(500).body(("Proxy Error: " + e.getMessage()).getBytes());
        }
    }

    private void copyHeaders(HttpServletRequest request, HttpUriRequestBase proxyRequest) {
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (!WHITE_HEADERS.contains(headerName.toLowerCase())) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(headerName);
            while (values.hasMoreElements()) {
                String value = values.nextElement();
                if (headerName.equalsIgnoreCase("host")) {
                    String hostIp = globalConfig.getHostIp();
                    if (StringUtils.hasLength(hostIp)) {
                        String host = hostIp.replace("http://", "").replace("https://", "");
                        proxyRequest.setHeader("Host", host);
                    } else {
                        proxyRequest.setHeader("Host", "127.0.0.1:18888");
                    }
                } else {
                    proxyRequest.addHeader(headerName, value);
                }
            }
        }
    }

    public ResponseEntity<byte[]> proxyRequest(HttpServletRequest request) {
        try (
                InputStream inputStream = request.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream()
        ) {
            inputStream.transferTo(baos);
            byte[] requestBody = baos.toByteArray();
            HttpPost proxyPost = new HttpPost("http://" + TcpChannelUtils.REMOTE_HOST + "/trafficRouter/cs");

            String contentTypeHeader = request.getContentType();
            ContentType contentType = contentTypeHeader != null ? ContentType.parse(contentTypeHeader) : ContentType.APPLICATION_OCTET_STREAM;

            proxyPost.setEntity(new ByteArrayEntity(requestBody, contentType));

            String deviceId = request.getHeader("UI");
            String sidWrapper = request.getHeader("P");
            if (deviceId != null) {
                R1IotUtils.setCurrentDeviceId(deviceId);
                String clientIp = request.getRemoteAddr();
                DEVICE_IP.put(deviceId, clientIp);
                if (!R1IotUtils.getDeviceMap().containsKey(deviceId)) {
                    throw new RuntimeException("device not found: " + deviceId);
                }
                Matcher matcher = SID_PATTERN.matcher(sidWrapper);
                while (matcher.find()) {
                    SID_DEVICE_CACHE.put(matcher.group(1), deviceId);
                }
            }

            copyHeaders(request, proxyPost);

            try (CloseableHttpResponse proxyResponse = httpClient.execute(proxyPost)) {
                HttpEntity proxyEntity = proxyResponse.getEntity();
                byte[] body = proxyEntity != null ? proxyEntity.getContent().readAllBytes() : new byte[0];
                JsonNode jsonNode = objectMapper.readTree(body);
                String sid = "";

                for (Header header : proxyResponse.getHeaders()) {
                    if (header.getName().equalsIgnoreCase("SID")) {
                        sid = header.getValue();
                    }
                }

                if (!jsonNode.isEmpty()) {
                    if (!jsonNode.has("asr_recongize")) {
                        ASR_MAP.put(sid, new StringBuffer());
                    } else {
                        ASR_MAP.computeIfAbsent(sid, k -> new StringBuffer())
                                .append(jsonNode.get("asr_recongize").asText());
                    }
                }

                if (!jsonNode.has("responseId")) {
                    Thread.sleep(200);
                    HttpHeaders responseHeaders = new HttpHeaders();
                    for (Header header : proxyResponse.getHeaders()) {
                        if (!header.getName().equalsIgnoreCase("Content-Length")) {
                            responseHeaders.add(header.getName(), header.getValue());
                        }
                    }
                    byte[] fixedBody = R1IotUtils.fixHost(new String(body, StandardCharsets.UTF_8), globalConfig.getHostIp()).getBytes(StandardCharsets.UTF_8);
                    responseHeaders.setContentLength(fixedBody.length);
                    return ResponseEntity.status(proxyResponse.getCode()).headers(responseHeaders).body(fixedBody);
                }

                log.info("\n==== FROM R1 ====\n {}", jsonNode);
                try {
                    String storeDeviceId = SID_DEVICE_CACHE.get(sid, () -> null);
                    String asrResult = ASR_MAP.get(sid).toString();
                    R1IotUtils.JSON_RET.set(jsonNode);
                    R1IotUtils.CLIENT_IP.set(DEVICE_IP.get(storeDeviceId));
                    
                    AiAssistant assistant = aiDirect.getAssistants().get(storeDeviceId);
                    String answer = assistant.chat(asrResult);
                    JsonNode fixedJsonNode = R1IotUtils.JSON_RET.get();
                    if (answer != null) {
                        fixedJsonNode = R1IotUtils.sampleChatResp(answer);
                    }

                    String responseString = objectMapper.writeValueAsString(fixedJsonNode);
                    log.info("\n==== FROM AI ====\n {}", responseString);

                    HttpHeaders responseHeaders = new HttpHeaders();
                    for (Header header : proxyResponse.getHeaders()) {
                        if (!header.getName().equalsIgnoreCase("Content-Length")) {
                            responseHeaders.add(header.getName(), header.getValue());
                        }
                    }
                    byte[] binary = R1IotUtils.fixHost(responseString, globalConfig.getHostIp()).getBytes(StandardCharsets.UTF_8);
                    responseHeaders.setContentLength(binary.length);

                    return ResponseEntity.status(proxyResponse.getCode()).headers(responseHeaders).body(binary);
                } catch (Exception e) {
                    log.error("\n！！！！ 位于 AI 代理执行环节发生严重异常 ！！！！\n", e);
                    return ResponseEntity.status(500).body(("Proxy Error: " + e.getMessage()).getBytes());
                } finally {
                    R1IotUtils.remove();
                }
            }
        } catch (Exception e) {
            log.error("Proxy overall error", e);
            return ResponseEntity.status(500).body(("Proxy Error: " + e.getMessage()).getBytes());
        }
    }
}