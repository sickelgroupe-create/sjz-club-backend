package com.ruoyi.club.service;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.hamcrest.Matchers.containsString;

class ClubVirtualPayClientTest
{
    private ClubVirtualPayClient client;
    private MockRestServiceServer server;
    @BeforeEach void setUp()
    {
        ClubMiniProgramCodeService tokens=mock(ClubMiniProgramCodeService.class);
        when(tokens.accessToken()).thenReturn("unit-test-token");
        client=new ClubVirtualPayClient(tokens);
        ReflectionTestUtils.setField(client,"enabled",true);
        ReflectionTestUtils.setField(client,"environment",1);
        ReflectionTestUtils.setField(client,"offerId","12345");
        ReflectionTestUtils.setField(client,"sandboxKey","01234567890123456789012345678901");
        ReflectionTestUtils.setField(client,"liveKey","abcdefghijklmnopqrstuvwxyzABCDEF");
        ReflectionTestUtils.setField(client,"appId","wx-unit-test");
        ReflectionTestUtils.setField(client,"appSecret","unit-test-secret");
        server=MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(client,"rest")).build();
    }
    @Test void disabledByDefaultFailsClosed()
    {
        ReflectionTestUtils.setField(client,"enabled",false);
        assertThrows(ServiceException.class,()->client.query("openid","PAY123456",1));
        server.verify();
    }
    @Test void signedQueryUsesStoredEnvironmentAndExactBody()
    {
        String body="{\"openid\":\"openid\",\"order_id\":\"PAY123456\",\"env\":1}";
        String sig=ClubVirtualPaySigner.paymentSignature("01234567890123456789012345678901","/xpay/query_order",body);
        server.expect(requestTo(containsString("pay_sig="+sig))).andExpect(content().string(body))
            .andRespond(withSuccess("{\"errcode\":0,\"order\":{\"order_id\":\"PAY123456\",\"env_type\":2,\"status\":2}}",MediaType.APPLICATION_JSON));
        assertEquals(2,client.query("openid","PAY123456",1).getIntValue("status"));server.verify();
    }
    @Test void rejectsCrossEnvironmentOrder()
    {
        server.expect(anything()).andRespond(withSuccess("{\"errcode\":0,\"order\":{\"order_id\":\"PAY123456\",\"env_type\":1,\"status\":2}}",MediaType.APPLICATION_JSON));
        assertThrows(ServiceException.class,()->client.query("openid","PAY123456",1));server.verify();
    }
    @Test void rejectsOtherWechatIdentityBeforeSigning()
    {
        server.expect(requestTo(containsString("/sns/jscode2session"))).andRespond(withSuccess("{\"openid\":\"other-person\",\"session_key\":\"private-session\"}",MediaType.APPLICATION_JSON));
        assertThrows(ServiceException.class,()->client.prepareGoods("owner","abcdefghijk","PAY123456","sku_1",9900,1,9900));server.verify();
    }
    @Test void neverReturnsSessionOrAppKey()
    {
        server.expect(anything()).andRespond(withSuccess("{\"openid\":\"owner\",\"session_key\":\"private-session\"}",MediaType.APPLICATION_JSON));
        String response=JSONObject.toJSONString(client.prepareGoods("owner","abcdefghijk","PAY123456","sku_1",9900,1,9900));
        assertFalse(response.contains("private-session"));assertFalse(response.contains("01234567890123456789012345678901"));server.verify();
    }
    @Test void appleRefundIsNotSubmittedAsOrdinaryRefund()
    {
        server.expect(anything()).andRespond(withSuccess("{\"errcode\":0,\"order\":{\"order_id\":\"PAY123456\",\"env_type\":2,\"status\":4,\"order_type\":7,\"left_fee\":9900}}",MediaType.APPLICATION_JSON));
        ServiceException error=assertThrows(ServiceException.class,()->client.startRefund("owner","PAY123456","REF123456",9900,1));
        assertTrue(error.getMessage().contains("Apple"));server.verify();
    }
    @Test void rejectsNegativeAmountsOverflowAndInvalidIds()
    {
        assertThrows(ServiceException.class,()->ClubVirtualPayClient.validateGoods("short","sku",1,1,1));
        assertThrows(ServiceException.class,()->ClubVirtualPayClient.validateGoods("PAY123456","sku",1,1,0));
        assertThrows(ServiceException.class,()->ClubVirtualPayClient.validateGoods("PAY123456","sku",Integer.MAX_VALUE,2,1));
        assertThrows(ServiceException.class,()->ClubVirtualPayClient.validateGoods("PAY123456","sku",1,1,2));
    }
}
