package com.ruoyi.club.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.club.web.ClubForbiddenException;
import com.ruoyi.club.web.ClubConflictException;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import com.wechat.pay.java.service.refund.model.Status;

/** 订单售后申请、服务方处理、平台审核与最终资金退款。 */
@Service
public class ClubAfterSaleService
{
    private final JdbcTemplate jdbc;
    private final ClubBusinessService business;
    private final ClubWechatPayService wechatPay;

    public ClubAfterSaleService(JdbcTemplate jdbc, ClubBusinessService business, ClubWechatPayService wechatPay)
    {
        this.jdbc = jdbc;
        this.business = business;
        this.wechatPay = wechatPay;
    }

    /** Refusing fulfillment creates a formal refund case, never an unconfirmed refund. */
    @Transactional
    public Map<String,Object> rejectOrder(Long userId,Long orderId,Map<String,Object> input) {
        String key=required(input,"idempotencyKey","缺少操作编号");
        if(key.length()>96)throw new ServiceException("操作编号过长");
        List<Map<String,Object>> rows=jdbc.queryForList("select * from club_order where id=? and provider_user_id=? and provider_type='player' for update",orderId,userId);
        if(rows.isEmpty())throw new ClubForbiddenException("无权拒绝该订单");
        Map<String,Object> order=rows.get(0);
        if("refunding".equals(text(order.get("status"))))return order;
        if(!"pending".equals(text(order.get("status"))))throw new ServiceException("只有待接单订单可以拒单；已开始的服务请走售后处理");
        return openReview(order,"打手拒单","打手无法接单，申请平台审核退款", "provider-reject:"+orderId,"player",userId);
    }

    /** Verified money is recorded first, then fulfillment is held for platform review. */
    @Transactional
    public void reviewPaidLimit(Long orderId) {
        Map<String,Object> order=jdbc.queryForMap("select * from club_order where id=? for update",orderId);
        if(!"pending".equals(text(order.get("status"))))throw new ServiceException("收款复核订单状态不正确");
        openReview(order,"已收款超出消费限额","微信付款已确认，超过当前日限额；暂停服务并提交平台核对退款", "paid-limit:"+orderId,"system",null);
    }

    private Map<String,Object> openReview(Map<String,Object> order,String reason,String note,String key,String operator,Long operatorId) {
        Long orderId=number(order.get("id"));
        if(!jdbc.queryForList("select id from club_aftersale where order_id=?",orderId).isEmpty())throw new ServiceException("订单已有售后记录，请到退款售后继续处理");
        String no="AS"+System.currentTimeMillis()+randomDigits();
        jdbc.update("insert into club_aftersale(aftersale_no,order_id,user_id,provider_user_id,status,reason,description,evidence_json,refund_amount,request_id) values(?,?,?,?,'platform_reviewing',?,?,'[]',?,?)",no,orderId,order.get("user_id"),order.get("provider_user_id"),reason,note,order.get("total_amount"),key);
        Long id=jdbc.queryForObject("select id from club_aftersale where aftersale_no=?",Long.class,no);
        log(id,"","platform_reviewing",operator,operatorId,note);
        jdbc.update("update club_order set status='refunding',version=version+1 where id=?",orderId);
        jdbc.update("insert into club_order_log(order_id,from_status,to_status,operator_type,operator_id,note) values(?,?,'refunding',?,?,?)",orderId,order.get("status"),operator,operatorId,note);
        notify(order.get("user_id"),"订单已进入退款审核",note+"。尚未完成退款，请在售后详情查看进度。",id);
        notify(order.get("provider_user_id"),"订单暂停服务",note,id);
        return jdbc.queryForMap("select * from club_order where id=?",orderId);
    }

    @Transactional
    public Map<String, Object> apply(Long userId, Long orderId, Map<String, Object> input)
    {
        String requestId = required(input, "requestId", "缺少售后申请幂等编号");
        if (requestId.length() > 96) throw new ServiceException("售后申请幂等编号过长");
        List<Map<String, Object>> previous = jdbc.queryForList("select id from club_aftersale where user_id=? and request_id=?", userId, requestId);
        if (!previous.isEmpty()) return detailForUser(userId, number(previous.get(0).get("id")));
        List<Map<String, Object>> orders = jdbc.queryForList("select * from club_order where id=? and user_id=? for update", orderId, userId);
        if (orders.isEmpty()) throw new ServiceException("订单不存在");
        previous = jdbc.queryForList("select id from club_aftersale where user_id=? and request_id=?", userId, requestId);
        if (!previous.isEmpty()) return detailForUser(userId, number(previous.get(0).get("id")));
        Map<String, Object> order = orders.get(0);
        String status = text(order.get("status"));
        if (!Arrays.asList("pending", "accepted", "serving", "completed").contains(status))
            throw new ServiceException("当前订单状态不能申请售后");
        // aftersale_protected_until is the provider-income hold deadline, not the
        // customer's claim deadline.  A post-settlement dispute must still enter
        // the formal review flow so an approved refund can recover available funds
        // or create an auditable provider receivable without making a wallet negative.
        String reason = required(input, "reason", "请选择退款原因");
        String description = limited(input.get("description"), 1000);
        String evidence = JSON.toJSONString(input.get("evidence") == null ? java.util.Collections.emptyList() : input.get("evidence"));
        if (evidence.length() > 8000) throw new ServiceException("售后凭证数量过多");
        String no = "AS" + System.currentTimeMillis() + randomDigits();
        boolean platformOrder = "platform".equals(text(order.get("provider_type")));
        String initialStatus = platformOrder ? "platform_reviewing" : "applied";
        int inserted = jdbc.update("insert ignore into club_aftersale(aftersale_no,order_id,user_id,provider_user_id,status,reason,description,evidence_json,refund_amount,request_id) " +
                        "values(?,?,?,?,?,?,?,?,?,?)", no, orderId, userId, order.get("provider_user_id"), initialStatus, reason, description, evidence, order.get("total_amount"), requestId);
        if (inserted != 1)
        {
            Long id = jdbc.queryForObject("select id from club_aftersale where order_id=?", Long.class, orderId);
            return detailForUser(userId, id);
        }
        Long id = jdbc.queryForObject("select id from club_aftersale where aftersale_no=?", Long.class, no);
        log(id, "", initialStatus, "user", userId, platformOrder ? "平台承接订单，售后直接进入平台审核" : "用户提交售后申请");
        jdbc.update("update club_order set status='refunding',version=version+1 where id=? and status=?", orderId, status);
        jdbc.update("insert into club_order_log(order_id,from_status,to_status,operator_type,operator_id,note) values(?,?,?,?,?,?)",
                orderId, status, "refunding", "user", userId, "用户提交售后申请，等待处理");
        notify(order.get("provider_user_id"), "收到新的售后申请", "订单" + order.get("order_no") + "需要处理售后申请。", id);
        if (platformOrder) notify(userId, "售后申请已提交平台", "平台已收到您的售后申请，请等待后台审核。", id);
        return detailForUser(userId, id);
    }

    /** Administrator stops fulfillment; money moves only after the existing refund review. */
    @Transactional
    public Map<String,Object> adminAbandon(Long adminId,Long orderId,Map<String,Object> input) {
        String note=required(input,"note","请填写放弃接单原因");
        if(note.length()>1000)throw new ServiceException("原因不能超过1000字");
        String requestId=required(input,"requestId","缺少操作编号");
        if(requestId.length()>96)throw new ServiceException("操作编号过长");
        List<Map<String,Object>> orders=jdbc.queryForList("select * from club_order where id=? for update",orderId);
        if(orders.isEmpty())throw new ServiceException("订单不存在");
        Map<String,Object> order=orders.get(0);String status=text(order.get("status"));
        if("refunding".equals(status))return order;
        if(!Arrays.asList("pending","accepted","serving").contains(status))throw new ServiceException("当前订单不能放弃接单");
        if(!jdbc.queryForList("select id from club_aftersale where order_id=?",orderId).isEmpty())throw new ServiceException("订单已有售后记录，请到退款售后继续处理");
        String no="AS"+System.currentTimeMillis()+randomDigits();
        jdbc.update("insert into club_aftersale(aftersale_no,order_id,user_id,provider_user_id,status,reason,description,evidence_json,refund_amount,request_id) values(?,?,?,?,'platform_reviewing',?,?,'[]',?,?)",no,orderId,order.get("user_id"),order.get("provider_user_id"),"平台放弃接单",note,order.get("total_amount"),requestId);
        Long id=jdbc.queryForObject("select id from club_aftersale where aftersale_no=?",Long.class,no);
        log(id,"","platform_reviewing","admin",adminId,"管理员放弃接单："+note);
        jdbc.update("update club_order set status='refunding',version=version+1 where id=? and status=?",orderId,status);
        jdbc.update("insert into club_order_log(order_id,from_status,to_status,operator_type,operator_id,note) values(?,?, 'refunding','admin',?,?)",orderId,status,adminId,"放弃接单，转退款售后："+note);
        notify(order.get("user_id"),"订单已转入售后","平台暂时无法继续提供服务，已提交退款售后，尚未完成退款。",id);
        return jdbc.queryForMap("select * from club_order where id=?",orderId);
    }

    public List<Map<String, Object>> userList(Long userId)
    {
        return jdbc.queryForList("select a.id,a.aftersale_no as aftersaleNo,a.order_id as orderId,a.status,a.reason,a.refund_amount as refundAmount," +
                "o.order_no as orderNo,o.product_name as productName,a.applied_at as appliedAt,a.updated_at as updatedAt " +
                "from club_aftersale a join club_order o on o.id=a.order_id where a.user_id=? order by a.id desc", userId);
    }

    public Map<String, Object> detailForUser(Long userId, Long id)
    {
        return detail(id, "a.user_id=?", userId);
    }

    public Map<String, Object> detailForProvider(Long userId, Long id)
    {
        return detail(id, "a.provider_user_id=?", userId);
    }

    @Transactional
    public Map<String, Object> providerAction(Long userId, Long id, Map<String, Object> input)
    {
        String action = required(input, "action", "请选择售后处理结果");
        if (!Arrays.asList("approve", "reject").contains(action)) throw new ServiceException("不支持的售后操作");
        String key = required(input, "idempotencyKey", "缺少幂等键");
        if (jdbc.update("insert ignore into club_action_request(user_id,business_type,business_id,action,idempotency_key) values(?,'aftersale',?,?,?)",
                userId, id, action, key) != 1) return detailForProvider(userId, id);
        List<Map<String, Object>> rows = jdbc.queryForList("select * from club_aftersale where id=? and provider_user_id=? for update", id, userId);
        if (rows.isEmpty()) throw new ClubForbiddenException("无权处理该售后申请");
        Map<String, Object> current = rows.get(0);
        if (!"applied".equals(text(current.get("status")))) throw new ServiceException("当前售后状态不能由服务方处理");
        String target = "approve".equals(action) ? "provider_approved" : "provider_rejected";
        String note = limited(input.get("note"), 1000);
        jdbc.update("update club_aftersale set status=?,provider_note=?,provider_processed_at=now() where id=? and status='applied'", target, note, id);
        log(id, "applied", target, "provider", userId, note);
        notify(current.get("user_id"), "售后申请状态已更新", "服务方已经处理您的售后申请，请查看详情。", id);
        return detailForProvider(userId, id);
    }

    @Transactional
    public Map<String, Object> adminReview(Long adminId, Long id, Map<String, Object> input)
    {
        String action = required(input, "action", "请选择审核结果");
        if (!Arrays.asList("approve", "reject", "reopen").contains(action)) throw new ServiceException("审核结果不正确");
        String requestId = requiredEither(input, "requestId", "request_id", "缺少审核幂等编号");
        if (requestId.length() > 96) throw new ServiceException("审核幂等编号过长");
        String note = limited(required(input, "note", "请填写审核或重开说明"), 1000);
        // The order lock serializes reopening with fulfillment, settlement release,
        // refunds and other reviews. Never acquire the case before this order.
        List<Map<String, Object>> orders = jdbc.queryForList(
                "select * from club_order where id=(select order_id from club_aftersale where id=?) for update", id);
        if (orders.size() != 1) throw new ServiceException("售后对应订单不存在");
        Map<String, Object> order = orders.get(0);
        List<Map<String, Object>> rows = jdbc.queryForList("select * from club_aftersale where id=? for update", id);
        if (rows.isEmpty()) throw new ServiceException("售后申请不存在");
        Map<String, Object> current = rows.get(0);
        if (!order.get("id").equals(current.get("order_id"))) throw new ServiceException("售后对应订单不一致");
        BigDecimal orderAmount = decimal(order.get("total_amount"), null);
        BigDecimal requestedAmount = decimal(first(input, "refundAmount", "refund_amount"), orderAmount);
        if (orderAmount.compareTo(BigDecimal.ZERO) <= 0 || requestedAmount.compareTo(orderAmount) != 0)
            throw new ServiceException("当前仅支持全额退款，退款金额必须等于订单实付金额");
        if ("reopen".equals(action) && requestId.equals(text(current.get("request_id"))))
            throw new ClubConflictException("重开售后必须使用新的幂等编号，不能复用原申请编号");
        preservePreviousReview(current, orderAmount);
        if (beginAdminRequest(adminId, id, action, requestId, note, requestedAmount, current)) return adminDetail(id, true);
        if ("reopen".equals(action))
        {
            reopenLocked(adminId, id, order, current, note);
            return finishAdminRequest(requestId, id);
        }
        String from = text(current.get("status"));
        if (("approve".equals(action) && "refunded".equals(from))
                || ("reject".equals(action) && "rejected".equals(from))) return finishAdminRequest(requestId, id);
        if (!Arrays.asList("provider_approved", "provider_rejected", "platform_reviewing").contains(from))
            throw new ServiceException("当前售后状态不能审核");
        BigDecimal refundAmount = orderAmount;
        if ("reject".equals(action))
        {
            jdbc.update("update club_aftersale set status='rejected',review_note=?,reviewed_by=?,reviewed_at=now(),review_request_id=? where id=?", note, adminId, requestId, id);
            log(id, from, "rejected", "admin", adminId, note);
            restoreOrderAfterRejected(current.get("order_id"));
        }
        else
        {
            jdbc.update("update club_aftersale set status='approved',refund_amount=?,review_note=?,reviewed_by=?,reviewed_at=now(),refund_idempotency_key=?,review_request_id=? where id=?",
                    refundAmount, note, adminId, "AFR-" + id, requestId, id);
            log(id, from, "approved", "admin", adminId, note);
            List<Map<String, Object>> payments = jdbc.queryForList(
                    "select id,payment_no,mode,amount,status from club_payment where order_id=? and status='success' for update", current.get("order_id"));
            if (payments.size() != 1) throw new ServiceException("订单没有唯一的可退款支付记录");
            Map<String, Object> payment = payments.get(0);
            if ("wechat".equals(text(payment.get("mode"))))
            {
                String refundNo = "AFR-" + id;
                if (wechatPay.applePayment(text(payment.get("payment_no"))))
                {
                    jdbc.update("update club_payment set refund_no=?,refund_status='apple_pending' where id=? and status='success'",refundNo,payment.get("id"));
                    log(id,"approved","approved","system",null,"平台同意售后；苹果支付需用户向Apple申请退款，等待退款结果，尚未退款");
                    notify(current.get("user_id"),"苹果支付售后已审核","请向Apple申请退款，最终结果由Apple处理；平台审核通过不代表资金已退回。",id);
                    return finishAdminRequest(requestId, id);
                }
                if (ClubVirtualPayGateway.owns(text(payment.get("payment_no"))))
                {
                    // Commit the refund identity with approval. The worker performs the
                    // remote mutation only after this transaction is visible to it.
                    String remoteRefundNo = ClubVirtualPayGateway.refundNumber(refundNo);
                    if (jdbc.update("update club_virtual_payment set refund_no=? where payment_no=? and (refund_no is null or refund_no=?)",
                            remoteRefundNo, payment.get("payment_no"), remoteRefundNo) != 1)
                        throw new ServiceException("虚拟退款意图冲突，请人工核对");
                }
                jdbc.update("update club_payment set refund_no=?,refund_status='pending' where id=? and status='success'", refundNo, payment.get("id"));
                jdbc.update("insert into club_payment_audit(payment_id,order_id,user_id,action,result_status,request_id,detail) " +
                                "select id,order_id,user_id,'wechat_refund_intent','pending',?,'全额退款已审核，等待服务端提交和查单确认' from club_payment where id=?",
                        refundNo, payment.get("id"));
                log(id, "approved", "approved", "system", null, "退款意图已保存，等待服务端提交，尚未确认退款成功");
                notify(current.get("user_id"), "售后审核完成", "平台已同意退款，退款结果确认后将更新订单。", id);
                return finishAdminRequest(requestId, id);
            }
            else
            {
                business.executeApprovedRefund(number(current.get("order_id")), adminId, id, refundAmount, "售后审核通过：" + note);
                jdbc.update("update club_aftersale set status='refunded',refunded_at=now() where id=? and status='approved'", id);
                log(id, "approved", "refunded", "system", null, "退款及资金处理完成");
            }
        }
        notify(current.get("user_id"), "售后审核完成", "您的售后申请已经完成平台审核，请查看详情。", id);
        return finishAdminRequest(requestId, id);
    }

    private void reopenLocked(Long adminId, Long id, Map<String,Object> order, Map<String,Object> current, String note)
    {
        if (!"rejected".equals(current.get("status"))) throw new ServiceException("只有平台已驳回的售后可以重开");
        if (!Arrays.asList("pending", "accepted", "serving", "completed").contains(order.get("status")))
            throw new ServiceException("当前订单状态不能重开售后");
        if (!text(current.get("refund_idempotency_key")).isEmpty() || current.get("refunded_at") != null
                || !order.get("user_id").equals(current.get("user_id")))
            throw new ServiceException("售后存在退款记录或归属异常，不能重开");
        List<Map<String,Object>> payments = jdbc.queryForList("select * from club_payment where order_id=? for update", order.get("id"));
        Map<String,Object> paid = null;
        for (Map<String,Object> payment : payments)
        {
            if ("refunded".equals(payment.get("status")) || "created".equals(payment.get("status"))
                    || !text(payment.get("refund_no")).isEmpty() || !text(payment.get("refund_status")).isEmpty()
                    || !text(payment.get("wechat_refund_id")).isEmpty()
                    || payment.get("refunded_at") != null
                    || decimal(payment.get("refunded_amount"), BigDecimal.ZERO).signum() != 0)
                throw new ServiceException("订单存在支付或退款处理中记录，不能重开售后");
            if ("success".equals(payment.get("status")))
            {
                if (paid != null) throw new ServiceException("订单没有唯一的成功支付记录");
                paid = payment;
            }
        }
        if (paid == null || !order.get("user_id").equals(paid.get("user_id"))
                || !Arrays.asList("wechat", "balance", "mock_wechat").contains(paid.get("mode"))
                || decimal(paid.get("amount"), null).compareTo(decimal(order.get("total_amount"), null)) != 0)
            throw new ServiceException("订单原支付记录或金额异常，不能重开售后");
        if (!jdbc.queryForList("select v.payment_no from club_virtual_payment v join club_payment p on p.payment_no=v.payment_no where p.order_id=? and v.refund_no is not null for update", order.get("id")).isEmpty())
            throw new ServiceException("订单已有虚拟退款意图，不能重开售后");
        if (jdbc.update("update club_aftersale set status='platform_reviewing',review_note='',reviewed_by=null,reviewed_at=null,review_request_id=null where id=? and status='rejected'", id) != 1)
            throw new ClubConflictException("售后状态发生变化，请刷新后重试");
        if (jdbc.update("update club_order set status='refunding',version=version+1 where id=? and status=?", order.get("id"), order.get("status")) != 1)
            throw new ClubConflictException("订单状态发生变化，请刷新后重试");
        log(id, "rejected", "platform_reviewing", "admin", adminId, note);
        jdbc.update("insert into club_order_log(order_id,from_status,to_status,operator_type,operator_id,note) values(?,?,'refunding','admin',?,?)",
                order.get("id"), order.get("status"), adminId, "管理员重新打开售后，等待平台审核；尚未退款");
        notify(order.get("user_id"), "售后已重新进入平台审核", "管理员已重新打开原售后申请，尚未批准或完成退款，请查看售后处理记录。", id);
        notify(order.get("provider_user_id"), "售后重新审核，暂停服务", "订单已重新进入平台售后审核，请暂停服务并查看处理记录。", id);
    }

    /** Archive the pre-upgrade scalar key before a later review can replace it. */
    private void preservePreviousReview(Map<String,Object> current, BigDecimal amount)
    {
        String previous = text(current.get("review_request_id"));
        if (previous.isEmpty()) return;
        String status = text(current.get("status"));
        if (!Arrays.asList("rejected", "approved", "refunded").contains(status))
            throw new ServiceException("历史审核状态异常，请人工核对");
        String decision = status;
        if ("refunded".equals(status))
        {
            // Apple can override a rejected case without changing its reviewer.
            // The final refund state is not evidence that this reviewer approved it.
            List<String> decisions = jdbc.query("select to_status from club_aftersale_log where aftersale_id=? and operator_type='admin' and to_status in ('approved','rejected') order by id desc limit 1 for update",
                    (rs, row) -> rs.getString(1), current.get("id"));
            if (decisions.size() != 1) throw new ServiceException("历史审核日志缺失，请人工核对");
            decision = decisions.get(0);
        }
        String action = "rejected".equals(decision) ? "reject" : "approve";
        String snapshot = JSON.toJSONString(current);
        jdbc.update("insert into club_aftersale_admin_request(request_id,aftersale_id,admin_user_id,action,payload_hash,before_json,result_json,completed_at) values(?,?,?,?,?,?,?,now()) on duplicate key update request_id=request_id",
                previous, current.get("id"), current.get("reviewed_by"), action,
                adminRequestHash(number(current.get("id")), action, text(current.get("review_note")), amount), snapshot, snapshot);
    }

    private boolean beginAdminRequest(Long adminId, Long id, String action, String key, String note, BigDecimal amount, Map<String,Object> before)
    {
        String hash = adminRequestHash(id, action, note, amount);
        jdbc.update("insert into club_aftersale_admin_request(request_id,aftersale_id,admin_user_id,action,payload_hash,before_json) values(?,?,?,?,?,?) on duplicate key update request_id=request_id",
                key, id, adminId, action, hash, JSON.toJSONString(before));
        Map<String,Object> saved = jdbc.queryForMap("select * from club_aftersale_admin_request where request_id=? for update", key);
        if (!id.equals(number(saved.get("aftersale_id"))) || !adminId.equals(number(saved.get("admin_user_id")))
                || !action.equals(saved.get("action")) || !hash.equals(saved.get("payload_hash")))
            throw new ClubConflictException("同一幂等编号不能用于不同售后、操作、管理员或内容");
        return saved.get("result_json") != null;
    }

    private Map<String,Object> finishAdminRequest(String key, Long id)
    {
        Map<String,Object> result = adminDetail(id, true);
        jdbc.update("update club_aftersale_admin_request set result_json=?,completed_at=now() where request_id=? and result_json is null", JSON.toJSONString(result), key);
        return result;
    }

    private static String adminRequestHash(Long id, String action, String note, BigDecimal amount)
    {
        String canonical = JSON.toJSONString(Arrays.asList(id, action, note, amount.stripTrailingZeros().toPlainString()));
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder();
            for (byte value : digest) hash.append(String.format("%02x", value));
            return hash.toString();
        }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    @Transactional
    public void completeWechatRefund(RefundNotification notification)
    {
        String refundNo = text(notification.getOutRefundNo());
        if (refundNo.isEmpty()) throw new ServiceException("微信退款回调缺少商户退款单号");
        List<Map<String, Object>> payments = jdbc.queryForList(
                "select * from club_payment where refund_no=? and mode='wechat' for update", refundNo);
        if (payments.size() != 1) throw new ServiceException("微信退款记录不存在");
        Map<String, Object> payment = payments.get(0);
        String status = notification.getRefundStatus() == null ? "" : notification.getRefundStatus().name().toLowerCase();
        jdbc.update("update club_payment set wechat_refund_id=?,refund_status=? where id=? and status<>'refunded'",
                notification.getRefundId(), status, payment.get("id"));
        if (!Status.SUCCESS.equals(notification.getRefundStatus())) return;
        int expected = ClubWechatPayService.cents(decimal(payment.get("amount"), payment.get("amount")));
        if (notification.getAmount() == null || notification.getAmount().getRefund() == null || notification.getAmount().getRefund() != expected)
            throw new ServiceException("微信退款回调金额不匹配");
        if (!text(payment.get("payment_no")).equals(text(notification.getOutTradeNo())) ||
                !text(payment.get("mock_transaction_no")).equals(text(notification.getTransactionId())))
            throw new ServiceException("微信退款回调原支付流水不匹配");
        if ("refunded".equals(text(payment.get("status")))) return;
        List<Map<String, Object>> aftersales = jdbc.queryForList(
                "select * from club_aftersale where order_id=? and refund_idempotency_key=? for update", payment.get("order_id"), refundNo);
        if (aftersales.size() != 1 || !"approved".equals(text(aftersales.get(0).get("status"))))
            throw new ServiceException("微信退款对应售后状态异常");
        Map<String, Object> aftersale = aftersales.get(0);
        business.executeApprovedRefund(number(payment.get("order_id")), number(aftersale.get("reviewed_by")), number(aftersale.get("id")),
                decimal(payment.get("amount"), payment.get("amount")), "微信退款回调确认成功");
        jdbc.update("update club_aftersale set status='refunded',refunded_at=now() where id=? and status='approved'", aftersale.get("id"));
        log(number(aftersale.get("id")), "approved", "refunded", "wechat", null, "微信原路退款回调确认，本地资金冲正完成");
        notify(aftersale.get("user_id"), "微信退款完成", "订单款项已原路退回微信。", number(aftersale.get("id")));
    }

    public Map<String, Object> adminDetail(Long id)
    {
        return adminDetail(id, false);
    }

    private Map<String, Object> adminDetail(Long id, boolean currentRead)
    {
        Map<String, Object> result = detail(id, "1=1", null, currentRead);
        result.put("refundPayments", jdbc.queryForList(
                "select mode,status,amount,refund_no,refund_status,refunded_at from club_payment " +
                "where order_id=? and status in ('success','refunded') order by id desc" + (currentRead ? " for update" : ""), result.get("order_id")));
        return result;
    }

    public List<Long> readyForPlatformReview()
    {
        int hours = Math.max(1, configHours("aftersale_provider_response_hours", 24));
        return jdbc.query("select id from club_aftersale where status='applied' and applied_at<=date_sub(now(),interval ? hour) order by id limit 100",
                (rs, row) -> rs.getLong(1), hours);
    }

    @Transactional
    public void escalateProviderTimeout(Long aftersaleId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from club_aftersale where id=? for update", aftersaleId);
        if (rows.isEmpty() || !"applied".equals(text(rows.get(0).get("status")))) return;
        Map<String, Object> current = rows.get(0);
        int changed = jdbc.update("update club_aftersale set status='platform_reviewing',provider_note=case when coalesce(provider_note,'')='' then '服务方超过处理时限，系统已转平台审核' else provider_note end where id=? and status='applied'", aftersaleId);
        if (changed != 1) return;
        log(aftersaleId, "applied", "platform_reviewing", "system", null, "服务方超时未处理，自动转平台审核");
        jdbc.update("insert into club_order_log(order_id,from_status,to_status,operator_type,note) values(?,'refunding','refunding','system','售后申请已因服务方超时转平台审核')", current.get("order_id"));
        notify(current.get("user_id"), "售后申请已转平台审核", "服务方未在规定时间内处理，平台已自动介入。", aftersaleId);
        notify(current.get("provider_user_id"), "售后申请已转平台审核", "您未在规定时间内处理售后申请，平台已自动介入。", aftersaleId);
    }

    private int configHours(String key, int fallback)
    {
        List<String> values = jdbc.query("select config_value from club_business_config where config_key=?", (rs, row) -> rs.getString(1), key);
        try { return values.isEmpty() ? fallback : Integer.parseInt(values.get(0)); }
        catch (Exception ignored) { return fallback; }
    }

    private Map<String, Object> detail(Long id, String ownership, Object owner)
    {
        return detail(id, ownership, owner, false);
    }

    private Map<String, Object> detail(Long id, String ownership, Object owner, boolean currentRead)
    {
        String lock = currentRead ? " for update" : "";
        String sql = "select a.*,o.order_no as orderNo,o.product_name as productName,o.sku_name as skuName,o.total_amount as orderAmount,o.status as orderStatus " +
                "from club_aftersale a join club_order o on o.id=a.order_id where a.id=? and " + ownership + lock;
        List<Map<String, Object>> rows = owner == null ? jdbc.queryForList(sql, id) : jdbc.queryForList(sql, id, owner);
        if (rows.isEmpty()) throw new ClubForbiddenException("无权查看该售后申请");
        Map<String, Object> result = new HashMap<>(rows.get(0));
        result.put("logs", jdbc.queryForList("select from_status as fromStatus,to_status as toStatus,operator_type as operatorType,note,created_at as createdAt from club_aftersale_log where aftersale_id=? order by id" + lock, id));
        result.put("receivables", jdbc.queryForList("select receivable_no as receivableNo,amount,recovered_amount as recoveredAmount,status,created_at as createdAt from club_provider_receivable where aftersale_id=?" + lock, id));
        return result;
    }

    private void restoreOrderAfterRejected(Object orderId)
    {
        List<Map<String, Object>> logs = jdbc.queryForList("select from_status from club_order_log where order_id=? and to_status='refunding' order by id desc limit 1", orderId);
        String restore = logs.isEmpty() ? "pending" : text(logs.get(0).get("from_status"));
        if (!Arrays.asList("pending", "accepted", "serving", "completed").contains(restore)) restore = "pending";
        if("serving".equals(restore)||"accepted".equals(restore)) {
            Map<String,Object> order=jdbc.queryForMap("select provider_user_id,provider_type,provider_completed_at from club_order where id=?",orderId);
            if("player".equals(text(order.get("provider_type")))&&order.get("provider_completed_at")==null)
                ClubPlayerCapacity.assertAvailable(jdbc,order.get("provider_user_id"),orderId);
        }
        jdbc.update("update club_order set status=?,version=version+1 where id=? and status='refunding'", restore, orderId);
        jdbc.update("insert into club_order_log(order_id,from_status,to_status,operator_type,note) values(?,'refunding',?,'admin','售后申请被驳回，恢复原订单状态')", orderId, restore);
    }

    private void log(Long id, String from, String to, String type, Long operatorId, String note)
    {
        jdbc.update("insert into club_aftersale_log(aftersale_id,from_status,to_status,operator_type,operator_id,note) values(?,?,?,?,?,?)", id, from, to, type, operatorId, note);
    }

    private void notify(Object userId, String title, String content, Long id)
    {
        if (userId == null) return;
        jdbc.update("insert into club_message(user_id,message_type,title,content,reference_type,reference_id) values(?,?,?,?,?,?)",
                userId, "order", title, content, "aftersale", id);
    }

    private static String required(Map<String, Object> input, String key, String message)
    {
        String value = text(input.get(key));
        if (value.isEmpty()) throw new ServiceException(message);
        return value;
    }
    private static String requiredEither(Map<String, Object> input, String first, String second, String message)
    {
        String value = text(input.get(first));
        if (value.isEmpty()) value = text(input.get(second));
        if (value.isEmpty()) throw new ServiceException(message);
        return value;
    }
    private static Object first(Map<String, Object> input, String first, String second)
    {
        return input.containsKey(first) ? input.get(first) : input.get(second);
    }
    private static BigDecimal decimal(Object preferred, Object fallback)
    {
        Object value = preferred == null || text(preferred).isEmpty() ? fallback : preferred;
        try { return new BigDecimal(text(value)); }
        catch (Exception e) { throw new ServiceException("退款金额格式不正确"); }
    }
    private static String limited(Object value, int max)
    {
        String result = text(value);
        if (result.length() > max) throw new ServiceException("输入内容过长");
        return result;
    }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static Long number(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private static String randomDigits() { return String.valueOf(1000 + Math.abs(UUID.randomUUID().hashCode() % 9000)); }
}
