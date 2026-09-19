package com.ruoyi.club.service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ClubWechatMessageServiceTest
{
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ClubVirtualPayGateway gateway=mock(ClubVirtualPayGateway.class);
    private final ClubAfterSaleService afterSale=mock(ClubAfterSaleService.class);
    private final ClubAppleRefundService apple=mock(ClubAppleRefundService.class);
    private final ClubWechatMessageService service=new ClubWechatMessageService(jdbc,mock(ClubAppService.class),gateway,afterSale,apple);
    private final JSONObject event=new JSONObject();
    private final JSONObject original=new JSONObject();
    @BeforeEach void setup() {
        when(gateway.enabled()).thenReturn(true);
        Map<String,Object> payment=new HashMap<>();payment.put("openid","open-7");
        // No local refund_no or local application: this used to reject all Apple callbacks.
        when(jdbc.queryForList(anyString(),eq("VP00000001"))).thenReturn(Collections.singletonList(payment));
        event.put("Event","xpay_refund_notify");event.put("MchOrderId","VP00000001");event.put("OpenId","open-7");
        event.put("RetCode",0);event.put("MchRefundId","APPLE-R1");event.put("WxRefundId","wx-refund-1");event.put("WxOrderId","wx-1");event.put("RefundFee",9900L);
        original.put("order_type",7);original.put("wx_order_id","wx-1");when(gateway.remote("VP00000001")).thenReturn(original);
    }
    @Test void appleCallbackUsesExternalRefundBoundaryWithoutPriorAdminApproval() {
        assertNull(service.handle(event));
        verify(apple).confirmNotification("VP00000001","APPLE-R1","wx-refund-1",9900L,"wx-1");
        verifyNoInteractions(afterSale);
    }
    @Test void ordinaryRefundWithoutLocalApplicationStillRejected() {
        original.put("order_type",0);assertThrows(IllegalStateException.class,()->service.handle(event));
        verifyNoInteractions(apple,afterSale);
    }
    @Test void wrongOpenidRejectedBeforeGatewayQuery() {
        event.put("OpenId","other");assertThrows(IllegalArgumentException.class,()->service.handle(event));
        verify(gateway,never()).remote(anyString());verifyNoInteractions(apple,afterSale);
    }
    @Test void sandboxCallbackCannotReverseLivePayment() {
        event.put("Env",1);assertThrows(IllegalArgumentException.class,()->service.handle(event));verifyNoInteractions(apple,afterSale);
    }
    @Test void failedOrMismatchedCallbackIsNotAcknowledged() {
        event.put("RetCode",1);assertThrows(IllegalStateException.class,()->service.handle(event));
        event.put("RetCode",0);event.put("WxOrderId","unrelated");assertThrows(IllegalStateException.class,()->service.handle(event));
        verifyNoInteractions(apple,afterSale);
    }
}
