package com.ruoyi.club.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Server-only signing. Sign the exact serialized bytes sent to WeChat, without reserialization. */
public final class ClubVirtualPaySigner
{
    private ClubVirtualPaySigner() { }

    public static String paymentSignature(String appKey, String uri, String body)
    {
        if (uri == null || !("requestVirtualPayment".equals(uri) || uri.matches("/xpay/[a-z_]+")))
            throw new IllegalArgumentException("Invalid virtual payment signing path");
        if (body == null || body.isEmpty()) throw new IllegalArgumentException("Missing signing body");
        return hmac(appKey, uri + "&" + body);
    }

    public static String userSignature(String sessionKey, String body)
    {
        if (body == null || body.isEmpty()) throw new IllegalArgumentException("Missing signing body");
        return hmac(sessionKey, body);
    }

    private static String hmac(String key, String message)
    {
        if (key == null || key.trim().isEmpty()) throw new IllegalArgumentException("Missing signing credential");
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[digest.length * 2];
            char[] alphabet = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++)
            {
                hex[i * 2] = alphabet[(digest[i] & 0xff) >>> 4];
                hex[i * 2 + 1] = alphabet[digest[i] & 0x0f];
            }
            return new String(hex);
        }
        catch (GeneralSecurityException exception)
        {
            throw new IllegalStateException("Virtual payment signing unavailable");
        }
    }
}
