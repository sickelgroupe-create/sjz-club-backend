package com.ruoyi.club.service;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ClubWechatMessageCryptoTest {
    private final ClubWechatMessageCrypto crypto=new ClubWechatMessageCrypto("AAAAA","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","wxba5fad812f8e6fb9");
    @Test void officialVerificationSignature(){assertEquals("f464b24fc39322e44b38aa78f5edd27bd1441696",crypto.signature("1714036504","1514711492",null));}
    @Test void officialEncryptedReply(){assertEquals("{\"demo_resp\":\"good luck\"}",crypto.decrypt("ELGduP2YcVatjqIS+eZbp80MNLoAUWvzzyJxgGzxZO/5sAvd070Bs6qrLARC9nVHm48Y4hyRbtzve1L32tmxSQ=="));}
    @Test void officialReplySignature(){assertEquals("1b9339964ed2e271e7c7b6ff2b0ef902fc94dea1",crypto.signature("1713424427","415670741","ELGduP2YcVatjqIS+eZbp80MNLoAUWvzzyJxgGzxZO/5sAvd070Bs6qrLARC9nVHm48Y4hyRbtzve1L32tmxSQ=="));}
    @Test void utf8RoundTrip(){String message="{\"event\":\"退款通知\"}";assertEquals(message,crypto.decrypt(crypto.encrypt(message)));}
    @Test void wrongAppRejected(){ClubWechatMessageCrypto other=new ClubWechatMessageCrypto("AAAAA","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","wx0000000000000000");assertThrows(IllegalArgumentException.class,()->other.decrypt(crypto.encrypt("hello")));}
    @Test void badSignatureRejected(){assertThrows(IllegalArgumentException.class,()->crypto.verify("0000000000000000000000000000000000000000","1","2",null));}
    @Test void malformedCiphertextRejected(){assertThrows(IllegalArgumentException.class,()->crypto.decrypt("abcd"));}
    @Test void randomIvUsed(){assertNotEquals(crypto.encrypt("hello"),crypto.encrypt("hello"));}
}
