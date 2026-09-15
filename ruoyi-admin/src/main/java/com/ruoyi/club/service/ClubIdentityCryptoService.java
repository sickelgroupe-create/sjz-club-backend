package com.ruoyi.club.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;

/** Encrypts identity review material with an environment-only server key. */
@Service
public class ClubIdentityCryptoService
{
    private static final byte FORMAT_VERSION = 1;
    private final SecureRandom random = new SecureRandom();

    @Value("${club.identity-encryption-key:}")
    private String configuredKey;

    public String encrypt(String plainText)
    {
        if (plainText == null || plainText.isEmpty()) return "";
        try
        {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            ByteBuffer payload = ByteBuffer.allocate(1 + iv.length + encrypted.length);
            payload.put(FORMAT_VERSION).put(iv).put(encrypted);
            return Base64.getEncoder().encodeToString(payload.array());
        }
        catch (ServiceException e) { throw e; }
        catch (Exception e) { throw new ServiceException("实名认证材料加密失败，请稍后重试"); }
    }

    public String decrypt(String encoded)
    {
        if (encoded == null || encoded.trim().isEmpty()) return "";
        try
        {
            ByteBuffer payload = ByteBuffer.wrap(Base64.getDecoder().decode(encoded));
            if (payload.get() != FORMAT_VERSION) throw new ServiceException("实名认证材料版本不受支持");
            byte[] iv = new byte[12];
            payload.get(iv);
            byte[] encrypted = new byte[payload.remaining()];
            payload.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        }
        catch (ServiceException e) { throw e; }
        catch (Exception e) { throw new ServiceException("实名认证材料解密失败或已损坏"); }
    }

    private SecretKeySpec key() throws Exception
    {
        if (configuredKey == null || configuredKey.trim().length() < 32)
            throw new ServiceException("服务器未配置实名认证材料加密密钥");
        return new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(configuredKey.getBytes(StandardCharsets.UTF_8)), "AES");
    }
}
