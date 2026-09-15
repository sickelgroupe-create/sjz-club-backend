package com.ruoyi.club.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.Amount;
import com.wechat.pay.java.service.payments.jsapi.model.CloseOrderRequest;
import com.wechat.pay.java.service.payments.jsapi.model.Payer;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayWithRequestPaymentResponse;
import com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.RefundNotification;

/** The only component allowed to handle WeChat Pay credentials and API v3 messages. */
@Service
public class ClubWechatPayService
{
    @org.springframework.beans.factory.annotation.Autowired
    private ClubVirtualPayGateway virtualPay;

    public boolean virtualEnabled() { return virtualPay != null && virtualPay.enabled(); }
    public boolean applePayment(String no)
    {
        return ClubVirtualPayGateway.owns(no) && Integer.valueOf(7).equals(virtualPay.remote(no).getInteger("order_type"));
    }
    public Map<String,Object> virtualPrepay(String no, Map<String,Object> order, String openid, String code)
    {
        if (!virtualEnabled()) throw new ServiceException("虚拟支付尚未启用");
        return virtualPay.prepare(no, order, openid, code);
    }
    @Value("${club.payment-mode:disabled}") private String paymentMode;
    @Value("${club.wechat-app-id:}") private String appId;
    @Value("${club.wechat-pay-mch-id:}") private String merchantId;
    @Value("${club.wechat-pay-api-v3-key:}") private String apiV3Key;
    @Value("${club.wechat-pay-merchant-serial:}") private String merchantSerial;
    @Value("${club.wechat-pay-private-key-path:}") private String privateKeyPath;
    @Value("${club.wechat-pay-public-key-path:}") private String publicKeyPath;
    @Value("${club.wechat-pay-public-key-id:}") private String publicKeyId;
    @Value("${club.wechat-pay-notify-url:}") private String notifyUrl;
    @Value("${club.wechat-pay-refund-notify-url:}") private String refundNotifyUrl;

    private volatile RSAPublicKeyConfig config;
    private volatile JsapiServiceExtension jsapi;
    private volatile RefundService refunds;

    public boolean enabled() { return "wechat".equalsIgnoreCase(paymentMode); }

    private synchronized void initialize()
    {
        if (jsapi != null) return;
        if (!enabled()) throw new ServiceException("真实微信支付尚未开启");
        if (blank(appId) || blank(merchantId) || blank(apiV3Key) || blank(merchantSerial) || blank(privateKeyPath) ||
                blank(publicKeyPath) || blank(publicKeyId) || blank(notifyUrl) || blank(refundNotifyUrl))
            throw new ServiceException("微信支付服务器配置不完整");
        config = new RSAPublicKeyConfig.Builder().merchantId(merchantId.trim())
                .privateKeyFromPath(privateKeyPath.trim()).merchantSerialNumber(merchantSerial.trim())
                .publicKeyFromPath(publicKeyPath.trim()).publicKeyId(publicKeyId.trim())
                .apiV3Key(apiV3Key.trim()).build();
        jsapi = new JsapiServiceExtension.Builder().config(config).signType("RSA").build();
        refunds = new RefundService.Builder().config(config).build();
    }

    public Map<String, Object> prepay(String paymentNo, String description, BigDecimal yuan, String openid, OffsetDateTime expiresAt)
    {
        initialize();
        PrepayRequest request = new PrepayRequest();
        request.setAppid(appId.trim()); request.setMchid(merchantId.trim());
        request.setDescription(description.length() > 127 ? description.substring(0, 127) : description);
        request.setOutTradeNo(paymentNo); request.setNotifyUrl(notifyUrl.trim());
        request.setTimeExpire(expiresAt.withOffsetSameInstant(ZoneOffset.ofHours(8)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        Amount amount = new Amount(); amount.setTotal(cents(yuan)); amount.setCurrency("CNY"); request.setAmount(amount);
        Payer payer = new Payer(); payer.setOpenid(openid); request.setPayer(payer);
        PrepayWithRequestPaymentResponse response = jsapi.prepayWithRequestPayment(request);
        Map<String, Object> result = new HashMap<>();
        result.put("timeStamp", response.getTimeStamp()); result.put("nonceStr", response.getNonceStr());
        result.put("package", response.getPackageVal()); result.put("signType", response.getSignType()); result.put("paySign", response.getPaySign());
        return result;
    }

    public Transaction parseNotification(String serial, String nonce, String signature, String timestamp, String body)
    {
        initialize();
        RequestParam param = new RequestParam.Builder().serialNumber(serial).nonce(nonce).signature(signature).timestamp(timestamp).body(body).build();
        return new NotificationParser(config).parse(param, Transaction.class);
    }

    public RefundNotification parseRefundNotification(String serial, String nonce, String signature, String timestamp, String body)
    {
        initialize();
        RequestParam param = new RequestParam.Builder().serialNumber(serial).nonce(nonce).signature(signature).timestamp(timestamp).body(body).build();
        return new NotificationParser(config).parse(param, RefundNotification.class);
    }

    public Refund refund(String paymentNo, String refundNo, BigDecimal amount, String reason)
    {
        if (ClubVirtualPayGateway.owns(paymentNo)) return virtualPay.refund(paymentNo, refundNo, amount);
        initialize();
        CreateRequest request = new CreateRequest();
        request.setOutTradeNo(paymentNo); request.setOutRefundNo(refundNo);
        request.setReason(reason.length() > 80 ? reason.substring(0, 80) : reason);
        request.setNotifyUrl(refundNotifyUrl.trim());
        AmountReq money = new AmountReq();
        long cents = cents(amount); money.setTotal(cents); money.setRefund(cents); money.setCurrency("CNY"); request.setAmount(money);
        return refunds.create(request);
    }

    public Transaction query(String paymentNo)
    {
        if (ClubVirtualPayGateway.owns(paymentNo)) return virtualPay.query(paymentNo, appId, merchantId);
        initialize(); QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
        request.setMchid(merchantId.trim()); request.setOutTradeNo(paymentNo); return jsapi.queryOrderByOutTradeNo(request);
    }

    public void close(String paymentNo)
    {
        if (ClubVirtualPayGateway.owns(paymentNo)) throw new ServiceException("虚拟支付订单需要查单确认关闭，不能提前释放库存");
        initialize(); CloseOrderRequest request = new CloseOrderRequest(); request.setMchid(merchantId.trim()); request.setOutTradeNo(paymentNo); jsapi.closeOrder(request);
    }

    public void validateIdentity(Transaction tx)
    {
        if (!appId.trim().equals(tx.getAppid()) || !merchantId.trim().equals(tx.getMchid())) throw new ServiceException("微信支付回调商户身份不匹配");
    }

    public static int cents(BigDecimal yuan)
    {
        try { return yuan.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).intValueExact(); }
        catch (ArithmeticException e) { throw new ServiceException("支付金额精度或范围无效"); }
    }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
