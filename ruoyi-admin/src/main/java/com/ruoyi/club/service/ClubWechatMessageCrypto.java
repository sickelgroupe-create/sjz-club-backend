package com.ruoyi.club.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** WeChat message-push AES-256-CBC / PKCS7(32), not payment API-v3 AES-GCM. */
public final class ClubWechatMessageCrypto
{
    private final String token,appId;
    private final byte[] key;
    public ClubWechatMessageCrypto(String token,String encodingKey,String appId) {
        if(token==null || !token.matches("[A-Za-z0-9]{3,32}") || encodingKey==null || !encodingKey.matches("[A-Za-z0-9+/]{43}") || appId==null || !appId.matches("wx[A-Za-z0-9]{16}"))throw new IllegalArgumentException("Message configuration invalid");
        this.token=token;this.appId=appId;this.key=Base64.getDecoder().decode(encodingKey+"=");
        if(key.length!=32)throw new IllegalArgumentException("Message key invalid");
    }
    public String signature(String timestamp,String nonce,String encrypted) {
        try {
            if(timestamp==null || nonce==null || timestamp.length()>20 || nonce.length()>128)throw new IllegalArgumentException();
            String[] parts=encrypted==null?new String[]{token,timestamp,nonce}:new String[]{token,timestamp,nonce,encrypted};
            Arrays.sort(parts);byte[] digest=MessageDigest.getInstance("SHA-1").digest(String.join("",parts).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format("%02x",b&255));return hex.toString();
        }catch(Exception error){throw new IllegalArgumentException("Message signature invalid");}
    }
    public void verify(String supplied,String timestamp,String nonce,String encrypted) {
        if(supplied==null || !supplied.matches("[a-f0-9]{40}") || !MessageDigest.isEqual(signature(timestamp,nonce,encrypted).getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII)))throw new IllegalArgumentException("Message signature invalid");
    }
    public String decrypt(String encrypted) {
        try {
            if(encrypted==null || encrypted.length()>131072)throw new IllegalArgumentException();
            byte[] bytes=crypt(Cipher.DECRYPT_MODE,Base64.getDecoder().decode(encrypted));
            if(bytes.length<32)throw new IllegalArgumentException();
            int padding=bytes[bytes.length-1]&255;
            if(padding<1 || padding>32 || padding>bytes.length)throw new IllegalArgumentException();
            for(int i=bytes.length-padding;i<bytes.length;i++)if((bytes[i]&255)!=padding)throw new IllegalArgumentException();
            int limit=bytes.length-padding,length=ByteBuffer.wrap(bytes,16,4).getInt();
            if(length<0 || length>limit-20)throw new IllegalArgumentException();
            String receiver=new String(bytes,20+length,limit-20-length,StandardCharsets.UTF_8);
            if(!appId.equals(receiver))throw new IllegalArgumentException();
            return new String(bytes,20,length,StandardCharsets.UTF_8);
        }catch(Exception error){throw new IllegalArgumentException("Encrypted message invalid");}
    }
    public String encrypt(String message) {
        try {
            byte[] data=message.getBytes(StandardCharsets.UTF_8),app=appId.getBytes(StandardCharsets.UTF_8),random=new byte[16];
            if(data.length>65536)throw new IllegalArgumentException();
            new SecureRandom().nextBytes(random);int length=20+data.length+app.length,padding=32-length%32;
            ByteBuffer buf=ByteBuffer.allocate(length+padding);buf.put(random).putInt(data.length).put(data).put(app);
            for(int i=0;i<padding;i++)buf.put((byte)padding);
            return Base64.getEncoder().encodeToString(crypt(Cipher.ENCRYPT_MODE,buf.array()));
        }catch(Exception error){throw new IllegalArgumentException("Message encryption failed");}
    }
    private byte[] crypt(int mode,byte[] bytes)throws Exception {
        Cipher cipher=Cipher.getInstance("AES/CBC/NoPadding");cipher.init(mode,new SecretKeySpec(key,"AES"),new IvParameterSpec(Arrays.copyOf(key,16)));return cipher.doFinal(bytes);
    }
}
