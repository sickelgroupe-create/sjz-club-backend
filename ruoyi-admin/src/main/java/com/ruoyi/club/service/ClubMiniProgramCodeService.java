package com.ruoyi.club.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** 仅在服务端调用微信接口生成真实可扫描的小程序码。 */
@Service
public class ClubMiniProgramCodeService
{
    @Value("${club.wechat-login-mode:disabled}")
    private String wechatMode;

    @Value("${club.wechat-app-id:}")
    private String appId;

    @Value("${club.wechat-app-secret:}")
    private String appSecret;

    private final RestTemplate rest = new RestTemplate();
    private String accessToken = "";
    private long tokenExpiresAt;

    public boolean isConfigured()
    {
        return "real".equalsIgnoreCase(wechatMode) && !appId.trim().isEmpty() && !appSecret.trim().isEmpty();
    }

    public String productCode(Long productId)
    {
        if (!isConfigured()) return "";
        try
        {
            String token = accessToken();
            URI uri = UriComponentsBuilder.fromHttpUrl("https://api.weixin.qq.com/wxa/getwxacodeunlimit")
                    .queryParam("access_token", token).build().encode().toUri();
            Map<String, Object> body = new HashMap<>();
            body.put("scene", "p=" + productId);
            body.put("page", "pages/product/detail");
            body.put("check_path", false);
            body.put("env_version", "trial");
            body.put("width", 280);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<byte[]> response = rest.exchange(uri, HttpMethod.POST,
                    new HttpEntity<>(JSON.toJSONString(body), headers), byte[].class);
            byte[] bytes = response.getBody();
            if (bytes == null || bytes.length < 100) throw new ServiceException("微信小程序码生成失败");
            MediaType contentType = response.getHeaders().getContentType();
            if (contentType != null && MediaType.APPLICATION_JSON.includes(contentType))
                throw new ServiceException("微信小程序码生成失败");
            String prefix = new String(bytes, 0, Math.min(bytes.length, 16), StandardCharsets.UTF_8).trim();
            if (prefix.startsWith("{")) throw new ServiceException("微信小程序码生成失败");
            Path directory = Paths.get(RuoYiConfig.getProfile(), "club-qrcode").toAbsolutePath().normalize();
            Files.createDirectories(directory);
            String fileName = "product-" + productId + "-" + System.currentTimeMillis() + ".png";
            Path target = directory.resolve(fileName).normalize();
            if (!target.startsWith(directory)) throw new ServiceException("小程序码保存路径不正确");
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
            return Constants.RESOURCE_PREFIX + "/club-qrcode/" + fileName;
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new ServiceException("微信小程序码服务暂时不可用，请稍后重试");
        }
    }

    @SuppressWarnings("unchecked")
    synchronized String accessToken()
    {
        long now = Instant.now().getEpochSecond();
        if (!accessToken.isEmpty() && tokenExpiresAt > now + 120) return accessToken;
        try
        {
            URI uri = UriComponentsBuilder.fromHttpUrl("https://api.weixin.qq.com/cgi-bin/token")
                    .queryParam("grant_type", "client_credential").queryParam("appid", appId)
                    .queryParam("secret", appSecret).build().encode().toUri();
            String payload = rest.getForObject(uri, String.class);
            Map<String, Object> result = JSON.parseObject(payload, Map.class);
            Object value = result == null ? null : result.get("access_token");
            if (value == null || String.valueOf(value).trim().isEmpty()) throw new ServiceException("微信小程序码服务鉴权失败");
            accessToken = String.valueOf(value);
            int expiresIn = result.get("expires_in") instanceof Number ? ((Number) result.get("expires_in")).intValue() : 7200;
            tokenExpiresAt = now + Math.max(300, expiresIn);
            return accessToken;
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new ServiceException("微信小程序码服务暂时不可用，请稍后重试");
        }
    }
}
