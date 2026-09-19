package com.ruoyi.club.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.club.web.ClubForbiddenException;

/** 订单履约、收益结算、退款冲正和角色工作台。 */
@Service
public class ClubBusinessService
{
    @org.springframework.beans.factory.annotation.Value("${club.payment-mode:disabled}")
    private String runtimePaymentMode;
    private final JdbcTemplate jdbc;
    private final ClubCatalogService catalog;

    public ClubBusinessService(JdbcTemplate jdbc, ClubCatalogService catalog)
    {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    public Map<String, Object> workbench(Long userId)
    {
        String role = requireRole(userId);
        if(!"player".equals(role))throw new ServiceException("独立商家工作台已停用，平台商品由后台统一管理");
        ensureRoleStatus(userId, role);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", role);
        result.put("serviceStatus", jdbc.queryForObject("select service_status from club_role_status where user_id=?", String.class, userId));
        Integer busy=jdbc.queryForObject("select count(1) from club_order where provider_user_id=? and provider_type='player' and "+ClubPlayerCapacity.occupied("club_order"),Integer.class,userId);
        result.put("isBusy",busy!=null&&busy>0);
        result.put("occupiedSeconds",jdbc.queryForObject("select "+ClubPlayerCapacity.playerElapsed("?"),Long.class,userId));
        result.put("wallet", walletSummary(userId));
        BigDecimal providerRate = new BigDecimal("100").subtract(configMoney("platform_commission_rate", new BigDecimal("20.00")));
        Map<String, Object> stats = jdbc.queryForMap(
                "select count(distinct o.id) totalOrders," +
                "sum(case when date(o.created_at)=curdate() then 1 else 0 end) todayOrders," +
                "coalesce(sum(case when o.status in ('pending','accepted','serving') then round(o.total_amount*?/100,2) else 0 end),0) expectedIncome," +
                "coalesce(sum(case when o.status='completed' then o.total_amount else 0 end),0) turnover," +
                "coalesce(sum(case when s.status='settled' then s.platform_fee else 0 end),0) platformFees," +
                "coalesce(sum(case when s.status='settled' then s.provider_income else 0 end),0) totalIncome " +
                "from club_order o left join club_order_settlement s on s.order_id=o.id where " + ownershipSql(role), providerRate, userId);
        result.put("stats", stats);
        result.put("orders", workbenchOrders(userId, "all"));
        if ("player".equals(role))
        {
            List<Map<String, Object>> profiles = jdbc.queryForList(
                    "select id,display_name as displayName,gender,age,city,intro,image,voice_url as voiceUrl,voice_seconds as voiceSeconds,online_status as onlineStatus,status from club_player_profile where user_id=?", userId);
            result.put("profile", profiles.isEmpty() ? null : profiles.get(0));
            result.put("services", playerServices(userId));
            result.put("products", playerProducts(userId));
        }
        return result;
    }

    /** Read-only catalogue, scoped to the authenticated player's canonical product binding. */
    public List<Map<String, Object>> playerProducts(Long userId)
    {
        requireRole(userId, "player");
        return jdbc.queryForList("select pr.id as productId,pr.name as productName,pr.image,pr.status as productStatus," +
                "coalesce(c.name,'未分类') as categoryName,sk.id as skuId,sk.name as skuName,sk.price,sk.stock,sk.status as skuStatus " +
                "from club_product pr join club_player_profile pp on pp.id=pr.bound_player_id " +
                "left join club_category c on c.code=pr.category_code " +
                "left join club_product_sku sk on sk.product_id=pr.id and sk.status<>'deleted' " +
                "where pp.user_id=? and pr.status<>'deleted' order by pr.id,sk.sort_no,sk.id", userId);
    }

    public List<Long> readyForAutoComplete()
    {
        int hours = configMoney("auto_complete_hours", new BigDecimal("24")).intValue();
        return jdbc.query("select id from club_order where status='serving' and provider_completed_at is not null and timestampdiff(hour,provider_completed_at,now())>=? order by id limit 100",
                (rs, n) -> rs.getLong(1), hours);
    }

    @Transactional
    public void autoCompleteOrder(Long orderId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from club_order where id=? for update", orderId);
        if (rows.isEmpty()) return;
        Map<String, Object> order = rows.get(0);
        if (!"serving".equals(text(order.get("status"))) || order.get("provider_completed_at") == null) return;
        jdbc.update("update club_order set status='completed',completed_at=now(),version=version+1 where id=? and status='serving'", orderId);
        logOrder(orderId, "serving", "completed", "system", null, "超过配置时间自动确认完成");
        settleLocked(orderId);
        notifyUser(order.get("user_id"), "订单已自动完成", "服务完成后超过确认时间，系统已自动确认并完成结算。", orderId);
    }

    public List<Map<String, Object>> workbenchOrders(Long userId, String status)
    {
        String role = requireRole(userId);
        String filter = status == null || "all".equals(status) ? "" : status;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select "+ClubPlayerCapacity.busy("o.provider_user_id","o.id")+" as playerBusy,"+ClubPlayerCapacity.elapsed("o")+" as occupiedSeconds,o.id,o.order_no as orderNo,o.product_name as productName,o.sku_name as skuName,o.quantity as qty," +
                "o.total_amount as totalAmount,o.status,o.provider_completed_at as providerCompletedAt,o.created_at as createdAt," +
                "o.game_id as gameId,o.game_nickname as gameNickname,case when o.status in ('pending','accepted','serving') then o.contact_name else '' end as contactName," +
                "case when o.status in ('pending','accepted','serving') then o.contact_phone else '' end as contactPhone,o.remark," +
                "o.payment_method as paymentMethod,o.payment_expires_at as paymentExpiresAt,o.platform_fee as platformFee,o.provider_income as providerIncome " +
                "from club_order o where " + ownershipSql(role) + " and o.status<>'unpaid' and (?='' or o.status=?) order by o.id desc limit 100",
                userId, filter, filter);
        for (Map<String, Object> row : rows) row.put("allowedActions", allowedActions(row));
        return rows;
    }

    /** Only the authenticated provider's orders; summary and pages share one accounting filter. */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String, Object> workbenchMetric(Long userId, String type, int page)
    {
        String role = requireRole(userId);
        if (page < 1 || page > 1000000) throw new ServiceException("页码不正确");
        String filter;
        String amount = "o.total_amount";
        BigDecimal rate = new BigDecimal("100").subtract(configMoney("platform_commission_rate", new BigDecimal("20.00")));
        List<Object> params = new ArrayList<>();
        switch (type)
        {
            case "today": filter = " and o.created_at>=curdate() and o.created_at<date_add(curdate(),interval 1 day)"; break;
            case "total": filter = ""; break;
            case "expected":
                filter = " and o.status in ('pending','accepted','serving')";
                amount = "round(o.total_amount*?/100,2)"; params.add(rate); break;
            case "turnover": filter = " and o.status='completed'"; break;
            case "fees": filter = " and s.status='settled'"; amount = "s.platform_fee"; break;
            case "income": filter = " and s.status='settled'"; amount = "s.provider_income"; break;
            default: throw new ServiceException("不支持的工作台明细类型");
        }
        params.add(userId);
        String from = " from club_order o left join club_order_settlement s on s.order_id=o.id where " + ownershipSql(role) + filter;
        Map<String, Object> result = new LinkedHashMap<>(jdbc.queryForMap(
                "select count(*) total,coalesce(sum(" + amount + "),0) amount,curdate() businessDate" + from, params.toArray()));
        List<Object> rowParams = new ArrayList<>(params);
        rowParams.add(50); rowParams.add((page - 1) * 50);
        result.put("rows", jdbc.queryForList("select o.id,o.order_no orderNo,o.product_name productName,o.sku_name skuName," +
                "o.quantity qty,o.status,o.created_at createdAt,o.total_amount totalAmount," + amount + " amount" + from +
                " order by o.id desc limit ? offset ?", rowParams.toArray()));
        result.put("page", page);
        result.put("hasMore", ((Number)result.get("total")).longValue() > (long)page * 50);
        result.put("type", type);
        result.put("providerRate", rate);
        return result;
    }

    public Map<String, Object> workbenchOrder(Long userId, Long orderId)
    {
        String role = requireRole(userId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select "+ClubPlayerCapacity.busy("o.provider_user_id","o.id")+" as playerBusy,"+ClubPlayerCapacity.elapsed("o")+" as occupiedSeconds,o.id,o.order_no as orderNo,o.product_id as productId,o.sku_id as skuId,o.product_name as productName,o.sku_name as skuName," +
                "o.product_image as productImage,o.quantity as qty,o.unit_price as unitPrice,o.original_amount as originalAmount,o.discount_amount as discountAmount," +
                "o.total_amount as totalAmount,o.game_id as gameId,o.game_nickname as gameNickname," +
                "case when o.status in ('pending','accepted','serving') then o.contact_name else '' end as contactName," +
                "case when o.status in ('pending','accepted','serving') then o.contact_phone else '' end as contactPhone," +
                "o.remark,o.status,o.payment_method as paymentMethod,o.payment_expires_at as paymentExpiresAt,o.paid_at as paidAt,o.created_at as createdAt," +
                "o.provider_completed_at as providerCompletedAt,o.completed_at as completedAt,o.platform_fee as platformFee,o.provider_income as providerIncome " +
                "from club_order o where o.id=? and " + ownershipSql(role), orderId, userId);
        if (rows.isEmpty()) throw new ClubForbiddenException("无权查看该订单");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put("allowedActions", allowedActions(result));
        result.put("logs", jdbc.queryForList(
                "select id,from_status as fromStatus,to_status as toStatus,operator_type as operatorType,operator_id as operatorId,note,created_at as createdAt " +
                "from club_order_log where order_id=? order by id", orderId));
        result.put("aftersale", jdbc.queryForList(
                "select id,aftersale_no as aftersaleNo,status,reason,description,refund_amount as refundAmount,provider_note as providerNote,review_note as reviewNote,updated_at as updatedAt " +
                "from club_aftersale where order_id=?", orderId));
        return result;
    }

    @Transactional
    public Map<String, Object> roleAction(Long userId, Long orderId, Map<String, Object> input)
    {
        String role = requireRole(userId);
        String action = required(input, "action", "请选择操作").toLowerCase();
        if (!Arrays.asList("accept", "reject", "start", "finish").contains(action))
        {
            throw new ServiceException("不支持的订单操作");
        }
        if("player".equals(role)&&Arrays.asList("accept","start").contains(action))jdbc.queryForList("select id from club_user where id=? for update",userId);
        String key = idempotency(input);
        if (!recordAction(userId, "order", orderId, action, key))
        {
            return roleOrder(userId, role, orderId, true);
        }
        Map<String, Object> order = ownedOrderForUpdate(userId, role, orderId);
        String current = text(order.get("status"));
        if (("accept".equals(action)||"start".equals(action))&&"player".equals(role))ClubPlayerCapacity.assertAvailable(jdbc,userId,orderId);
        if ("accept".equals(action))
        {
            transition(order, "pending", "accepted", role, userId, "服务方接单");
        }
        else if ("reject".equals(action))
        {
            throw new ServiceException("拒单必须提交正式退款审核，不能直接完成本地退款");
        }
        else if ("start".equals(action))
        {
            if(!Arrays.asList("pending","accepted").contains(current))throw illegal(current,"开始服务");
            transition(order, current, "serving", role, userId, "打手开始服务");
        }
        else
        {
            if (!"serving".equals(current)) throw illegal(current, "完成服务");
            if (order.get("provider_completed_at") == null)
            {
                jdbc.update("update club_order set provider_completed_at=now(),version=version+1 where id=? and status='serving' and provider_completed_at is null", orderId);
                logOrder(orderId, "serving", "serving", role, userId, "服务方已完成，等待用户确认");
                notifyUser(order.get("user_id"), "服务已完成", "服务方已完成订单，请确认完成。", orderId);
            }
        }
        return roleOrder(userId, role, orderId, false);
    }

    @Transactional
    public Map<String, Object> confirmOrder(Long userId, Long orderId, Map<String, Object> input)
    {
        String key = idempotency(input);
        if (!recordAction(userId, "order", orderId, "confirm", key))
        {
            return userOrder(userId, orderId, true);
        }
        Map<String, Object> order = userOrderForUpdate(userId, orderId);
        String current = text(order.get("status"));
        if ("completed".equals(current)) return userOrder(userId, orderId, true);
        if (!"serving".equals(current) || order.get("provider_completed_at") == null)
        {
            throw new ServiceException("服务方完成服务后才能确认订单");
        }
        jdbc.update("update club_order set status='completed',completed_at=now(),version=version+1 where id=? and status='serving'", orderId);
        logOrder(orderId, "serving", "completed", "user", userId, "用户确认服务完成");
        settleLocked(orderId);
        return userOrder(userId, orderId, false);
    }

    @Transactional
    public Map<String, Object> adminTransition(Long orderId, String target, String note, Long adminId)
    {
        if(Arrays.asList("accepted","serving").contains(target)) {
            List<Map<String,Object>> provider=jdbc.queryForList("select provider_user_id from club_order where id=? and provider_type='player'",orderId);
            if(!provider.isEmpty())jdbc.queryForList("select id from club_user where id=? for update",provider.get(0).get("provider_user_id"));
        }
        List<Map<String, Object>> rows = jdbc.queryForList("select * from club_order where id=? for update", orderId);
        if (rows.isEmpty()) throw new ServiceException("订单不存在");
        Map<String, Object> order = rows.get(0);
        String current = text(order.get("status"));
        if (current.equals(target)) return order;
        if ("refunded".equals(target))
        {
            throw new ServiceException("退款必须通过正式售后审核流程处理");
        }
        else if ("cancelled".equals(target))
        {
            throw new ServiceException("取消未付款订单必须通过支付关单流程处理");
        }
        else if ("accepted".equals(target)||"serving".equals(target)) {
            if("player".equals(text(order.get("provider_type"))))ClubPlayerCapacity.assertAvailable(jdbc,order.get("provider_user_id"),orderId);
            if("accepted".equals(target))transition(order,"pending",target,"admin",adminId,note);
            else {if(!Arrays.asList("pending","accepted").contains(current))throw illegal(current,target);transition(order,current,target,"admin",adminId,note);}
        }
        else if("pending".equals(target)) {
            if(!"accepted".equals(current))throw new ServiceException("只有已接单且尚未开始服务的订单可以退回待接单");
            if(note==null||note.trim().isEmpty())throw new ServiceException("请填写放弃接单原因");
            transition(order,"accepted","pending","admin",adminId,"放弃本次接单，保留付款等待重新安排："+note);
        }
        else if ("completed".equals(target))
        {
            if (!"serving".equals(current)) throw illegal(current, target);
            jdbc.update("update club_order set status='completed',provider_completed_at=coalesce(provider_completed_at,now()),completed_at=now(),version=version+1 where id=? and status='serving'", orderId);
            logOrder(orderId, current, target, "admin", adminId, note);
            settleLocked(orderId);
        }
        else throw illegal(current, target);
        notifyUser(order.get("user_id"), "订单状态已更新", "订单" + order.get("order_no") + "状态已更新为" + orderStateName(target), orderId);
        return jdbc.queryForMap("select * from club_order where id=?", orderId);
    }

    @Transactional
    public Map<String, Object> updateAvailability(Long userId, Map<String, Object> input)
    {
        jdbc.queryForList("select id from club_user where id=? for update", userId);
        String role = requireRole(userId);
        if(!"player".equals(role))throw new ServiceException("独立商家接单已停用，请使用打手工作台");
        String status = required(input, "status", "请选择服务状态");
        if("online".equals(status))ClubPlayerCapacity.assertAvailable(jdbc,userId,null);
        if (!Arrays.asList("online", "offline", "paused").contains(status)) throw new ServiceException("服务状态不正确");
        if ("online".equals(status) && "player".equals(role))
        {
            Map<String,Object> profile = playerProfile(userId);
            if (!"active".equals(text(profile.get("status")))) throw new ServiceException("打手资料已停用，请联系平台");
            Integer approved = jdbc.queryForObject("select count(1) from club_player_profile p where p.user_id=? and " + ClubPlayerEligibility.approved("p"), Integer.class, userId);
            if (approved == null || approved != 1) throw new ServiceException("入驻审核尚未通过，请在入驻审核中查看原因");
            List<String> missing = new ArrayList<>();
            for (String[] field : new String[][]{{"displayName","昵称"},{"image","头像"},{"intro","简介"},{"voiceUrl","语音"}})
                if (text(profile.get(field[0])).isEmpty()) missing.add(field[1]);
            if (!missing.isEmpty()) throw new ServiceException("请先补充：" + String.join("、", missing));
            Integer ready = jdbc.queryForObject("select count(1) from club_player_profile p where p.user_id=? and exists(" + ClubPlayerEligibility.activeSkus("p") + ")", Integer.class, userId);
            if (ready == null || ready != 1) throw new ServiceException("没有可接单商品，请平台绑定上架商品并启用规格；已删除或停用商品不能接单");
        }
        jdbc.update("insert into club_role_status(user_id,role_type,service_status) values(?,?,?) on duplicate key update role_type=values(role_type),service_status=values(service_status)", userId, role, status);
        if ("player".equals(role)) jdbc.update("update club_player_profile set online_status=? where user_id=?", "online".equals(status) ? 1 : 0, userId);
        return workbench(userId);
    }

    public Map<String, Object> playerProfile(Long userId)
    {
        requireRole(userId, "player");
        List<Map<String, Object>> rows = jdbc.queryForList("select id,display_name as displayName,gender,age,city,intro,image,voice_url as voiceUrl,voice_seconds as voiceSeconds,online_status as onlineStatus,status from club_player_profile where user_id=?", userId);
        if (rows.isEmpty()) throw new ServiceException("陪玩档案不存在");
        return rows.get(0);
    }

    @Transactional
    public Map<String, Object> savePlayerProfile(Long userId, Map<String, Object> input)
    {
        requireRole(userId, "player");
        List<Map<String, Object>> rows = jdbc.queryForList("select * from club_player_profile where user_id=? and status='active' for update", userId);
        if (rows.isEmpty()) throw new ServiceException("陪玩档案不存在或已停用");
        Map<String, Object> current = rows.get(0);
        String name = input.containsKey("displayName") ? required(input, "displayName", "请填写陪玩昵称") : text(current.get("display_name"));
        String image = input.containsKey("image") ? text(input.get("image")) : text(current.get("image"));
        String intro = input.containsKey("intro") ? text(input.get("intro")) : text(current.get("intro"));
        String voice = input.containsKey("voiceUrl") ? text(input.get("voiceUrl")) : text(current.get("voice_url"));
        String gender = input.containsKey("gender") ? text(input.get("gender")) : text(current.get("gender"));
        if (!gender.isEmpty() && !Arrays.asList("male", "female", "unknown").contains(gender)) throw new ServiceException("性别选项不正确");
        int age = input.containsKey("age") ? integer(input.get("age"), 0) : integer(current.get("age"), 0);
        if (age < 0 || age > 100) throw new ServiceException("年龄范围不正确");
        if (integer(current.get("online_status"), 0) == 1 && (name.isEmpty() || image.isEmpty() || intro.isEmpty() || voice.isEmpty()))
            throw new ServiceException("在线接单时不能清空头像、昵称、简介或语音");
        jdbc.update("update club_player_profile set display_name=?,gender=?,age=?,city=?,intro=?,image=?,voice_url=?,voice_seconds=? where id=?",
                name, gender.isEmpty() ? "unknown" : gender, age, input.containsKey("city") ? text(input.get("city")) : text(current.get("city")),
                intro, image, voice, input.containsKey("voiceSeconds") ? integer(input.get("voiceSeconds"), 0) : integer(current.get("voice_seconds"), 0), current.get("id"));
        return playerProfile(userId);
    }

    public Map<String, Object> playerServices(Long userId)
    {
        requireRole(userId, "player");
        Long playerId = jdbc.queryForObject("select id from club_player_profile where user_id=?", Long.class, userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("selected", jdbc.queryForList("select s.id,p.id as productId,p.name as productName,s.id as skuId,s.name as skuName,s.status " +
                "from club_product p join club_product_sku s on s.product_id=p.id and s.status='active' " +
                "where p.bound_player_id=? and p.status='active' order by p.id,s.sort_no,s.id", playerId));
        result.put("available", result.get("selected"));
        return result;
    }

    private void settleLocked(Long orderId)
    {
        if (!jdbc.queryForList("select id from club_order_settlement where order_id=?", orderId).isEmpty()) return;
        Map<String, Object> order = jdbc.queryForMap("select * from club_order where id=? for update", orderId);
        if (!"completed".equals(text(order.get("status")))) throw new ServiceException("只有已完成订单可以结算");
        Long providerId = order.get("provider_user_id") == null ? null : longValue(order.get("provider_user_id"));
        String providerType = text(order.get("provider_type"));
        if ("platform".equals(providerType))
        {
            if (providerId != null) throw new ServiceException("平台订单不应绑定服务方钱包");
            BigDecimal gross = money(order.get("total_amount"));
            // Platform receipts are recorded in the settlement ledger, never in
            // an administrator/customer wallet. Existing provider orders are unchanged.
            jdbc.update("insert into club_order_settlement(settlement_no,order_id,provider_user_id,provider_type,gross_amount,commission_rate,platform_fee,provider_income,status,settled_at,available_at) " +
                            "values(?,?,null,'platform',?,100,?,0,'settled',now(),now())",
                    "ST" + System.currentTimeMillis() + randomDigits(), orderId, gross, gross);
            jdbc.update("update club_order set platform_fee=?,provider_income=0 where id=?", gross, orderId);
            awardExperience(order);
            return;
        }
        if (providerId == null) throw new ServiceException("订单未绑定可结算的陪玩或商家账号");
        BigDecimal gross = money(order.get("total_amount"));
        BigDecimal rate = configMoney("platform_commission_rate", new BigDecimal("20.00"));
        if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(new BigDecimal("100")) > 0) throw new ServiceException("平台佣金配置不正确");
        BigDecimal fee = gross.multiply(rate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        BigDecimal income = gross.subtract(fee);
        String no = "ST" + System.currentTimeMillis() + randomDigits();
        int protectionHours = Math.max(0, configMoney("aftersale_protection_hours", new BigDecimal("24")).intValue());
        jdbc.update("insert into club_order_settlement(settlement_no,order_id,provider_user_id,provider_type,gross_amount,commission_rate,platform_fee,provider_income,status,settled_at,available_at) " +
                        "values(?,?,?,?,?,?,?,?,'protected',now(),date_add(now(),interval ? hour))",
                no, orderId, providerId, providerType, gross, rate, fee, income, protectionHours);
        jdbc.update("insert ignore into club_wallet(user_id,balance,frozen,total_income,total_withdrawn) values(?,0,0,0,0)", providerId);
        Map<String, Object> before = jdbc.queryForMap("select balance,frozen from club_wallet where user_id=? for update", providerId);
        BigDecimal balanceBefore = money(before.get("balance"));
        BigDecimal frozenAfter = money(before.get("frozen")).add(income);
        jdbc.update("update club_wallet set frozen=frozen+?,total_income=total_income+?,version=version+1 where user_id=?", income, income, providerId);
        jdbc.update("insert into club_wallet_record(user_id,record_type,amount,balance_before,balance_after,frozen_after,reference_no,order_id,counterparty_type,description) values(?,?,?,?,?,?,?,?,?,?)",
                providerId, "income_protected", income, balanceBefore, balanceBefore, frozenAfter, no, orderId, providerType, "订单收益进入售后保护期");
        jdbc.update("update club_order set platform_fee=?,provider_income=?,aftersale_protected_until=date_add(now(),interval ? hour) where id=?", fee, income, protectionHours, orderId);
        awardExperience(order);
    }

    /** 已完成订单按实付金额累计经验；订单维度唯一，重试不会重复发放。 */
    private void awardExperience(Map<String, Object> order)
    {
        Long orderId = longValue(order.get("id"));
        Long userId = longValue(order.get("user_id"));
        int delta = Math.max(1, money(order.get("total_amount")).setScale(0, RoundingMode.DOWN).intValue());
        Map<String, Object> user = jdbc.queryForMap("select experience from club_user where id=? for update", userId);
        int before = user.get("experience") == null ? 0 : ((Number) user.get("experience")).intValue();
        int after = before + delta;
        int inserted = jdbc.update("insert ignore into club_experience_record(user_id,order_id,event_type,delta,before_experience,after_experience,rule_version) values(?,?,'order_completed',?,?,?,'v1_amount_floor')",
                userId, orderId, delta, before, after);
        if (inserted == 1)
            jdbc.update("update club_user set experience=?,level_name=? where id=?", after, levelFor(after), userId);
    }

    /** 全额退款精确冲销原订单经验；不存在原发放记录时不产生负经验。 */
    private void reverseExperience(Long orderId)
    {
        List<Map<String, Object>> earned = jdbc.queryForList(
                "select user_id,delta from club_experience_record where order_id=? and event_type='order_completed' for update", orderId);
        if (earned.isEmpty()) return;
        Long userId = longValue(earned.get(0).get("user_id"));
        int delta = ((Number) earned.get(0).get("delta")).intValue();
        Map<String, Object> user = jdbc.queryForMap("select experience from club_user where id=? for update", userId);
        int before = user.get("experience") == null ? 0 : ((Number) user.get("experience")).intValue();
        int after = Math.max(0, before - delta);
        int inserted = jdbc.update("insert ignore into club_experience_record(user_id,order_id,event_type,delta,before_experience,after_experience,rule_version) values(?,?,'order_refunded',?,?,?,'v1_amount_floor')",
                userId, orderId, -delta, before, after);
        if (inserted == 1)
            jdbc.update("update club_user set experience=?,level_name=? where id=?", after, levelFor(after), userId);
    }

    static String levelFor(int experience)
    {
        if (experience >= 5000) return "LV.5 巅峰王者";
        if (experience >= 1500) return "LV.4 名震一方";
        if (experience >= 500) return "LV.3 独当一面";
        if (experience >= 100) return "LV.2 渐入佳境";
        return "LV.1 初入江湖";
    }

    public List<Long> readyProtectedSettlements()
    {
        return jdbc.query("select s.id from club_order_settlement s join club_order o on o.id=s.order_id " +
                        "where s.status='protected' and s.available_at<=now() and o.status='completed' order by s.id limit 100",
                (rs, n) -> rs.getLong(1));
    }

    @Transactional
    public void releaseProtectedSettlement(Long settlementId)
    {
        // Use the same order -> settlement -> wallet lock order as refund reversal.
        // An open dispute continues to protect this income after the normal deadline.
        List<Map<String, Object>> orders = jdbc.queryForList(
                "select status from club_order where id=(select order_id from club_order_settlement where id=?) for update", settlementId);
        if (orders.isEmpty() || !"completed".equals(text(orders.get(0).get("status")))) return;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from club_order_settlement where id=? and status='protected' and available_at<=now() for update", settlementId);
        if (rows.isEmpty()) return;
        Map<String, Object> settlement = rows.get(0);
        Long providerId = longValue(settlement.get("provider_user_id"));
        BigDecimal amount = money(settlement.get("provider_income")).subtract(money(settlement.get("reversed_income")));
        if (amount.compareTo(BigDecimal.ZERO) <= 0)
        {
            jdbc.update("update club_order_settlement set status='reversed',reversed_at=coalesce(reversed_at,now()) where id=?", settlementId);
            return;
        }
        Map<String, Object> wallet = jdbc.queryForMap("select balance,frozen from club_wallet where user_id=? for update", providerId);
        BigDecimal before = money(wallet.get("balance"));
        BigDecimal frozenBefore = money(wallet.get("frozen"));
        if (frozenBefore.compareTo(amount) < 0) throw new ServiceException("售后保护资金不足，不能释放结算");
        BigDecimal recovered = recoverReceivables(providerId, amount, settlement);
        BigDecimal released = amount.subtract(recovered);
        BigDecimal after = before.add(released);
        BigDecimal frozenAfter = frozenBefore.subtract(amount);
        jdbc.update("update club_wallet set balance=?,frozen=?,version=version+1 where user_id=?", after, frozenAfter, providerId);
        if (recovered.compareTo(BigDecimal.ZERO) > 0)
        {
            jdbc.update("insert into club_wallet_record(user_id,record_type,amount,balance_before,balance_after,frozen_after,reference_no,order_id,counterparty_type,description) values(?,?,?,?,?,?,?,?,?,?)",
                    providerId, "receivable_recovery", recovered.negate(), before, before, frozenAfter,
                    "RC-" + settlement.get("settlement_no"), settlement.get("order_id"), "receivable", "新结算收入抵扣退款追偿");
        }
        jdbc.update("insert into club_wallet_record(user_id,record_type,amount,balance_before,balance_after,frozen_after,reference_no,order_id,counterparty_type,description) values(?,?,?,?,?,?,?,?,?,?)",
                providerId, "income_release", released, before, after, frozenAfter, settlement.get("settlement_no"), settlement.get("order_id"), settlement.get("provider_type"),
                recovered.compareTo(BigDecimal.ZERO) > 0 ? "售后保护期结束，收入先抵扣退款追偿后转为可提现余额" : "售后保护期结束，收益转为可提现余额");
        jdbc.update("update club_order_settlement set status='settled' where id=? and status='protected'", settlementId);
    }

    /** 新收入释放前，按最早未结清应收自动回收；本方法必须在钱包和结算事务锁内调用。 */
    private BigDecimal recoverReceivables(Long providerId, BigDecimal available, Map<String, Object> settlement)
    {
        if (available.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        List<Map<String, Object>> debts = jdbc.queryForList(
                "select * from club_provider_receivable where provider_user_id=? and status='outstanding' and recovered_amount<amount order by id for update", providerId);
        BigDecimal remaining = available;
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> debt : debts)
        {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal outstanding = money(debt.get("amount")).subtract(money(debt.get("recovered_amount")));
            BigDecimal recovered = outstanding.min(remaining);
            if (recovered.compareTo(BigDecimal.ZERO) <= 0) continue;
            // MySQL evaluates single-table assignments left to right; decide the state
            // from the old amount before incrementing it, so this recovery is counted once.
            int changed = jdbc.update("update club_provider_receivable set status=case when recovered_amount+?>=amount then 'recovered' else 'outstanding' end,recovered_amount=recovered_amount+?,updated_at=now() where id=? and status='outstanding' and recovered_amount+?<=amount",
                    recovered, recovered, debt.get("id"), recovered);
            if (changed != 1) throw new ServiceException("追偿余额发生变化，请稍后重试");
            String recoveryNo = "RC" + System.currentTimeMillis() + randomDigits();
            jdbc.update("insert into club_provider_receivable_recovery(recovery_no,receivable_id,provider_user_id,settlement_id,order_id,aftersale_id,amount,recovery_type,reason) values(?,?,?,?,?,?,?,'income_offset','新结算收入自动抵扣退款追偿')",
                    recoveryNo, debt.get("id"), providerId, settlement.get("id"), settlement.get("order_id"), debt.get("aftersale_id"), recovered);
            total = total.add(recovered);
            remaining = remaining.subtract(recovered);
        }
        return total;
    }

    private void reverseSettlement(Long orderId, Long aftersaleId, BigDecimal refundAmount, BigDecimal orderAmount)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from club_order_settlement where order_id=? for update", orderId);
        if (rows.isEmpty() || !Arrays.asList("protected", "settled").contains(text(rows.get(0).get("status")))) return;
        Map<String, Object> settlement = rows.get(0);
        if ("platform".equals(text(settlement.get("provider_type"))))
        {
            if (settlement.get("provider_user_id") != null || money(settlement.get("provider_income")).signum() != 0)
                throw new ServiceException("平台结算数据不一致，不能退款");
            jdbc.update("update club_order_settlement set status='reversed',refunded_amount=?,reversed_at=now() where id=? and status='settled'",
                    refundAmount, settlement.get("id"));
            jdbc.update("update club_order set platform_fee=0,provider_income=0 where id=?", orderId);
            return;
        }
        Long userId = longValue(settlement.get("provider_user_id"));
        BigDecimal originalIncome = money(settlement.get("provider_income"));
        BigDecimal alreadyReversed = money(settlement.get("reversed_income"));
        BigDecimal amount = originalIncome.multiply(refundAmount).divide(orderAmount, 2, RoundingMode.HALF_UP)
                .min(originalIncome.subtract(alreadyReversed));
        Map<String, Object> wallet = jdbc.queryForMap("select balance,frozen,total_income from club_wallet where user_id=? for update", userId);
        BigDecimal before = money(wallet.get("balance"));
        BigDecimal frozenBefore = money(wallet.get("frozen"));
        boolean protectedIncome = "protected".equals(text(settlement.get("status")));
        if (!protectedIncome && before.compareTo(amount) < 0)
        {
            cancelPendingWithdrawalsForRefund(userId, amount.subtract(before));
            wallet = jdbc.queryForMap("select balance,frozen,total_income from club_wallet where user_id=? for update", userId);
            before = money(wallet.get("balance"));
            frozenBefore = money(wallet.get("frozen"));
        }
        BigDecimal recovered = protectedIncome ? amount.min(frozenBefore) : amount.min(before);
        BigDecimal after = protectedIncome ? before : before.subtract(recovered);
        BigDecimal frozenAfter = protectedIncome ? frozenBefore.subtract(recovered) : frozenBefore;
        jdbc.update("update club_wallet set balance=?,frozen=?,total_income=greatest(0,total_income-?),version=version+1 where user_id=?",
                after, frozenAfter, amount, userId);
        jdbc.update("insert into club_wallet_record(user_id,record_type,amount,balance_before,balance_after,frozen_after,reference_no,order_id,counterparty_type,description) values(?,?,?,?,?,?,?,?,?,?)",
                userId, "settlement_reversal", recovered.negate(), before, after, frozenAfter, "RV-" + settlement.get("settlement_no"), orderId, settlement.get("provider_type"), "订单退款收益冲正");
        BigDecimal shortage = amount.subtract(recovered);
        if (shortage.compareTo(BigDecimal.ZERO) > 0)
        {
            if (aftersaleId == null) throw new ServiceException("服务方可用资金不足，必须通过售后审核流程退款");
            String receivableNo = "AR" + System.currentTimeMillis() + randomDigits();
            jdbc.update("insert ignore into club_provider_receivable(receivable_no,provider_user_id,order_id,aftersale_id,amount,status) values(?,?,?,?,?,'outstanding')",
                    receivableNo, userId, orderId, aftersaleId, shortage);
        }
        jdbc.update("update club_order_settlement set reversed_income=reversed_income+?,refunded_amount=refunded_amount+?," +
                "status=case when reversed_income+?>=provider_income then 'reversed' else status end," +
                "reversed_at=case when reversed_income+?>=provider_income then now() else reversed_at end where id=?",
                amount, refundAmount, amount, amount, settlement.get("id"));
        jdbc.update("update club_order set provider_income=greatest(0,coalesce(provider_income,0)-?) where id=?", amount, orderId);
    }

    /** 退款优先拦截尚未出款的提现，避免先放款再生成不必要的追偿欠款。 */
    private void cancelPendingWithdrawalsForRefund(Long userId, BigDecimal required)
    {
        if (required.compareTo(BigDecimal.ZERO) <= 0) return;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,withdrawal_no,amount from club_withdrawal where user_id=? and status='pending' order by id for update", userId);
        BigDecimal released = BigDecimal.ZERO;
        for (Map<String, Object> row : rows)
        {
            if (released.compareTo(required) >= 0) break;
            BigDecimal value = money(row.get("amount"));
            Map<String, Object> before = jdbc.queryForMap("select balance,frozen from club_wallet where user_id=? for update", userId);
            if (money(before.get("frozen")).compareTo(value) < 0) continue;
            if (jdbc.update("update club_withdrawal set status='rejected',review_note='关联订单退款，系统自动取消未出款提现',reviewed_at=now() where id=? and status='pending'", row.get("id")) != 1) continue;
            BigDecimal balanceBefore = money(before.get("balance"));
            BigDecimal balanceAfter = balanceBefore.add(value);
            BigDecimal frozenAfter = money(before.get("frozen")).subtract(value);
            jdbc.update("update club_wallet set balance=?,frozen=?,version=version+1 where user_id=?", balanceAfter, frozenAfter, userId);
            jdbc.update("insert into club_wallet_record(user_id,record_type,amount,balance_before,balance_after,frozen_after,reference_no,counterparty_type,description) values(?,?,?,?,?,?,?,?,?)",
                    userId, "withdraw_auto_cancel", value, balanceBefore, balanceAfter, frozenAfter, row.get("withdrawal_no"), "withdrawal", "关联订单退款，未出款提现自动取消");
            released = released.add(value);
        }
    }

    private void refundLocked(Map<String, Object> order, String operatorType, Long operatorId, String note, Long aftersaleId,
            BigDecimal refundAmount)
    {
        refundLocked(order,operatorType,operatorId,note,aftersaleId,refundAmount,false);
    }

    private void refundLocked(Map<String, Object> order, String operatorType, Long operatorId, String note, Long aftersaleId,
            BigDecimal refundAmount, boolean appleRefund)
    {
        Long orderId = longValue(order.get("id"));
        String current = text(order.get("status"));
        if ("refunded".equals(current)) return;
        boolean formalAfterSale = aftersaleId != null && "refunding".equals(current);
        if (!formalAfterSale)
        {
            if (!Arrays.asList("pending", "accepted", "serving", "completed").contains(current)) throw illegal(current, "退款");
            jdbc.update("update club_order set status='refunding',version=version+1 where id=? and status=?", orderId, current);
            logOrder(orderId, current, "refunding", operatorType, operatorId, note);
        }
        List<Map<String, Object>> payments = jdbc.queryForList("select id,mode,amount,payment_no,paid_at,refund_status from club_payment where order_id=? and status='success' for update", orderId);
        if (payments.size() != 1) throw new ServiceException("订单没有唯一的可退款支付记录");
        Map<String, Object> paymentRow = payments.get(0);
        BigDecimal paidAmount = money(paymentRow.get("amount"));
        if (refundAmount.compareTo(paidAmount) != 0)
            throw new ServiceException("当前仅支持全额退款，退款金额必须等于订单实付金额");
        String paymentMode = text(paymentRow.get("mode"));
        boolean simulated="simulation".equals(runtimePaymentMode)&&"mock_wechat".equals(paymentMode);
        if (!simulated&&!Arrays.asList("wechat", "balance").contains(paymentMode))
            throw new ServiceException("该历史支付记录不可执行资金退款，请先人工核对账目");
        boolean realWechat = "wechat".equals(paymentMode);
        if (realWechat && !"success".equals(text(paymentRow.get("refund_status"))))
            throw new ServiceException("微信退款尚未确认成功，不能提前完成本地退款");
        if ("balance".equals(paymentMode)) refundCustomerBalance(order, paymentRow, refundAmount);
        int payment = jdbc.update("update club_payment set status=?,refunded_amount=?,refunded_at=now() where id=? and status='success'",
                "refunded", refundAmount, paymentRow.get("id"));
        if (payment != 1) throw new ServiceException("订单没有可退款的支付记录");
        jdbc.update("insert into club_payment_audit(payment_id,order_id,user_id,action,result_status,request_id,detail) values(?,?,?,?,?,?,?)",
                paymentRow.get("id"), orderId, order.get("user_id"), "refund", "refunded",
                aftersaleId == null ? "REFUND-ORDER-" + orderId : "REFUND-AFTERSALE-" + aftersaleId,
                (appleRefund?"Apple支付已确认全额退款，金额：":simulated?"模拟支付退款（无真实资金），金额：":realWechat ? "微信支付原路全额退款，金额：" : "钱包余额全额退回，金额：") + refundAmount);
        reverseSettlement(orderId, aftersaleId, refundAmount, paidAmount);
        jdbc.update("update club_teen_daily_spend set used_amount=greatest(0,used_amount-?),updated_at=now() where user_id=? and spend_date=date(?)",
                refundAmount, order.get("user_id"), paymentRow.get("paid_at"));
        returnUsedCoupon(orderId);
        jdbc.update("update club_product_sku set stock=stock+? where id=?", order.get("quantity"), order.get("sku_id"));
        catalog.syncProductSummary(longValue(order.get("product_id")));
        jdbc.update("update club_product set sales=greatest(0,sales-?) where id=?", order.get("quantity"), order.get("product_id"));
        jdbc.update("update club_order set status='refunded',refunded_at=now(),version=version+1 where id=? and status='refunding'", orderId);
        reverseExperience(orderId);
        logOrder(orderId, "refunding", "refunded", operatorType, operatorId, (appleRefund?"Apple退款及本地冲正完成，金额：":simulated?"模拟支付退款完成，金额：":realWechat ? "微信原路退款完成，退款金额：" : "钱包余额退款完成，退款金额：") + refundAmount);
        notifyUser(order.get("user_id"), appleRefund?"苹果支付退款完成":simulated?"模拟退款完成":realWechat ? "微信退款完成" : "余额退款完成",
                appleRefund?"Apple已确认订单退款"+refundAmount+"元，到账情况请以Apple账单为准。":simulated?"模拟支付退款已完成，没有真实资金变动。":realWechat ? "订单已原路退回微信支付" + refundAmount + "元。" : "订单退款" + refundAmount + "元已退回钱包余额。", orderId);
    }

    /** Called only after ClubAppleRefundService verifies a completed external refund. */
    @Transactional
    public void executeUnbookedAppleRefund(Long orderId, Long paymentId, BigDecimal amount, String transactionId, String note)
    {
        Map<String,Object> order=jdbc.queryForMap("select * from club_order where id=? for update",orderId);
        Map<String,Object> payment=jdbc.queryForMap("select * from club_payment where id=? and order_id=? for update",paymentId,orderId);
        if(!"unpaid".equals(order.get("status")) || !"created".equals(payment.get("status"))
                || !"wechat".equals(payment.get("mode")) || !"success".equals(payment.get("refund_status"))
                || !ClubVirtualPayGateway.owns(text(payment.get("payment_no")))
                || !Long.valueOf(1L).equals(longValue(payment.get("stock_reserved")))
                || money(payment.get("amount")).compareTo(amount)!=0
                || money(order.get("total_amount")).compareTo(amount)!=0
                || !jdbc.queryForList("select id from club_order_settlement where order_id=?",orderId).isEmpty())
            throw new ServiceException("苹果退款对应未入账订单状态异常，请人工核对");
        if(jdbc.update("update club_payment set status='refunded',mock_transaction_no=?,refunded_amount=?,refunded_at=now(),stock_reserved=0 where id=? and status='created' and stock_reserved=1",
                transactionId,amount,paymentId)!=1)throw new ServiceException("苹果退款支付记录状态发生变化");
        releaseReservedCoupon(orderId);
        jdbc.update("update club_product_sku set stock=stock+? where id=?",order.get("quantity"),order.get("sku_id"));
        catalog.syncProductSummary(longValue(order.get("product_id")));
        jdbc.update("update club_order set status='refunded',refunded_at=now(),version=version+1 where id=? and status='unpaid'",orderId);
        logOrder(orderId,"unpaid","refunded","apple",null,note+"；付款尚未本地入账，已释放预占库存和优惠券，未产生服务收益");
        notifyUser(order.get("user_id"),"苹果支付退款完成","Apple已确认全额退款；订单预占库存及优惠券已释放，到账情况请以Apple账单为准。",orderId);
    }

    @Transactional
    public void executeExternalAppleRefund(Long orderId, Long aftersaleId, BigDecimal refundAmount, String note)
    {
        Map<String,Object> order=jdbc.queryForMap("select * from club_order where id=? for update",orderId);
        refundLocked(order,"apple",null,note,aftersaleId,refundAmount,true);
    }

    @Transactional
    public Map<String, Object> executeApprovedRefund(Long orderId, Long adminId, Long aftersaleId, BigDecimal refundAmount, String note)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from club_order where id=? for update", orderId);
        if (rows.isEmpty()) throw new ServiceException("订单不存在");
        refundLocked(rows.get(0), "admin", adminId, note, aftersaleId, refundAmount);
        return jdbc.queryForMap("select * from club_order where id=?", orderId);
    }

    private void refundCustomerBalance(Map<String, Object> order, Map<String, Object> payment, BigDecimal amount)
    {
        Long userId = longValue(order.get("user_id"));
        Map<String, Object> wallet = jdbc.queryForMap("select balance,frozen from club_wallet where user_id=? for update", userId);
        BigDecimal before = money(wallet.get("balance"));
        BigDecimal after = before.add(amount);
        jdbc.update("update club_wallet set balance=?,version=version+1 where user_id=?", after, userId);
        jdbc.update("insert into club_wallet_record(user_id,record_type,amount,balance_before,balance_after,frozen_after,reference_no,order_id,counterparty_type,description) values(?,?,?,?,?,?,?,?,?,?)",
                userId, "order_refund", amount, before, after, wallet.get("frozen"), payment.get("payment_no"), order.get("id"), "order", "余额支付订单退款");
    }

    private void transition(Map<String, Object> order, String expected, String target, String operatorType, Long operatorId, String note)
    {
        String current = text(order.get("status"));
        if (!expected.equals(current)) throw illegal(current, target);
        if (jdbc.update("update club_order set status=?,version=version+1 where id=? and status=?", target, order.get("id"), expected) != 1)
            throw new ServiceException("订单状态发生变化，请刷新后重试");
        logOrder(longValue(order.get("id")), expected, target, operatorType, operatorId, note);
        notifyUser(order.get("user_id"), "订单状态已更新", "订单" + order.get("order_no") + "状态已更新为" + orderStateName(target), longValue(order.get("id")));
    }

    private void releaseReservedCoupon(Long orderId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from club_order_coupon where order_id=? and status='reserved' for update", orderId);
        if (rows.isEmpty()) return;
        Map<String, Object> row = rows.get(0);
        jdbc.update("update club_coupon_issue set reserved_count=greatest(0,reserved_count-1) where id=?", row.get("coupon_issue_id"));
        jdbc.update("update club_order_coupon set status='released' where order_id=?", orderId);
    }

    private void returnUsedCoupon(Long orderId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from club_order_coupon where order_id=? for update", orderId);
        if (rows.isEmpty()) return;
        Map<String, Object> row = rows.get(0);
        String status = text(row.get("status"));
        if ("reserved".equals(status)) releaseReservedCoupon(orderId);
        else if ("used".equals(status))
        {
            jdbc.update("update club_coupon_issue set remaining_count=remaining_count+1,status='active' where id=?", row.get("coupon_issue_id"));
            jdbc.update("update club_coupon c join club_coupon_issue i on i.coupon_id=c.id set c.used_count=greatest(0,c.used_count-1) where i.id=?", row.get("coupon_issue_id"));
            jdbc.update("update club_order_coupon set status='returned' where order_id=?", orderId);
        }
    }

    public void consumeReservedCoupon(Long orderId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from club_order_coupon where order_id=? and status='reserved' for update", orderId);
        if (rows.isEmpty()) return;
        Map<String, Object> row = rows.get(0);
        if (jdbc.update("update club_coupon_issue set reserved_count=reserved_count-1,remaining_count=remaining_count-1,status=case when remaining_count=0 then 'used' else 'active' end where id=? and reserved_count>0 and remaining_count>0", row.get("coupon_issue_id")) != 1)
            throw new ServiceException("优惠券状态发生变化，请重新下单");
        jdbc.update("update club_order_coupon set status='used' where order_id=?", orderId);
        jdbc.update("update club_coupon c join club_coupon_issue i on i.coupon_id=c.id set c.used_count=c.used_count+1 where i.id=?", row.get("coupon_issue_id"));
    }

    public void releaseCoupon(Long orderId) { releaseReservedCoupon(orderId); }

    private Map<String, Object> userOrderForUpdate(Long userId, Long orderId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from club_order where id=? and user_id=? for update", orderId, userId);
        if (rows.isEmpty()) throw new ServiceException("订单不存在");
        return rows.get(0);
    }

    private Map<String, Object> ownedOrderForUpdate(Long userId, String role, Long orderId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("select o.* from club_order o where o.id=? and " + ownershipSql(role) + " for update", orderId, userId);
        if (rows.isEmpty()) throw new ClubForbiddenException("无权操作该订单");
        return rows.get(0);
    }

    private Map<String, Object> roleOrder(Long userId, String role, Long orderId, boolean idempotent)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("select "+ClubPlayerCapacity.busy("o.provider_user_id","o.id")+" as playerBusy,"+ClubPlayerCapacity.elapsed("o")+" as occupiedSeconds,o.id,o.order_no as orderNo,o.product_name as productName,o.total_amount as totalAmount,o.status,o.provider_completed_at as providerCompletedAt,o.platform_fee as platformFee,o.provider_income as providerIncome from club_order o where o.id=? and " + ownershipSql(role), orderId, userId);
        if (rows.isEmpty()) throw new ClubForbiddenException("无权操作该订单");
        Map<String, Object> result = new HashMap<>(rows.get(0));
        result.put("idempotent", idempotent);
        return result;
    }

    private Map<String, Object> userOrder(Long userId, Long orderId, boolean idempotent)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("select id,order_no as orderNo,total_amount as totalAmount,status,platform_fee as platformFee,provider_income as providerIncome from club_order where id=? and user_id=?", orderId, userId);
        if (rows.isEmpty()) throw new ServiceException("订单不存在");
        Map<String, Object> result = new HashMap<>(rows.get(0));
        result.put("idempotent", idempotent);
        return result;
    }

    private String ownershipSql(String role)
    {
        return "o.provider_user_id=? and o.provider_type='" + ("player".equals(role) ? "player" : "merchant") + "' and " + ClubOrderVisibility.operational("o");
    }

    private List<String> allowedActions(Map<String, Object> order)
    {
        String status = text(order.get("status"));
        boolean busy=Boolean.TRUE.equals(order.get("playerBusy"))||"1".equals(String.valueOf(order.get("playerBusy")));
        if ("pending".equals(status)) return busy?Arrays.asList("reject"):Arrays.asList("start", "reject");
        if ("accepted".equals(status)) return busy?java.util.Collections.emptyList():Arrays.asList("start");
        if ("serving".equals(status) && order.get("providerCompletedAt") == null && order.get("provider_completed_at") == null)
            return Arrays.asList("finish");
        return java.util.Collections.emptyList();
    }

    private String requireRole(Long userId)
    {
        List<String> roles = jdbc.query("select user_type from club_user where id=? and status='active'", (rs, n) -> rs.getString(1), userId);
        if (roles.isEmpty() || !Arrays.asList("player", "merchant").contains(roles.get(0))) throw new ServiceException("当前账号没有陪玩或商家工作台权限");
        return roles.get(0);
    }

    private void requireRole(Long userId, String expected)
    {
        if (!expected.equals(requireRole(userId))) throw new ServiceException("当前角色无权执行此操作");
    }

    private void ensureRoleStatus(Long userId, String role)
    {
        jdbc.update("insert ignore into club_role_status(user_id,role_type,service_status) values(?,?,'offline')", userId, role);
    }

    private Map<String, Object> walletSummary(Long userId)
    {
        jdbc.update("insert ignore into club_wallet(user_id,balance,frozen,total_income,total_withdrawn) values(?,0,0,0,0)", userId);
        Map<String,Object> result=jdbc.queryForMap("select balance,frozen,total_income as totalIncome,total_withdrawn as totalWithdrawn from club_wallet where user_id=?", userId);
        result.put("withdrawable",ClubWalletPolicy.withdrawable(result));
        return result;
    }

    private boolean recordAction(Long userId, String type, Long id, String action, String key)
    {
        return jdbc.update("insert ignore into club_action_request(user_id,business_type,business_id,action,idempotency_key) values(?,?,?,?,?)", userId, type, id, action, key) == 1;
    }

    private BigDecimal configMoney(String key, BigDecimal fallback)
    {
        List<String> rows = jdbc.query("select config_value from club_business_config where config_key=?", (rs, n) -> rs.getString(1), key);
        try { return rows.isEmpty() ? fallback : new BigDecimal(rows.get(0)); }
        catch (Exception e) { return fallback; }
    }

    private void logOrder(Long orderId, String from, String to, String operatorType, Long operatorId, String note)
    {
        jdbc.update("insert into club_order_log(order_id,from_status,to_status,operator_type,operator_id,note) values(?,?,?,?,?,?)", orderId, from, to, operatorType, operatorId, note);
    }

    private void notifyUser(Object userId, String title, String content, Long orderId)
    {
        jdbc.update("insert into club_message(user_id,message_type,title,content,reference_type,reference_id) values(?,?,?,?,?,?)",
                userId, "order", title, content, "order", orderId);
    }

    private Long insertAndKey(String sql, Object... args)
    {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection ->
        {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new ServiceException("新增数据失败，未取得主键");
        return key.longValue();
    }

    private String orderStateName(String state) { switch(state){case "pending":return "待接单";case "accepted":return "已接单";case "serving":return "服务中";case "completed":return "已完成";case "cancelled":return "已取消";case "refunding":return "退款处理中";default:return "已更新";} }
    private ServiceException illegal(String current, String target) { return new ServiceException("订单当前状态“" + current + "”不能执行“" + target + "”"); }
    private static String idempotency(Map<String, Object> input)
    {
        String key = required(input, "idempotencyKey", "缺少幂等键");
        if (key.length() > 96) throw new ServiceException("幂等键过长");
        return key;
    }
    private static String required(Map<String, Object> input, String key, String message)
    {
        String value = text(input.get(key));
        if (value.isEmpty()) throw new ServiceException(message);
        return value;
    }
    private static String empty(Object value, String fallback) { return text(value).isEmpty() ? fallback : text(value); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static BigDecimal money(Object value) { try { return new BigDecimal(text(value)); } catch (Exception e) { throw new ServiceException("金额格式不正确"); } }
    private static Long longValue(Object value) { try { return Long.valueOf(text(value)); } catch (Exception e) { throw new ServiceException("数据编号不正确"); } }
    private static Long optionalLong(Object value) { return text(value).isEmpty() ? null : longValue(value); }
    private static int integer(Object value, int fallback) { try { return text(value).isEmpty() ? fallback : Integer.parseInt(text(value)); } catch (Exception e) { return fallback; } }
    private static String randomDigits() { return String.valueOf(1000 + Math.abs(UUID.randomUUID().hashCode() % 9000)); }
}
