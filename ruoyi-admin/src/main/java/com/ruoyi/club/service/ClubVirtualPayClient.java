package com.ruoyi.club.service;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Isolated transport only. Callers must persist and reconcile orders before granting service. */
@Service
public class ClubVirtualPayClient
{
    private final ClubMiniProgramCodeService tokens;
    private final RestTemplate rest;
    @Value("${VIRTUAL_PAYMENT_ENABLED:false}") private boolean enabled;
    @Value("${VIRTUAL_PAYMENT_ENV:1}") private int environment;
    @Value("${VIRTUAL_PAYMENT_OFFER_ID:}") private String offerId;
    @Value("${VIRTUAL_PAYMENT_SANDBOX_APP_KEY:}") private String sandboxKey;
    @Value("${VIRTUAL_PAYMENT_LIVE_APP_KEY:}") private String liveKey;
    @Value("${club.wechat-app-id:}") private String appId;
    @Value("${club.wechat-app-secret:}") private String appSecret;

    public ClubVirtualPayClient(ClubMiniProgramCodeService tokens)
    {
        this.tokens = tokens;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(10000);
        this.rest = new RestTemplate(factory);
    }

    public boolean enabled() { return enabled; }
    public int environment() { configured(environment); return environment; }

    private String configured(int env)
    {
        if (!enabled) throw new ServiceException("虚拟支付尚未启用");
        if (env != 0 && env != 1) throw new ServiceException("虚拟支付环境配置无效");
        String key = env == 1 ? sandboxKey : liveKey;
        if (offerId == null || !offerId.matches("[0-9]+") || key == null || !key.matches("[A-Za-z0-9]{32}"))
            throw new ServiceException("虚拟支付服务器配置不完整");
        return key;
    }

    /** code comes from a fresh native login; boundOpenId must come from the authenticated user's DB identity. */
    public Map<String, Object> prepareGoods(String boundOpenId, String code, String paymentNo,
            String goodsId, int unitPrice, int quantity, int actualUnitPrice)
    {
        String key = configured(environment);
        validateGoods(paymentNo, goodsId, unitPrice, quantity, actualUnitPrice);
        if (boundOpenId == null || boundOpenId.isEmpty() || code == null || !code.matches("[A-Za-z0-9_-]{8,256}"))
            throw new ServiceException("请在当前绑定的微信中重新发起支付");
        JSONObject session;
        try
        {
            URI uri = UriComponentsBuilder.fromHttpUrl("https://api.weixin.qq.com/sns/jscode2session")
                    .queryParam("appid", appId).queryParam("secret", appSecret).queryParam("js_code", code)
                    .queryParam("grant_type", "authorization_code").build().encode().toUri();
            session = JSON.parseObject(rest.getForObject(uri, String.class));
        }
        catch (Exception error) { throw new ServiceException("微信支付登录凭证校验失败，请重试"); }
        if (session == null || (session.containsKey("errcode") && session.getIntValue("errcode") != 0)
                || !boundOpenId.equals(session.getString("openid")) || session.getString("session_key") == null
                || session.getString("session_key").isEmpty())
            throw new ServiceException("付款微信与绑定账号不一致或登录已过期，请重新登录");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("offerId", offerId); data.put("buyQuantity", quantity); data.put("env", environment);
        data.put("currencyType", "CNY"); data.put("productId", goodsId); data.put("goodsPrice", unitPrice);
        if (actualUnitPrice != unitPrice) data.put("activitySellingPrice", actualUnitPrice);
        data.put("outTradeNo", paymentNo); data.put("attach", paymentNo);
        String body = JSON.toJSONString(data);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "short_series_goods"); result.put("signData", body);
        result.put("paySig", ClubVirtualPaySigner.paymentSignature(key, "requestVirtualPayment", body));
        result.put("signature", ClubVirtualPaySigner.userSignature(session.getString("session_key"), body));
        return result;
    }

    static void validateGoods(String paymentNo, String goodsId, int unitPrice, int quantity, int actualUnitPrice)
    {
        if (paymentNo == null || !paymentNo.matches("[A-Za-z0-9][A-Za-z0-9_|*@-]{7,31}")
                || goodsId == null || !goodsId.matches("[A-Za-z0-9_-]{1,20}")
                || unitPrice <= 0 || quantity <= 0 || actualUnitPrice <= 0 || actualUnitPrice > unitPrice
                || (long) unitPrice * quantity > Integer.MAX_VALUE)
            throw new ServiceException("虚拟支付商品、数量或金额无效");
    }

    /** env is pinned to the stored payment record, never supplied by an unauthenticated client. */
    public JSONObject query(String openid, String paymentNo, int env)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("openid", openid); data.put("order_id", paymentNo); data.put("env", env);
        JSONObject order = post("query_order", data, env).getJSONObject("order");
        if (order == null || !paymentNo.equals(order.getString("order_id")) || !order.containsKey("status")
                || !order.containsKey("env_type") || order.getIntValue("env_type") != (env == 0 ? 1 : 2))
            throw new ServiceException("虚拟支付查单信息不匹配，不能确认付款");
        return order;
    }

    /** Only starts a refund; success of this method is NOT a completed refund. */
    public JSONObject startRefund(String openid, String paymentNo, String refundNo, int cents, int env)
    {
        if (refundNo == null || !refundNo.matches("[A-Za-z0-9_-]{8,32}") || cents <= 0)
            throw new ServiceException("退款单号或金额无效");
        JSONObject order = query(openid, paymentNo, env);
        if (!order.containsKey("order_type")) throw new ServiceException("支付渠道缺失，不能发起退款");
        if (order.getIntValue("order_type") == 7)
            throw new ServiceException("苹果支付订单请由用户向Apple申请退款，后台不能直接原路退款");
        if (order.getIntValue("order_type") != 0 || !order.containsKey("left_fee")
                || cents > order.getIntValue("left_fee")) throw new ServiceException("订单渠道或可退款金额不匹配");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("openid", openid); data.put("order_id", paymentNo); data.put("refund_order_id", refundNo);
        data.put("left_fee", order.getIntValue("left_fee")); data.put("refund_fee", cents);
        data.put("biz_meta", refundNo); data.put("refund_reason", "3"); data.put("req_from", "1"); data.put("env", env);
        return post("refund_order", data, env);
    }

    public void confirmDelivery(String paymentNo, int env)
    {
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("order_id",paymentNo); data.put("env",env);
        post("notify_provide_goods",data,env);
    }

    private JSONObject post(String action, Map<String, Object> data, int env)
    {
        String key = configured(env);
        String body = JSON.toJSONString(data), path = "/xpay/" + action;
        try
        {
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl("https://api.weixin.qq.com" + path)
                    .queryParam("access_token", tokens.accessToken());
            if (!"notify_provide_goods".equals(action)) builder.queryParam("pay_sig", ClubVirtualPaySigner.paymentSignature(key, path, body));
            URI uri=builder.build().encode().toUri();
            HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
            JSONObject result = JSON.parseObject(rest.postForObject(uri, new HttpEntity<>(body, headers), String.class));
            if (result == null || !result.containsKey("errcode")) throw new ServiceException("虚拟支付服务返回不完整");
            if (result.getIntValue("errcode") != 0)
                throw new ServiceException("虚拟支付服务未受理，错误码：" + result.getIntValue("errcode"));
            return result;
        }
        catch (ServiceException error) { throw error; }
        catch (Exception error) { throw new ServiceException("虚拟支付服务暂时不可用，请稍后查询订单状态，勿重复付款"); }
    }
}
