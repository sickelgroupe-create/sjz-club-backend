package com.ruoyi.club.web;

import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.ruoyi.club.service.ClubAppService;
import com.ruoyi.club.service.ClubAfterSaleService;
import com.ruoyi.club.service.ClubAuthService;
import com.ruoyi.club.service.ClubBusinessService;
import com.ruoyi.club.service.ClubTeenPolicyService;
import com.ruoyi.club.service.ClubWechatPayService;
import com.wechat.pay.java.core.exception.ValidationException;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import com.ruoyi.common.annotation.RateLimiter;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.LimitType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.file.FileUploadUtils;
import com.ruoyi.common.utils.file.MimeTypeUtils;

@RestController
@RequestMapping("/app")
public class ClubAppController
{
    private static final Logger log = LoggerFactory.getLogger(ClubAppController.class);
    private final ClubAuthService auth;
    private final ClubAppService service;
    private final ClubBusinessService business;
    private final ClubAfterSaleService afterSale;
    private final ClubTeenPolicyService teenPolicy;
    private final ClubWechatPayService wechatPay;

    public ClubAppController(ClubAuthService auth, ClubAppService service, ClubBusinessService business,
            ClubAfterSaleService afterSale, ClubTeenPolicyService teenPolicy, ClubWechatPayService wechatPay)
    {
        this.auth = auth;
        this.service = service;
        this.business = business;
        this.afterSale = afterSale;
        this.teenPolicy = teenPolicy;
        this.wechatPay = wechatPay;
    }

    private void authContent(HttpServletRequest request, String contentType)
    {
        teenPolicy.assertContentAllowed(auth.optionalUserId(request), contentType);
    }

    @PostMapping("/auth/register")
    @RateLimiter(time = 60, count = 5, limitType = LimitType.IP)
    public AjaxResult register(@RequestBody Map<String, Object> input)
    {
        auth.requireStandalone();
        return AjaxResult.success(auth.register(input));
    }

    @PostMapping("/auth/login")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.IP)
    public AjaxResult login(@RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(auth.login(input));
    }

    @PostMapping("/auth/phone-code")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.IP)
    public AjaxResult phoneCode(@RequestBody Map<String, Object> input)
    {
        throw new ServiceException("验证码登录已关闭，请使用密码或微信登录");
    }

    @PostMapping("/auth/phone-register")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.IP)
    public AjaxResult phoneRegister(@RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(auth.phoneRegister(input));
    }

    @PostMapping("/auth/phone-code-login")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.IP)
    public AjaxResult phoneCodeLogin(@RequestBody Map<String, Object> input)
    {
        throw new ServiceException("验证码登录已关闭，请使用密码或微信登录");
    }

    @PostMapping("/auth/wechat-login")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.IP)
    public AjaxResult wechatLogin(@RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(auth.wechatLogin(input));
    }

    @PostMapping("/auth/refresh")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.IP)
    public AjaxResult refresh(@RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(auth.refresh(input));
    }

    @PostMapping("/auth/logout")
    public AjaxResult logout(HttpServletRequest request)
    {
        auth.requireAuthenticatedUserId(request);
        auth.logout(request);
        return AjaxResult.success();
    }

    @GetMapping("/auth/bindings")
    public AjaxResult bindings(HttpServletRequest request)
    {
        return AjaxResult.success(auth.bindingState(auth.requireAuthenticatedUserId(request)));
    }

    @PostMapping("/auth/bind-wechat")
    public AjaxResult bindWechat(HttpServletRequest request, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(auth.bindWechatSession(auth.requireAuthenticatedUserId(request), input));
    }

    @PostMapping("/auth/contact-phone")
    @RateLimiter(time = 60, count = 5, limitType = LimitType.IP)
    public AjaxResult contactPhone(HttpServletRequest request,@RequestBody Map<String,Object> input) {
        return AjaxResult.success(auth.setContactPhone(auth.requireUserId(request),input));
    }

    @PostMapping("/auth/bind-phone")
    @RateLimiter(time = 60, count = 5, limitType = LimitType.IP)
    public AjaxResult bindPhone(HttpServletRequest request, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(auth.bindWechatPhone(auth.requireWechatUserId(request),input));
    }

    @PostMapping("/auth/unbind")
    public AjaxResult unbind(HttpServletRequest request, @RequestBody Map<String, Object> input)
    {
        auth.requireUserId(request);
        throw new ServiceException("如需解绑账号请联系客服核验身份");
    }

    @PostMapping("/auth/reset-password")
    @RateLimiter(time = 60, count = 5, limitType = LimitType.IP)
    public AjaxResult resetPassword(HttpServletRequest request, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(auth.resetPassword(auth.requireUserId(request), input));
    }

    @PostMapping("/auth/merge")
    public AjaxResult mergeAccounts(HttpServletRequest request, @RequestBody Map<String, Object> input)
    {
        auth.requireUserId(request);
        throw new ServiceException("如需合并账号请联系客服核验身份");
    }

    @GetMapping("/public/home")
    public AjaxResult home(HttpServletRequest request) { return AjaxResult.success(service.home(auth.optionalUserId(request))); }

    @GetMapping("/public/promotions")
    public AjaxResult promotions(HttpServletRequest request, @RequestParam(required = false) String scene)
    {
        authContent(request, "promotion");
        return AjaxResult.success(service.promotions(scene));
    }

    @GetMapping("/public/articles/{id}")
    public AjaxResult article(HttpServletRequest request, @PathVariable Long id)
    {
        authContent(request, "news");
        return AjaxResult.success(service.article(id));
    }

    @GetMapping("/public/documents/{scene}")
    public AjaxResult document(HttpServletRequest request, @PathVariable String scene)
    {
        authContent(request, "document");
        return AjaxResult.success(service.document(scene));
    }

    @GetMapping("/public/products")
    public AjaxResult products(HttpServletRequest request, @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "") String keyword, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize, @RequestParam(defaultValue = "default") String sort,
            @RequestParam(defaultValue = "false") boolean hot)
    {
        authContent(request, "product");
        return AjaxResult.success(service.productItems(category, keyword, page, pageSize, sort, hot));
    }

    @GetMapping("/public/products/{id}")
    public AjaxResult product(HttpServletRequest request, @PathVariable Long id)
    {
        authContent(request, "product");
        return AjaxResult.success(service.product(id));
    }

    @GetMapping("/public/players")
    public AjaxResult players(HttpServletRequest request, @RequestParam(defaultValue = "") String gender,
            @RequestParam(defaultValue = "") String keyword, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize, @RequestParam(defaultValue = "default") String sort,
            @RequestParam(required = false) Long productId, @RequestParam(required = false) Long skuId)
    {
        authContent(request, "product");
        return AjaxResult.success(service.playerItems(gender, keyword, page, pageSize, sort, productId, skuId));
    }

    @GetMapping("/public/players/{id}/services")
    public AjaxResult playerOffers(HttpServletRequest request, @PathVariable Long id)
    {
        authContent(request, "product");
        return AjaxResult.success(service.playerOffers(id));
    }

    @GetMapping("/public/ranking")
    public AjaxResult ranking(HttpServletRequest request)
    {
        authContent(request, "ranking");
        return AjaxResult.success(service.ranking());
    }

    @GetMapping("/public/announcements/{id}")
    public AjaxResult announcement(HttpServletRequest request, @PathVariable Long id)
    {
        authContent(request, "announcement");
        return AjaxResult.success(service.announcement(id));
    }

    @GetMapping("/public/customer-service")
    public AjaxResult customerService() { return AjaxResult.success(service.customerService()); }

    @GetMapping("/me")
    public AjaxResult me(HttpServletRequest request) { return AjaxResult.success(service.profile(auth.requireUserId(request))); }

    @PutMapping("/me")
    public AjaxResult updateMe(HttpServletRequest request, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(service.updateProfile(auth.requireUserId(request), input));
    }

    @GetMapping("/relations/products/{productId}")
    public AjaxResult relationState(HttpServletRequest request, @PathVariable Long productId)
    {
        return AjaxResult.success(service.relationState(auth.requireUserId(request), productId));
    }

    @PutMapping("/favorites/{productId}")
    public AjaxResult favorite(HttpServletRequest request, @PathVariable Long productId)
    {
        return AjaxResult.success(service.setFavorite(auth.requireUserId(request), productId, true));
    }

    @DeleteMapping("/favorites/{productId}")
    public AjaxResult unfavorite(HttpServletRequest request, @PathVariable Long productId)
    {
        return AjaxResult.success(service.setFavorite(auth.requireUserId(request), productId, false));
    }

    @GetMapping("/favorites")
    public AjaxResult favorites(HttpServletRequest request) { return AjaxResult.success(service.favorites(auth.requireUserId(request))); }

    @PutMapping("/follows/{shopId}")
    public AjaxResult follow(HttpServletRequest request, @PathVariable Long shopId)
    {
        return AjaxResult.success(service.setFollow(auth.requireUserId(request), shopId, true));
    }

    @DeleteMapping("/follows/{shopId}")
    public AjaxResult unfollow(HttpServletRequest request, @PathVariable Long shopId)
    {
        return AjaxResult.success(service.setFollow(auth.requireUserId(request), shopId, false));
    }

    @GetMapping("/follows")
    public AjaxResult follows(HttpServletRequest request) { return AjaxResult.success(service.follows(auth.requireUserId(request))); }

    @PostMapping("/posters/{productId}")
    public AjaxResult createPoster(HttpServletRequest request, @PathVariable Long productId)
    {
        return AjaxResult.success(service.createPoster(auth.requireUserId(request), productId));
    }

    @GetMapping("/posters")
    public AjaxResult posters(HttpServletRequest request) { return AjaxResult.success(service.posters(auth.requireUserId(request))); }

    @GetMapping("/coupons/available")
    public AjaxResult availableCoupons(HttpServletRequest request){return AjaxResult.success(service.claimableCoupons(auth.requireUserId(request)));}
    @PostMapping("/coupons/{id}/claim")
    @RateLimiter(time=60,count=10,limitType=LimitType.IP)
    public AjaxResult claimCoupon(HttpServletRequest request,@PathVariable Long id){service.claimCoupon(auth.requireUserId(request),id);return AjaxResult.success();}
    @GetMapping("/coupons")
    public AjaxResult coupons(HttpServletRequest request) { return AjaxResult.success(service.coupons(auth.requireUserId(request))); }

    @PostMapping("/orders")
    public AjaxResult createOrder(HttpServletRequest request, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(service.createOrder(auth.requireContactUserId(request), input));
    }

    @PostMapping("/orders/quote")
    public AjaxResult quoteOrder(HttpServletRequest request, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(service.quoteOrder(auth.requireContactUserId(request), input));
    }

    @GetMapping("/orders")
    public AjaxResult orders(HttpServletRequest request, @RequestParam(defaultValue = "all") String status)
    {
        return AjaxResult.success(service.orders(auth.requireUserId(request), status));
    }

    @GetMapping("/orders/{id}")
    public AjaxResult order(HttpServletRequest request, @PathVariable Long id)
    {
        return AjaxResult.success(service.order(auth.requireUserId(request), id));
    }

    @PostMapping("/orders/{id}/balance-pay")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.IP)
    public AjaxResult balancePay(HttpServletRequest request, @PathVariable Long id, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(service.balancePay(auth.requireContactUserId(request), id, input));
    }

    @PostMapping("/orders/{id}/simulation-pay")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.IP)
    public AjaxResult simulationPay(HttpServletRequest request,@PathVariable Long id,@RequestBody Map<String,Object> input) {
        auth.requireStandalone();
        input.put("method","wechat");
        return AjaxResult.success(service.balancePay(auth.requireUserId(request),id,input));
    }

    @PostMapping("/orders/{id}/wechat-prepay")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.IP)
    public AjaxResult wechatPrepay(HttpServletRequest request, @PathVariable Long id, @RequestBody Map<String, Object> input)
    {
        Long userId=auth.requireContactUserId(request);
        auth.requireWechatUserId(request);
        return AjaxResult.success(service.wechatPrepay(userId, id, input));
    }

    @PostMapping("/orders/{id}/wechat-sync")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.IP)
    public AjaxResult wechatSync(HttpServletRequest request, @PathVariable Long id)
    {
        return AjaxResult.success(service.syncWechatPayment(auth.requireUserId(request), id));
    }

    /** Public only to WeChat Pay: the raw body is verified before any state transition. */
    @PostMapping("/pay/wechat/notify")
    public ResponseEntity<Void> wechatNotify(HttpServletRequest request, @RequestBody String body)
    {
        try
        {
            String serial = request.getHeader("Wechatpay-Serial");
            String nonce = request.getHeader("Wechatpay-Nonce");
            String signature = request.getHeader("Wechatpay-Signature");
            String timestamp = request.getHeader("Wechatpay-Timestamp");
            if (serial == null || nonce == null || signature == null || timestamp == null || body == null || body.trim().isEmpty())
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            Transaction tx = wechatPay.parseNotification(serial, nonce, signature, timestamp, body);
            service.completeWechatPayment(tx);
            return ResponseEntity.ok().build();
        }
        catch (ValidationException e)
        {
            log.warn("Rejected invalid WeChat Pay notification signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        catch (RuntimeException e)
        {
            log.error("WeChat Pay notification processing failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** Public only to WeChat Pay: the raw refund body is verified before local reversal. */
    @PostMapping("/pay/wechat/refund-notify")
    public ResponseEntity<Void> wechatRefundNotify(HttpServletRequest request, @RequestBody String body)
    {
        try
        {
            String serial = request.getHeader("Wechatpay-Serial");
            String nonce = request.getHeader("Wechatpay-Nonce");
            String signature = request.getHeader("Wechatpay-Signature");
            String timestamp = request.getHeader("Wechatpay-Timestamp");
            if (serial == null || nonce == null || signature == null || timestamp == null || body == null || body.trim().isEmpty())
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            RefundNotification notification = wechatPay.parseRefundNotification(serial, nonce, signature, timestamp, body);
            afterSale.completeWechatRefund(notification);
            return ResponseEntity.ok().build();
        }
        catch (ValidationException e)
        {
            log.warn("Rejected invalid WeChat Pay refund notification signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        catch (RuntimeException e)
        {
            log.error("WeChat Pay refund notification processing failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/orders/{id}/cancel")
    public AjaxResult cancelOrder(HttpServletRequest request, @PathVariable Long id)
    {
        return AjaxResult.success(service.cancelOrder(auth.requireUserId(request), id));
    }

    @PostMapping("/orders/{id}/aftersales")
    public AjaxResult applyAfterSale(HttpServletRequest request, @PathVariable Long id, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(afterSale.apply(auth.requireUserId(request), id, input));
    }

    @GetMapping("/aftersales")
    public AjaxResult afterSales(HttpServletRequest request)
    {
        return AjaxResult.success(afterSale.userList(auth.requireUserId(request)));
    }

    @GetMapping("/aftersales/{id}")
    public AjaxResult afterSale(HttpServletRequest request, @PathVariable Long id)
    {
        return AjaxResult.success(afterSale.detailForUser(auth.requireUserId(request), id));
    }

    @PostMapping("/orders/{id}/confirm")
    public AjaxResult confirmOrder(HttpServletRequest request, @PathVariable Long id, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(service.confirmOrder(auth.requireUserId(request), id, input));
    }

    @GetMapping("/workbench")
    public AjaxResult workbench(HttpServletRequest request)
    {
        return AjaxResult.success(business.workbench(auth.requireUserId(request)));
    }

    @GetMapping("/workbench/metrics/{type}")
    public AjaxResult workbenchMetric(HttpServletRequest request, @PathVariable String type, @RequestParam(defaultValue = "1") int page)
    {
        return AjaxResult.success(business.workbenchMetric(auth.requireUserId(request), type, page));
    }

    @GetMapping("/workbench/orders")
    public AjaxResult workbenchOrders(HttpServletRequest request, @RequestParam(defaultValue = "all") String status)
    {
        return AjaxResult.success(business.workbenchOrders(auth.requireUserId(request), status));
    }

    @GetMapping("/workbench/orders/{id}")
    public AjaxResult workbenchOrder(HttpServletRequest request, @PathVariable Long id)
    {
        return AjaxResult.success(business.workbenchOrder(auth.requireUserId(request), id));
    }

    @GetMapping("/workbench/aftersales/{id}")
    public AjaxResult workbenchAfterSale(HttpServletRequest request, @PathVariable Long id)
    {
        return AjaxResult.success(afterSale.detailForProvider(auth.requireUserId(request), id));
    }

    @PostMapping("/workbench/aftersales/{id}/actions")
    public AjaxResult workbenchAfterSaleAction(HttpServletRequest request, @PathVariable Long id, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(afterSale.providerAction(auth.requireUserId(request), id, input));
    }

    @PostMapping("/workbench/orders/{id}/actions")
    public AjaxResult workbenchOrderAction(HttpServletRequest request, @PathVariable Long id, @RequestBody Map<String, Object> input)
    {
        Long userId=auth.requireUserId(request);
        if("reject".equals(String.valueOf(input.get("action"))))return AjaxResult.success(afterSale.rejectOrder(userId,id,input));
        return AjaxResult.success(business.roleAction(userId, id, input));
    }

    @PutMapping("/workbench/availability")
    public AjaxResult workbenchAvailability(HttpServletRequest request, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(business.updateAvailability(auth.requireUserId(request), input));
    }

    @GetMapping("/workbench/profile")
    public AjaxResult workbenchProfile(HttpServletRequest request)
    {
        return AjaxResult.success(business.playerProfile(auth.requireUserId(request)));
    }

    @PutMapping("/workbench/profile")
    public AjaxResult saveWorkbenchProfile(HttpServletRequest request, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(business.savePlayerProfile(auth.requireUserId(request), input));
    }

    @GetMapping("/workbench/player-services")
    public AjaxResult workbenchPlayerServices(HttpServletRequest request)
    {
        return AjaxResult.success(business.playerServices(auth.requireUserId(request)));
    }

    @PutMapping("/workbench/player-services")
    public AjaxResult saveWorkbenchPlayerServices(HttpServletRequest request, @RequestBody Map<String, Object> input)
    {
        auth.requireUserId(request);throw new ServiceException("商品服务由平台统一绑定，请联系管理员");
    }

    @GetMapping("/workbench/products")
    public AjaxResult merchantProducts(HttpServletRequest request)
    {
        return AjaxResult.success(business.playerProducts(auth.requireUserId(request)));
    }

    @PostMapping("/workbench/products")
    public AjaxResult saveMerchantProduct(HttpServletRequest request, @RequestBody Map<String, Object> input)
    {
        auth.requireUserId(request);throw new ServiceException("平台商品只能由后台管理员编辑");
    }

    @PostMapping("/orders/{id}/reviews")
    public AjaxResult review(HttpServletRequest request, @PathVariable Long id, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(service.review(auth.requireUserId(request), id, input));
    }

    @GetMapping("/messages")
    public AjaxResult messages(HttpServletRequest request) { return AjaxResult.success(service.messages(auth.requireUserId(request))); }

    @GetMapping("/messages/unread-count")
    public AjaxResult unreadMessageCount(HttpServletRequest request) { return AjaxResult.success(service.unreadMessageCount(auth.requireUserId(request))); }

    @PutMapping("/messages/read-all")
    public AjaxResult readAllMessages(HttpServletRequest request) { return AjaxResult.success(service.readAllMessages(auth.requireUserId(request))); }

    @PutMapping("/messages/{id}/read")
    public AjaxResult readMessage(HttpServletRequest request, @PathVariable Long id)
    {
        service.readMessage(auth.requireUserId(request), id);
        return AjaxResult.success();
    }

    @GetMapping("/wallet")
    public AjaxResult wallet(HttpServletRequest request) { return AjaxResult.success(service.wallet(auth.requireUserId(request))); }

    @GetMapping("/wallet/records")
    public AjaxResult walletRecords(HttpServletRequest request,
            @RequestParam(required = false) String beforeId, @RequestParam(defaultValue = "30") int limit)
    {
        return AjaxResult.success(service.walletRecords(auth.requireUserId(request), beforeId, limit));
    }

    @GetMapping("/teen-mode")
    public AjaxResult teenMode(HttpServletRequest request) { return AjaxResult.success(service.teenMode(auth.requireUserId(request))); }

    @PutMapping("/teen-mode")
    public AjaxResult enableTeenMode(HttpServletRequest request, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(service.enableTeenMode(auth.requireUserId(request), input));
    }

    @PostMapping("/teen-mode/disable")
    public AjaxResult disableTeenMode(HttpServletRequest request, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(service.disableTeenMode(auth.requireUserId(request), input));
    }

    @GetMapping("/recharges/config")
    public AjaxResult rechargeConfig(HttpServletRequest request)
    {
        return AjaxResult.success(service.rechargeConfig(auth.requireUserId(request)));
    }

    @PostMapping("/recharges")
    public AjaxResult createRecharge(HttpServletRequest request, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(service.createRecharge(auth.requireUserId(request), input));
    }

    @PostMapping("/recharges/{id}/simulation-pay")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.IP)
    public AjaxResult simulationRecharge(HttpServletRequest request,@PathVariable Long id,@RequestBody Map<String,Object> input) {
        auth.requireStandalone();
        return AjaxResult.success(service.simulateRecharge(auth.requireUserId(request),id,input));
    }

    @GetMapping("/recharges")
    public AjaxResult recharges(HttpServletRequest request)
    {
        return AjaxResult.success(service.recharges(auth.requireUserId(request)));
    }

    @GetMapping("/recharges/{id}")
    public AjaxResult recharge(HttpServletRequest request, @PathVariable Long id)
    {
        return AjaxResult.success(service.recharge(auth.requireUserId(request), id));
    }

    @PostMapping("/recharges/{id}/wechat-prepay")
    @RateLimiter(time = 60, count = 10, limitType = LimitType.IP)
    public AjaxResult wechatRechargePrepay(HttpServletRequest request, @PathVariable Long id, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(service.wechatRechargePrepay(auth.requireWechatUserId(request), id, input));
    }

    @PostMapping("/recharges/{id}/wechat-sync")
    @RateLimiter(time = 60, count = 20, limitType = LimitType.IP)
    public AjaxResult wechatRechargeSync(HttpServletRequest request, @PathVariable Long id)
    {
        return AjaxResult.success(service.syncWechatRecharge(auth.requireUserId(request), id));
    }

    @PostMapping("/withdrawals")
    public AjaxResult withdraw(HttpServletRequest request, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(service.withdraw(auth.requireWechatUserId(request), input));
    }

    @GetMapping("/withdrawals")
    public AjaxResult withdrawals(HttpServletRequest request)
    {
        return AjaxResult.success(service.withdrawals(auth.requireUserId(request)));
    }

    @GetMapping("/withdrawals/by-request")
    public AjaxResult withdrawalByRequest(HttpServletRequest request, @RequestParam String key)
    {
        return AjaxResult.success(service.withdrawalByRequest(auth.requireUserId(request), key));
    }

    @GetMapping("/withdrawals/{id}")
    public AjaxResult withdrawal(HttpServletRequest request, @PathVariable Long id)
    {
        return AjaxResult.success(service.withdrawal(auth.requireUserId(request), id));
    }

    @PostMapping("/identity")
    public AjaxResult identity(HttpServletRequest request, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(service.identity(auth.requireUserId(request), input));
    }

    @GetMapping("/identity")
    public AjaxResult identityStatus(HttpServletRequest request)
    {
        return AjaxResult.success(service.identityStatus(auth.requireUserId(request)));
    }

    @GetMapping("/notification-settings")
    public AjaxResult notificationSettings(HttpServletRequest request)
    {
        return AjaxResult.success(service.notificationSettings(auth.requireUserId(request)));
    }

    @PutMapping("/notification-settings")
    public AjaxResult updateNotificationSettings(HttpServletRequest request, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(service.updateNotificationSettings(auth.requireUserId(request), input));
    }

    @PostMapping("/applications")
    public AjaxResult application(HttpServletRequest request, @RequestBody Map<String, Object> input)
    {
        return AjaxResult.success(service.application(auth.requireUserId(request), input));
    }

    @GetMapping("/applications")
    public AjaxResult applications(HttpServletRequest request, @RequestParam(defaultValue = "") String type)
    {
        return AjaxResult.success(service.applications(auth.requireUserId(request), type));
    }

    @GetMapping("/applications/{id}")
    public AjaxResult applicationDetail(HttpServletRequest request, @PathVariable Long id)
    {
        return AjaxResult.success(service.applicationDetail(auth.requireUserId(request), id));
    }

    @PostMapping("/files")
    public AjaxResult upload(HttpServletRequest request, @RequestParam("file") MultipartFile file) throws Exception
    {
        auth.requireUserId(request);
        if (file == null || file.isEmpty() || file.getSize() > 5L * 1024L * 1024L)
        {
            throw new ServiceException("图片不能为空且不能超过5MB");
        }
        String fileName = FileUploadUtils.upload(RuoYiConfig.getUploadPath(), file, MimeTypeUtils.IMAGE_EXTENSION, true);
        Map<String, Object> result = new HashMap<>();
        result.put("url", fileName);
        return AjaxResult.success(result);
    }
}
