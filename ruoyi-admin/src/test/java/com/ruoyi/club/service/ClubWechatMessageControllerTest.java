package com.ruoyi.club.service;
import com.ruoyi.club.web.ClubWechatMessageController;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
class ClubWechatMessageControllerTest {
    private final ClubWechatMessageCrypto crypto=new ClubWechatMessageCrypto("AAAAA","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","wxba5fad812f8e6fb9");
    private ClubWechatMessageController controller(){ClubWechatMessageController c=new ClubWechatMessageController();ReflectionTestUtils.setField(c,"token","AAAAA");ReflectionTestUtils.setField(c,"aesKey","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");ReflectionTestUtils.setField(c,"appId","wxba5fad812f8e6fb9");return c;}
    @Test void platformVerificationEcho(){assertEquals("echo",controller().verify(crypto.signature("1","2",null),"1","2","echo").getBody());}
    @Test void invalidVerificationRejected(){assertEquals(403,controller().verify("bad","1","2","echo").getStatusCodeValue());}
    @Test void debugMessageAccepted(){String time=Long.toString(System.currentTimeMillis()/1000);String encrypted=crypto.encrypt("{\"Event\":\"debug_demo\"}");JSONObject body=new JSONObject();body.put("Encrypt",encrypted);assertEquals(200,controller().receive(crypto.signature(time,"2",encrypted),time,"2",body.toJSONString()).getStatusCodeValue());}
    @Test void unhandledRefundNotSilentlyAcknowledged(){String time=Long.toString(System.currentTimeMillis()/1000);String encrypted=crypto.encrypt("{\"Event\":\"xpay_refund_notify\"}");JSONObject body=new JSONObject();body.put("Encrypt",encrypted);assertEquals(503,controller().receive(crypto.signature(time,"2",encrypted),time,"2",body.toJSONString()).getStatusCodeValue());}
    @Test void plaintextRejected(){assertEquals(403,controller().receive("bad",Long.toString(System.currentTimeMillis()/1000),"2","{\"Event\":\"debug_demo\"}").getStatusCodeValue());}
}
