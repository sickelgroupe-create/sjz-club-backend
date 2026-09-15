package com.ruoyi.club.service;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.club.web.ClubConflictException;
import com.ruoyi.common.exception.ServiceException;

/** 后台业务管理。表名和可编辑列均使用服务端白名单，绝不接受客户端 SQL 片段。 */
@Service
public class ClubAdminService
{
    private final JdbcTemplate jdbc;
    private final ClubBusinessService business;
    private final ClubAfterSaleService afterSale;
    private final ClubCatalogService catalog;
    private final ClubRoleLifecycleService roleLifecycle;
    private final ClubIdentityCryptoService identityCrypto;
    @org.springframework.beans.factory.annotation.Autowired
    private ClubAppService app;
    private final Set<String> allowedPageRoutes;
    private final Map<String, Entity> entities = new HashMap<>();

    public ClubAdminService(JdbcTemplate jdbc, ClubBusinessService business, ClubAfterSaleService afterSale,
            ClubCatalogService catalog, ClubRoleLifecycleService roleLifecycle, ClubIdentityCryptoService identityCrypto)
    {
        this.jdbc = jdbc;
        this.business = business;
        this.afterSale = afterSale;
        this.catalog = catalog;
        this.roleLifecycle = roleLifecycle;
        this.identityCrypto = identityCrypto;
        this.allowedPageRoutes = loadPageRoutes();
        entities.put("users", new Entity("club_user", "account,nickname,phone,email", "created_at desc"));
        entities.put("shops", new Entity("club_shop", "name,description", "id desc"));
        entities.put("products", new Entity("club_product", "name,subtitle,category_code", "id desc"));
        entities.put("orders", new Entity("club_order", "order_no,product_name,game_nickname", "id desc"));
        entities.put("payments", new Entity("club_payment", "payment_no,mock_transaction_no", "id desc"));
        entities.put("applications", new Entity("club_application", "real_name,phone,display_name", "id desc"));
        entities.put("identities", new Entity("club_identity", "real_name_mask,id_no_mask", "submitted_at desc", "user_id"));
        entities.put("withdrawals", new Entity("club_withdrawal", "withdrawal_no", "id desc"));
        entities.put("content", new Entity("club_content", "title,subtitle,scene,content_type", "sort_no,id desc"));
        entities.put("reviews", new Entity("club_review", "content", "id desc"));
        entities.put("coupons", new Entity("club_coupon", "name", "id desc"));
        entities.put("messages", new Entity("club_message", "title,content", "id desc"));
        entities.put("players", new Entity("club_player_profile", "display_name,city,intro", "sort_no,id"));
        entities.put("categories", new Entity("club_category", "code,name,icon", "sort_no,id"));
        entities.put("homeEntries", new Entity("club_home_entry", "entry_code,name,icon", "sort_no,id"));
        entities.put("skus", new Entity("club_product_sku", "name", "product_id,id"));
        entities.put("wallets", new Entity("club_wallet", "user_id", "updated_at desc", "user_id"));
        entities.put("walletRecords", new Entity("club_wallet_record", "reference_no,description", "id desc"));
        entities.put("settlements", new Entity("club_order_settlement", "settlement_no,provider_type", "id desc"));
        entities.put("orderLogs", new Entity("club_order_log", "note,operator_type", "id desc"));
        entities.put("rechargeRules",new Entity("club_recharge_coupon_rule","name","id desc"));
        entities.put("rechargeRewards",new Entity("club_recharge_reward","user_id,recharge_id,last_error","id desc"));
        entities.put("couponIssues", new Entity("club_coupon_issue", "idempotency_key,source,user_id,coupon_id", "id desc"));
        entities.put("businessConfig", new Entity("club_business_config", "config_key,description", "config_key", "config_key"));
        entities.put("rechargeOrders", new Entity("club_recharge_order", "recharge_no,user_id,mock_transaction_no", "id desc"));
        entities.put("rechargeTiers", new Entity("club_recharge_tier", "name,amount", "sort_no,id"));
        entities.put("customerConfig", new Entity("club_customer_config", "service_name,contact_text,page_url", "id", "id"));
        entities.put("teenSettings", new Entity("club_teen_setting", "user_id", "updated_at desc", "user_id"));
        entities.put("follows", new Entity("club_follow", "user_id,shop_id", "id desc"));
        entities.put("favorites", new Entity("club_favorite", "user_id,product_id", "id desc"));
        entities.put("playerServices", new Entity("club_player_service", "player_id,product_id,sku_id", "id desc"));
        entities.put("aftersales", new Entity("club_aftersale", "aftersale_no,order_no,reason", "id desc"));
        entities.put("paymentAudits", new Entity("club_payment_audit", "request_id,result_status,action", "id desc"));
        entities.put("adminAudits", new Entity("club_admin_audit", "entity_type,action", "id desc"));
        entities.put("receivables", new Entity("club_provider_receivable", "receivable_no,provider_user_id,order_id", "id desc"));
    }

    public Map<String, Object> dashboard()
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("users", scalar("select count(1) from club_user"));
        result.put("products", scalar("select count(1) from club_product where status='active'"));
        result.put("orders", scalar("select count(1) from club_order where " + ClubOrderVisibility.operational("club_order")));
        String paidOrders = " from club_order o where o.status in ('pending','accepted','serving','completed') " +
                "and exists(select 1 from club_payment p where p.order_id=o.id and p.mode in ('wechat','balance') and p.status='success')";
        result.put("paidOrders", scalar("select count(1)" + paidOrders));
        result.put("pendingApplications", scalar("select count(1) from club_application where application_type='player' and status='pending'"));
        result.put("pendingIdentities", scalar("select count(1) from club_identity where status='pending'"));
        result.put("pendingWithdrawals", scalar("select count(1) from club_withdrawal where status='pending'"));
        BigDecimal revenue = jdbc.queryForObject("select coalesce(sum(o.total_amount),0)" + paidOrders, BigDecimal.class);
        result.put("paidRevenue", revenue == null ? BigDecimal.ZERO : revenue);
        result.put("settledIncome", jdbc.queryForObject("select coalesce(sum(provider_income),0) from club_order_settlement where status='settled'", BigDecimal.class));
        result.put("platformFees", jdbc.queryForObject("select coalesce(sum(platform_fee),0) from club_order_settlement where status='settled'", BigDecimal.class));
        result.put("recentOrders", jdbc.queryForList("select id,order_no as orderNo,product_name as productName,total_amount as totalAmount,status,created_at as createdAt from club_order where " + ClubOrderVisibility.operational("club_order") + " order by id desc limit 8"));
        return result;
    }

    public Map<String, Object> list(String type, String keyword, String status, int page, int pageSize)
    {
        if ("players".equals(type) || "shops".equals(type)) return providerSummary(type, keyword, status, page, pageSize);
        if ("rechargeOrders".equals(type)) return rechargeOrderList(keyword, status, page, pageSize);
        if ("follows".equals(type) || "favorites".equals(type)) return relationList(type, keyword, page, pageSize);
        Entity entity = entity(type);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        String word = keyword == null ? "" : keyword.trim();
        String state = status == null ? "" : status.trim();
        String where = buildWhere(entity, word, state);
        if("categories".equals(type))where+=" and code<>'service'";
        List<Object> args = buildArgs(entity, word, state);
        Integer total = jdbc.queryForObject("select count(1) from " + entity.table + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add(((long) safePage - 1L) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList(("orders".equals(type) ? "select club_order.*,"+ClubPlayerCapacity.elapsed("club_order")+" as occupied_seconds from " : "select * from ") + entity.table + where + " order by " + entity.orderBy + " limit ? offset ?", pageArgs.toArray());
        if("categories".equals(type))for(Map<String,Object> row:rows){
            List<Map<String,Object>> entries=jdbc.queryForList("select name,status from club_home_entry where target_url=concat('/pages/product/list?category=',?) and status<>'deleted' order by sort_no,id",row.get("code"));
            List<String> places=new ArrayList<>();for(Map<String,Object> entry:entries)places.add("首页："+entry.get("name")+("active".equals(entry.get("status"))?"":"（已停用）"));
            row.put("display_places",places.isEmpty()?"尚未配置首页入口":String.join("；",places));
        }
        maskSensitive(type, rows);
        Map<String, Object> result = new HashMap<>();
        result.put("rows", rows);
        result.put("total", total == null ? 0 : total);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        return result;
    }

    private Map<String, Object> rechargeOrderList(String keyword, String status, int page, int pageSize)
    {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        String word = keyword == null ? "" : keyword.trim();
        String state = status == null ? "" : status.trim();
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        if (!word.isEmpty())
        {
            where.append(" and (r.recharge_no like ? or r.mock_transaction_no like ? or cast(r.user_id as char) like ? or u.account like ? or u.nickname like ? or u.phone like ?)");
            for (int i = 0; i < 6; i++) args.add("%" + word + "%");
        }
        if (!state.isEmpty())
        {
            where.append(" and r.status=?");
            args.add(state);
        }
        String from = " from club_recharge_order r join club_user u on u.id=r.user_id";
        Integer total = jdbc.queryForObject("select count(1)" + from + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add(((long) safePage - 1L) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select r.*,u.account,u.nickname,u.phone" + from + where + " order by r.id desc limit ? offset ?", pageArgs.toArray());
        maskSensitive("rechargeOrders", rows);
        Map<String, Object> result = new HashMap<>();
        result.put("rows", rows);
        result.put("total", total == null ? 0 : total);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        return result;
    }

    private Map<String, Object> providerSummary(String type, String keyword, String status, int page, int pageSize)
    {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        String word = keyword == null ? "" : keyword.trim();
        String state = status == null ? "" : status.trim();
        boolean player = "players".equals(type);
        String base = player
                ? " from club_player_profile p left join club_user u on u.id=p.user_id left join club_role_status r on r.user_id=p.user_id and r.role_type='player' left join club_wallet w on w.user_id=p.user_id "
                : " from club_shop p left join club_role_status r on r.user_id=p.owner_user_id and r.role_type='merchant' left join club_wallet w on w.user_id=p.owner_user_id ";
        String search = player ? "(p.display_name like ? or p.city like ? or p.intro like ?)" : "(p.name like ? or p.description like ?)";
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" where p.status<>'deleted'");
        if (!word.isEmpty())
        {
            Long uid=player?ClubUidSearch.parse(word):null;
            if(uid!=null){where.append(" and p.user_id=?");args.add(uid);}
            else {
            where.append(" and ").append(search);
            int columns = player ? 3 : 2;
            for (int i = 0; i < columns; i++) args.add("%" + word + "%");
            }
        }
        if (!state.isEmpty())
        {
            where.append(" and p.status=?");
            args.add(state);
        }
        Integer total = jdbc.queryForObject("select count(1)" + base + where, Integer.class, args.toArray());
        String columns = player
                ? "select p.*,u.phone,"+ClubPlayerCapacity.playerElapsed("p.user_id")+" as occupied_seconds,("+ClubPlayerEligibility.approved("p")+") as admission_ready,case when "+ClubPlayerCapacity.busy("p.user_id")+" then 'busy' else coalesce(r.service_status,case when p.online_status=1 then 'online' else 'offline' end) end service_status," +
                  "exists(select 1 from club_product pr where pr.bound_player_id=p.id and pr.status='active') as has_product," +
                  "exists("+ClubPlayerEligibility.activeSkus("p")+") as has_sku," +
                  "exists("+ClubPlayerEligibility.activeSkus("p")+" and sk.stock>0) as has_stock," +
                  "coalesce(w.total_income,0) total_income,greatest(0,least(coalesce(w.balance,0),coalesce(w.total_income,0)-coalesce(w.total_withdrawn,0)-coalesce(w.frozen,0))) available_balance," +
                  "(select count(1) from club_order o where o.player_id=p.id and o.status in ('pending','accepted','serving')) current_orders"
                : "select p.*,coalesce(r.service_status,'offline') service_status,coalesce(w.total_income,0) total_income," +
                  "coalesce(w.balance,0) available_balance,(select count(1) from club_order o join club_product cp on cp.id=o.product_id where cp.shop_id=p.id and o.status in ('pending','accepted','serving')) current_orders";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add(((long) safePage - 1L) * safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList(columns + base + where + " order by p.id desc limit ? offset ?", pageArgs.toArray());
        if(player)for(Map<String,Object> row:rows)row.put("readiness",ClubPlayerReadiness.describe(row));
        Map<String, Object> result = new HashMap<>();
        result.put("rows", rows);
        result.put("total", total == null ? 0 : total);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        return result;
    }

    private Map<String, Object> relationList(String type, String keyword, int page, int pageSize)
    {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        String word = keyword == null ? "" : keyword.trim();
        List<Object> args = new ArrayList<>();
        String where;
        String from;
        String select;
        if ("follows".equals(type))
        {
            from = " from club_follow f join club_user u on u.id=f.user_id join club_shop s on s.id=f.shop_id";
            where = word.isEmpty() ? "" : " where u.account like ? or u.nickname like ? or u.phone like ? or s.name like ?";
            for (int i=0; !word.isEmpty() && i<4; i++) args.add("%" + word + "%");
            select = "select f.id,f.user_id,u.account,u.nickname,u.phone,f.shop_id,s.name shop_name," +
                    "s.base_fans_count,(select count(1) from club_follow x where x.shop_id=s.id) relation_count," +
                    "s.base_fans_count+(select count(1) from club_follow x where x.shop_id=s.id) display_fans_count,f.created_at";
        }
        else
        {
            from = " from club_favorite f join club_user u on u.id=f.user_id join club_product p on p.id=f.product_id";
            where = word.isEmpty() ? "" : " where u.account like ? or u.nickname like ? or u.phone like ? or p.name like ?";
            for (int i=0; !word.isEmpty() && i<4; i++) args.add("%" + word + "%");
            select = "select f.id,f.user_id,u.account,u.nickname,u.phone,f.product_id,p.name product_name,f.created_at";
        }
        Integer total = jdbc.queryForObject("select count(1)" + from + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(safeSize); pageArgs.add((safePage-1)*safeSize);
        List<Map<String, Object>> rows = jdbc.queryForList(select + from + where + " order by f.id desc limit ? offset ?", pageArgs.toArray());
        Map<String, Object> result = new HashMap<>();
        result.put("rows", rows); result.put("total", total == null ? 0 : total); result.put("page", safePage); result.put("pageSize", safeSize);
        return result;
    }

    @Transactional
    public Map<String, Object> save(String type, Map<String, Object> input, Long adminId)
    {
        Long id = optionalLong(input.get("id"));
        String idempotencyKey = null;
        String payloadHash = null;
        if (id == null)
        {
            idempotencyKey = text(first(input, "idempotencyKey", "idempotency_key", "requestId")).trim();
            if (idempotencyKey.isEmpty()) throw new ServiceException("新增或配置写入请求缺少幂等编号");
            Map<String, Object> payload = new LinkedHashMap<>(input);
            payload.remove("idempotencyKey"); payload.remove("idempotency_key"); payload.remove("requestId");
            payloadHash = sha256(JSON.toJSONString(payload));
            jdbc.update("insert into club_admin_action_request(admin_user_id,entity_type,idempotency_key,payload_hash) values(?,?,?,?) " +
                            "on duplicate key update id=last_insert_id(id)",
                    adminId, type, idempotencyKey, payloadHash);
            Map<String, Object> request = jdbc.queryForMap("select payload_hash,result_json from club_admin_action_request where admin_user_id=? and entity_type=? and idempotency_key=? for update",
                    adminId, type, idempotencyKey);
            if (!payloadHash.equals(text(request.get("payload_hash"))))
                throw new ClubConflictException("同一幂等编号不能提交不同内容");
            if (request.get("result_json") != null && !text(request.get("result_json")).isEmpty())
                return JSON.parseObject(text(request.get("result_json")), Map.class);
        }
        Map<String, Object> before = snapshot(type, id);
        Map<String, Object> result = saveInternal(type, input, adminId, id);
        if (idempotencyKey != null)
            jdbc.update("update club_admin_action_request set result_json=?,completed_at=now() where admin_user_id=? and entity_type=? and idempotency_key=? and payload_hash=?",
                    JSON.toJSONString(result), adminId, type, idempotencyKey, payloadHash);
        Object auditEntityId = id == null ? result.get("id") : id;
        if (auditEntityId == null && "businessConfig".equals(type)) auditEntityId = result.get("config_key");
        if (auditEntityId == null && "couponIssues".equals(type)) auditEntityId = first(input, "idempotencyKey", "requestId");
        audit(adminId, type, auditEntityId,
                id == null ? "create" : "update", before, result, input);
        return result;
    }

    private static String sha256(String value)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        }
        catch (Exception exception)
        {
            throw new IllegalStateException("幂等摘要生成失败", exception);
        }
    }

    private Map<String, Object> saveInternal(String type, Map<String, Object> input, Long adminId, Long id)
    {
        if("orders".equals(type)&&"abandon".equals(text(input.get("action")))) {
            String mode=required(input,"abandon_mode");
            if("aftersale".equals(mode))return afterSale.adminAbandon(adminId,requireId(id),input);
            if("requeue".equals(mode))return business.adminTransition(requireId(id),"pending",required(input,"note"),adminId);
            throw new ServiceException("请选择放弃接单后的处理方式");
        }
        if ("orders".equals(type)) {
            String target=required(input,"status");
            if("cancelled".equals(target))return app.adminCancelOrder(adminId,requireId(id),text(input.get("note")));
            return business.adminTransition(requireId(id),target,text(input.get("note")),adminId);
        }
        if ("aftersales".equals(type)) return afterSale.adminReview(adminId, requireId(id), input);
        if ("applications".equals(type)) return reviewApplication(requireId(id), input, adminId);
        if ("identities".equals(type)) throw new ServiceException("实名认证已并入打手入驻审核，请在打手管理中统一审核");
        if ("withdrawals".equals(type)) return reviewWithdrawal(requireId(id), input, adminId);
        if ("receivables".equals(type)) return writeoffReceivable(requireId(id), input, adminId);
        if ("users".equals(type)) return saveUser(requireId(id), input);
        if ("rechargeRules".equals(type)) return saveRechargeRule(id,input);
        if ("rechargeRewards".equals(type)) {Long rechargeId=jdbc.queryForObject("select recharge_id from club_recharge_reward where id=?",Long.class,requireId(id));new ClubCouponGrantService(jdbc).issueReward(rechargeId);return detail(type,id);}
        if ("shops".equals(type)) throw new ServiceException("平台不再支持独立开店");
        if ("products".equals(type)) return saveProduct(id, input);
        if ("content".equals(type)) return saveContent(id, input);
        if ("reviews".equals(type))
        {
            String status = state(input);
            if (!Arrays.asList("visible", "hidden").contains(status)) throw new ServiceException("评价状态不正确");
            return updateFields("club_review", requireId(id), input, fields("status"));
        }
        if ("coupons".equals(type))
        {
            boolean targetedNew = id == null && "targeted".equals(text(input.get("distribution_mode")));
            if (targetedNew && userIds(input.get("user_ids")).isEmpty()) throw new ServiceException("指定发放必须选择接收用户");
            Map<String,Object> coupon = saveCoupon(id,input);
            if (targetedNew)
            {
                Map<String,Object> grant = new HashMap<>(input);
                grant.put("coupon_id",coupon.get("id"));
                grant.put("idempotency_key",required(input,"idempotency_key"));
                coupon.put("issuance",issueCoupons(grant,adminId));
            }
            return coupon;
        }
        if ("messages".equals(type)) return saveMessage(id, input);
        if ("players".equals(type)) return savePlayer(id, input);
        if ("categories".equals(type)) return saveCategory(id, input);
        if ("homeEntries".equals(type)) return saveHomeEntry(id, input);
        if ("skus".equals(type)) throw new ServiceException("服务规格已并入商品编辑，请在商品管理中保存");
        if ("playerServices".equals(type)) throw new ServiceException("请在商品管理中绑定打手");
        if ("couponIssues".equals(type)) return issueCoupons(input, adminId);
        if ("businessConfig".equals(type)) return saveBusinessConfig(input, adminId);
        if ("rechargeTiers".equals(type)) return saveRechargeTier(id, input);
        if ("customerConfig".equals(type)) return saveCustomerConfig(id, input);
        throw new ServiceException("该类型不支持编辑");
    }

    @Transactional
    public void delete(String type, Long id, Long adminId, String reason, String requestId)
    {
        if(Arrays.asList("skus","identities","playerServices").contains(type))throw new ServiceException("该独立操作已停用，请在商品管理或入驻审核中处理");
        Map<String, Object> auditContext = new LinkedHashMap<>();
        auditContext.put("reason", reason);
        auditContext.put("requestId", requestId);
        Map<String, Object> before = snapshot(type, id);
        if ("shops".equals(type))
        {
            List<Map<String,Object>> shop = jdbc.queryForList("select id from club_shop where id=? for update", id);
            if (shop.isEmpty()) throw new ServiceException("店铺不存在");
            Integer products = jdbc.queryForObject("select count(1) from club_product where shop_id=? and status='active'",Integer.class,id);
            Integer orders = jdbc.queryForObject("select count(1) from club_order o join club_product p on p.id=o.product_id where p.shop_id=? and o.status not in ('cancelled','completed','refunded')",Integer.class,id);
            if (products > 0 || orders > 0) throw new ServiceException("店铺仍有在售商品或未完成订单，请先下架商品并处理订单后再删除");
            jdbc.update("update club_shop set status='deleted' where id=?",id);
            audit(adminId,type,id,"delete",before,snapshot(type,id),auditContext);
            return;
        }
        if ("content".equals(type))
        {
            jdbc.update("update club_content set status='deleted' where id=?", id);
            audit(adminId, type, id, "delete", before, snapshot(type, id), auditContext);
            return;
        }
        if ("products".equals(type))
        {
            jdbc.update("update club_product set status='deleted' where id=?", id);
            audit(adminId, type, id, "delete", before, snapshot(type, id), auditContext);
            return;
        }
        if ("players".equals(type))
        {
            roleLifecycle.returnPlayerForReapplication(id, reason);
            audit(adminId, type, id, "return_for_reapplication", before, snapshot(type, id), auditContext);
            return;
        }
        if ("categories".equals(type))
        {
            jdbc.update("update club_category set status='deleted' where id=?", id);
            audit(adminId, type, id, "delete", before, snapshot(type, id), auditContext);
            return;
        }
        if ("homeEntries".equals(type))
        {
            jdbc.update("update club_home_entry set status='deleted' where id=?", id);
            audit(adminId, type, id, "delete", before, snapshot(type, id), auditContext);
            return;
        }
        if ("skus".equals(type))
        {
            Long productId = jdbc.queryForObject("select product_id from club_product_sku where id=?", Long.class, id);
            Integer active = jdbc.queryForObject("select count(1) from club_product_sku where product_id=? and status='active' and id<>?", Integer.class, productId, id);
            if (active == null || active < 1) throw new ServiceException("商品至少保留一个启用的规格");
            jdbc.update("update club_product_sku set status='deleted' where id=?", id);
            catalog.syncProductSummary(productId);
            audit(adminId, type, id, "delete", before, snapshot(type, id), auditContext);
            return;
        }
        if ("rechargeTiers".equals(type))
        {
            jdbc.update("update club_recharge_tier set status='disabled' where id=?", id);
            audit(adminId, type, id, "delete", before, snapshot(type, id), auditContext);
            return;
        }
        if ("coupons".equals(type))
        {
            Integer linked = jdbc.queryForObject("select count(1) from club_recharge_coupon_rule where coupon_id=? and status='active'", Integer.class, id);
            if (linked != null && linked > 0) throw new ServiceException("该券仍用于充值赠券，请先停用对应赠券规则再删除");
            jdbc.update("update club_coupon set status='deleted' where id=?", id);
            audit(adminId, type, id, "delete", before, snapshot(type, id), auditContext);
            return;
        }
        if ("messages".equals(type))
        {
            jdbc.update("delete from club_message where id=?", id);
            audit(adminId, type, id, "delete", before, new HashMap<>(), auditContext);
            return;
        }
        throw new ServiceException("该数据只能停用，不能直接删除");
    }

    private Map<String, Object> saveProduct(Long id, Map<String, Object> input)
    {
        String name = required(input, "name");
        ClubProductSpecs specifications=new ClubProductSpecs(jdbc);
        List<Map<String,Object>> inline=input.containsKey("skus")?specifications.prepare(id,input):null;
        BigDecimal price = inline==null?money(input.get("price")):BigDecimal.ZERO;
        int stock = inline==null?integer(input.get("stock"), 0):0;
        if(inline!=null) {
            price=null;stock=0;
            for(Map<String,Object> row:inline) if("active".equals(row.get("status"))) {
                BigDecimal value=(BigDecimal)row.get("price");price=price==null?value:price.min(value);stock+=(Integer)row.get("stock");
            }
            if(price==null)price=(BigDecimal)inline.get(0).get("price");
        }
        catalog.validatePriceAndStock(price, stock);
        Long shopId = jdbc.queryForObject("select min(id) from club_shop where name='品奢电竞' and owner_user_id is null and status='active'",Long.class);
        Long boundPlayerId=optionalLong(input.get("bound_player_id"));
        if(boundPlayerId==null)throw new ServiceException("请选择商品绑定的打手");
        Integer ready=jdbc.queryForObject("select count(1) from club_player_profile p where p.id=? and p.status='active' and "+ClubPlayerEligibility.approved("p"),Integer.class,boundPlayerId);
        if(ready==null||ready!=1)throw new ServiceException("该打手入驻审核未通过或账号已停用，请先到打手管理查看审核状态");
        String category = text(first(input, "category_code", "category"));
        if(category.isEmpty() && id!=null) category=jdbc.queryForObject("select category_code from club_product where id=?",String.class,id);
        if(category==null||category.isEmpty()) {
            jdbc.update("insert into club_category(code,name,icon,sort_no,status) values('uncategorized','未分类','',9999,'active') on duplicate key update name='未分类',status='active'");
            category="uncategorized";
        }
        if("service".equals(category))throw new ServiceException("在线客服不是商品分类，请选择实际商品分类");
        String productState = activeInactiveState(input);
        Integer shopExists = jdbc.queryForObject("select count(1) from club_shop where id=? and status<>'deleted'", Integer.class, shopId);
        if (shopId == null || shopId < 1 || shopExists == null || shopExists != 1) throw new ServiceException("商品所属店铺不存在");
        Integer categoryExists = jdbc.queryForObject("select count(1) from club_category where code=? and status='active'", Integer.class, category);
        if (categoryExists == null || categoryExists != 1) throw new ServiceException("商品分类不存在或已停用");
        if (id == null)
        {
            id = insertAndKey("insert into club_product(shop_id,category_code,name,subtitle,image,detail_text,price,old_price,stock,is_hot,status) values(?,?,?,?,?,?,?,?,?,?,?)",
                    shopId, category, name,
                    text(input.get("subtitle")), text(input.get("image")), text(first(input, "detail_text", "detailText")), price, null,
                    stock, bool(first(input, "is_hot", "hot")) ? 1 : 0, productState);
            BigDecimal skuPrice = price;
            if(inline==null)jdbc.update("insert into club_product_sku(product_id,name,price,stock,status) values(?,?,?,?, 'active')", id, "默认规格", skuPrice, stock);
        }
        else
        {
            jdbc.update("update club_product set shop_id=?,category_code=?,name=?,subtitle=?,image=?,detail_text=?,old_price=null,is_hot=?,status=? where id=?",
                    shopId, category, name,
                    text(input.get("subtitle")), text(input.get("image")), text(first(input, "detail_text", "detailText")),
                    bool(first(input, "is_hot", "hot")) ? 1 : 0, productState, id);
            if(inline==null) {
            List<Map<String, Object>> skus = jdbc.queryForList("select id from club_product_sku where product_id=? and status='active' order by id", id);
            if (skus.size() == 1)
            {
                jdbc.update("update club_product_sku set price=?,stock=? where id=?", price, stock, skus.get(0).get("id"));
            }
            else if (skus.isEmpty())
            {
                jdbc.update("insert into club_product_sku(product_id,name,price,stock,status) values(?,?,?,?, 'active')", id, "默认规格", price, stock);
            }
            else
            {
                BigDecimal currentPrice = jdbc.queryForObject("select min(price) from club_product_sku where product_id=? and status='active'", BigDecimal.class, id);
                Integer currentStock = jdbc.queryForObject("select coalesce(sum(stock),0) from club_product_sku where product_id=? and status='active'", Integer.class, id);
                if (price.compareTo(currentPrice) != 0 || stock != (currentStock == null ? 0 : currentStock))
                    throw new ServiceException("多规格商品请重新打开商品编辑，在服务规格中修改价格和库存");
            }
        }
        }
        if(inline!=null)specifications.save(id,inline);
        jdbc.update("update club_product set bound_player_id=? where id=?",boundPlayerId,id);
        jdbc.update("update club_player_service set status='inactive' where product_id=?",id);
        List<Map<String,Object>> bindings=jdbc.queryForList("select id from club_player_service where product_id=? and player_id=? and sku_id is null order by id limit 1",id,boundPlayerId);
        if(bindings.isEmpty())jdbc.update("insert into club_player_service(product_id,player_id,sku_id,status) values(?,?,null,'active')",id,boundPlayerId);
        else jdbc.update("update club_player_service set status='active' where id=?",bindings.get(0).get("id"));
        catalog.syncProductSummary(id);
        return jdbc.queryForMap("select * from club_product where id=?", id);
    }

    private Map<String, Object> saveContent(Long id, Map<String, Object> input)
    {
        String type = required(input, "content_type", "contentType");
        if (!Arrays.asList("promotion", "article", "document").contains(type)) throw new ServiceException("内容类型不正确");
        String scene = required(input, "scene");
        String title = required(input, "title");
        String targetUrl = text(first(input, "target_url", "targetUrl"));
        if (!targetUrl.isEmpty()) targetUrl = validPageUrl(targetUrl);
        Object startAt = emptyToNull(first(input, "start_at", "startAt"));
        Object endAt = emptyToNull(first(input, "end_at", "endAt"));
        validateDateWindow(startAt, endAt);
        String styleJson = text(first(input, "style_json", "styleJson"));
        validateJson(styleJson, "样式配置");
        if (id == null)
        {
            id = insertAndKey("insert into club_content(content_type,scene,title,subtitle,image,body_text,target_url,popup_enabled,start_at,end_at,style_json,sort_no,status) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    type, scene, title, text(input.get("subtitle")), text(input.get("image")), text(first(input, "body_text", "bodyText")),
                    targetUrl, bool(first(input, "popup_enabled", "popupEnabled")) ? 1 : 0,
                    startAt, endAt, styleJson, integer(first(input, "sort_no", "sortNo"), 0), activeDisabledState(input));
        }
        else
        {
            jdbc.update("update club_content set content_type=?,scene=?,title=?,subtitle=?,image=?,body_text=?,target_url=?,popup_enabled=?,start_at=?,end_at=?,style_json=?,sort_no=?,status=? where id=?",
                    type, scene, title, text(input.get("subtitle")), text(input.get("image")), text(first(input, "body_text", "bodyText")),
                    targetUrl, bool(first(input, "popup_enabled", "popupEnabled")) ? 1 : 0,
                    startAt, endAt, styleJson, integer(first(input, "sort_no", "sortNo"), 0), activeDisabledState(input), id);
        }
        return jdbc.queryForMap("select * from club_content where id=?", id);
    }

    private Map<String, Object> saveCoupon(Long id, Map<String, Object> input)
    {
        String name = required(input, "name");
        BigDecimal amount = money(input.get("amount"));
        BigDecimal minSpend = money(first(input, "min_spend", "minSpend"));
        String validFrom = required(input, "valid_from", "validFrom");
        String validUntil = required(input, "valid_until", "validUntil");
        int totalCount = integer(first(input, "total_count", "totalCount"), 0);
        int perUserLimit = Math.max(1, integer(first(input, "per_user_limit", "perUserLimit"), 1));
        catalog.validatePriceAndStock(amount, totalCount);
        if (minSpend.compareTo(BigDecimal.ZERO) < 0 || minSpend.scale() > 2) throw new ServiceException("优惠券使用门槛不能小于0且最多保留两位小数");
        if (totalCount < 1) throw new ServiceException("优惠券发行总数必须大于0");
        validateDateWindow(validFrom, validUntil);
        if (id == null)
        {
            id = insertAndKey("insert into club_coupon(name,amount,min_spend,valid_from,valid_until,total_count,per_user_limit,status) values(?,?,?,?,?,?,?,?)",
                    name, amount, minSpend, validFrom, validUntil, totalCount, perUserLimit, activeDisabledState(input));
        }
        else
        {
            Integer issued = jdbc.queryForObject("select issued_count from club_coupon where id=? for update", Integer.class, id);
            if (issued != null && totalCount < issued) throw new ServiceException("发行总数不能小于已发放数量");
            jdbc.update("update club_coupon set name=?,amount=?,min_spend=?,valid_from=?,valid_until=?,total_count=?,per_user_limit=?,status=? where id=?",
                    name, amount, minSpend, validFrom, validUntil, totalCount, perUserLimit, activeDisabledState(input), id);
        }
        String mode=text(input.get("distribution_mode"));if(mode.isEmpty())mode="targeted";
        if(!Arrays.asList("public","targeted","recharge").contains(mode))throw new ServiceException("发放方式不正确");
        Long productId=optionalLong(input.get("product_id"));
        if(productId!=null){Integer exists=jdbc.queryForObject("select count(1) from club_product where id=? and status<>'deleted'",Integer.class,productId);if(exists==null||exists!=1)throw new ServiceException("适用商品不存在");}
        jdbc.update("update club_coupon set distribution_mode=?,product_id=? where id=?",mode,productId,id);
        return jdbc.queryForMap("select * from club_coupon where id=?", id);
    }

    private Map<String,Object> saveRechargeRule(Long id,Map<String,Object> input){
        String name=required(input,"name");BigDecimal amount=money(input.get("min_amount"));
        Long couponId=optionalLong(input.get("coupon_id"));int quantity=integer(input.get("quantity"),0);
        if(amount.compareTo(BigDecimal.ONE)<0||amount.scale()>2||quantity<1||quantity>100||couponId==null)throw new ServiceException("请设置有效充值门槛、优惠券和赠送张数");
        Integer exists=jdbc.queryForObject("select count(1) from club_coupon where id=?",Integer.class,couponId);
        if(exists==null||exists!=1)throw new ServiceException("优惠券不存在");
        String status=activeDisabledState(input);
        if(id==null)id=insertAndKey("insert into club_recharge_coupon_rule(name,min_amount,coupon_id,quantity,status) values(?,?,?,?,?)",name,amount,couponId,quantity,status);
        else jdbc.update("update club_recharge_coupon_rule set name=?,min_amount=?,coupon_id=?,quantity=?,status=? where id=?",name,amount,couponId,quantity,status,id);
        return detail("rechargeRules",id);
    }

    private Map<String, Object> saveMessage(Long id, Map<String, Object> input)
    {
        if (id != null) throw new ServiceException("已发送消息不能修改");
        Long userId = optionalLong(first(input, "user_id", "userId"));
        String title = required(input, "title");
        String content = required(input, "content");
        String type = text(first(input, "message_type", "messageType"));
        if (type.isEmpty()) type = "system";
        if (!Arrays.asList("system", "order", "promotion").contains(type)) throw new ServiceException("消息类型不正确");
        if (userId == null)
        {
            if ("promotion".equals(type))
                jdbc.update("insert into club_message(user_id,message_type,title,content) select u.id,?,?,? from club_user u join club_notification_setting n on n.user_id=u.id and n.promotion_enabled=1 where u.status='active'", type, title, content);
            else
                jdbc.update("insert into club_message(user_id,message_type,title,content) select id,?,?,? from club_user where status='active'", type, title, content);
            Map<String, Object> result = new HashMap<>();
            result.put("broadcast", true);
            return result;
        }
        if ("promotion".equals(type))
        {
            Integer enabled = jdbc.queryForObject("select count(1) from club_notification_setting where user_id=? and promotion_enabled=1", Integer.class, userId);
            if (enabled == null || enabled == 0) throw new ServiceException("用户已关闭营销消息，不能发送营销广播");
        }
        Long messageId = insertAndKey("insert into club_message(user_id,message_type,title,content) values(?,?,?,?)", userId, type, title, content);
        return jdbc.queryForMap("select * from club_message where id=?", messageId);
    }

    private Map<String, Object> savePlayer(Long id, Map<String, Object> input)
    {
        if(id==null)throw new ServiceException("请通过打手入驻审核建立档案");
        String name = required(input, "display_name", "displayName");
        Long userId = optionalLong(first(input, "user_id", "userId"));
        if (id == null && userId == null) throw new ServiceException("请绑定真实小程序账号");
        if (userId != null && !activeUser(userId)) throw new ServiceException("绑定账号不存在或已停用");
        if (id != null)
        {
            Long boundUserId = jdbc.queryForObject("select user_id from club_player_profile where id=?", Long.class, id);
            if (userId == null) userId = boundUserId;
            if (boundUserId != null && !boundUserId.equals(userId))
                throw new ServiceException("已有陪玩档案不能直接更换绑定账号，请先完成角色迁移审核");
        }
        if (id == null)
        {
            Integer existing = jdbc.queryForObject("select count(1) from club_player_profile where user_id=?", Integer.class, userId);
            if (existing != null && existing > 0) throw new ServiceException("该账号已有陪玩档案，请编辑原档案");
            roleLifecycle.switchRole(userId, "player", name, text(input.get("intro")));
            id = jdbc.queryForObject("select id from club_player_profile where user_id=?", Long.class, userId);
        }
        // Editing a profile is not a role switch: the old path reset every saved
        // player to offline (and rejected every online save).
        Map<String, Object> account = jdbc.queryForMap("select user_type,status from club_user where id=? for update", userId);
        if (!"player".equals(text(account.get("user_type"))))
            throw new ServiceException("陪玩档案与账号身份不一致，请先在用户管理核对业务身份，不能通过编辑资料切换身份");
        Map<String, Object> current = jdbc.queryForMap("select * from club_player_profile where id=? for update", id);
        Map<String, Object> merged = new HashMap<>(current);
        merged.putAll(input);
        String requestedStatus = activeDisabledState(merged);
        if (!"active".equals(requestedStatus) && "active".equals(text(current.get("status")))) roleLifecycle.assertSwitchable(userId);
        String serviceStatus = jdbc.queryForObject("select service_status from club_role_status where user_id=?", String.class, userId);
        boolean hasOnline = input.containsKey("online_status") || input.containsKey("online");
        String targetAvailability = serviceStatus == null ? "offline" : serviceStatus;
        if (hasOnline)
        {
            boolean requestedOnline = bool(first(input, "online_status", "online"));
            // A paused player is displayed as not-online; a normal profile save
            // must preserve paused, not silently turn it into offline.
            if (requestedOnline) targetAvailability = "online";
            else if (!"paused".equals(targetAvailability)) targetAvailability = "offline";
        }
        if (!"active".equals(requestedStatus)) targetAvailability = "offline";
        jdbc.update("update club_player_profile set display_name=?,gender=?,age=?,city=?,intro=?,image=?,voice_url=?,voice_seconds=?,status=?,sort_no=? where id=?",
                name, ClubGender.normalize(merged.get("gender")), integer(merged.get("age"), 18), text(merged.get("city")), text(merged.get("intro")), text(merged.get("image")),
                input.containsKey("voiceUrl") ? text(input.get("voiceUrl")) : text(merged.get("voice_url")),
                input.containsKey("voiceSeconds") ? integer(input.get("voiceSeconds"), 0) : integer(merged.get("voice_seconds"), 0),
                requestedStatus, integer(first(merged, "sort_no", "sortNo"), 0), id);
        Map<String, Object> availability = new HashMap<>();
        availability.put("status", targetAvailability);
        // Editing an already-online busy player's profile must not reset their availability intent.
        Integer busyOrders=jdbc.queryForObject("select count(1) from club_order where provider_user_id=? and provider_type='player' and "+ClubPlayerCapacity.occupied("club_order"),Integer.class,userId);
        if (!("online".equals(serviceStatus)&&"online".equals(targetAvailability)&&busyOrders!=null&&busyOrders>0))
            business.updateAvailability(userId, availability);
        return jdbc.queryForMap("select * from club_player_profile where id=?", id);
    }

    private Map<String, Object> saveCategory(Long id, Map<String, Object> input)
    {
        String code = text(input.get("code"));
        if (code.isEmpty()) code = id == null ? "cat_" + UUID.randomUUID().toString().replace("-","").substring(0,24)
                : jdbc.queryForObject("select code from club_category where id=?",String.class,id);
        if (!code.matches("[A-Za-z0-9_-]{1,32}")) throw new ServiceException("分类编码只能包含32位以内的字母、数字、下划线或横线");
        Integer duplicate=jdbc.queryForObject("select count(1) from club_category where code=? and id<>?",Integer.class,code,id==null?0L:id);
        if(duplicate>0) throw new ServiceException("分类编码已存在");
        if(id!=null){String original=jdbc.queryForObject("select code from club_category where id=?",String.class,id);
            if(!code.equals(original)&&jdbc.queryForObject("select count(1) from club_product where category_code=?",Integer.class,original)>0)
                throw new ServiceException("分类已关联商品，不能修改编码；留空可保留原编码");}
        String name = required(input, "name");
        if("service".equals(code)||"在线客服".equals(name))throw new ServiceException("在线客服请在客服管理中设置，不作为商品分类");
        String icon = text(input.get("icon"));
        String avatarImage = text(first(input, "avatar_image", "avatarImage"));
        String targetUrl = text(first(input, "target_url", "targetUrl"));
        if (!targetUrl.isEmpty()) targetUrl = validPageUrl(targetUrl);
        if (id == null)
        {
            id = insertAndKey("insert into club_category(code,name,icon,avatar_image,target_url,role_scope,sort_no,status) values(?,?,?,?,?,?,?,?)", code, name, icon,
                    avatarImage, targetUrl, roleScope(input), integer(first(input,"sort_no","sortNo"),0), activeDisabledState(input));
        }
        else
        {
            jdbc.update("update club_category set code=?,name=?,icon=?,avatar_image=?,target_url=?,role_scope=?,sort_no=?,status=? where id=?", code, name, icon,
                    avatarImage, targetUrl, roleScope(input), integer(first(input,"sort_no","sortNo"),0), activeDisabledState(input), id);
        }
        return jdbc.queryForMap("select * from club_category where id=?", id);
    }

    private Map<String, Object> saveHomeEntry(Long id, Map<String, Object> input)
    {
        String code = text(first(input, "entry_code", "code"));
        if (code.isEmpty()) code = id == null ? "entry_" + UUID.randomUUID().toString().replace("-", "") : jdbc.queryForObject("select entry_code from club_home_entry where id=?", String.class, id);
        String name = required(input, "name");
        String icon = emptyFallback(input.get("icon"), "grid");
        String avatarImage = text(first(input, "avatar_image", "avatarImage"));
        String targetUrl = homeEntryTarget(id, input);
        if (id == null)
        {
            id = insertAndKey("insert into club_home_entry(entry_code,name,icon,avatar_image,target_url,role_scope,sort_no,status) values(?,?,?,?,?,?,?,?)",
                    code, name, icon, avatarImage, targetUrl, roleScope(input), integer(first(input,"sort_no","sortNo"),0), activeDisabledState(input));
        }
        else
        {
            jdbc.update("update club_home_entry set entry_code=?,name=?,icon=?,avatar_image=?,target_url=?,role_scope=?,sort_no=?,status=? where id=?",
                    code, name, icon, avatarImage, targetUrl, roleScope(input), integer(first(input,"sort_no","sortNo"),0), activeDisabledState(input), id);
        }
        return jdbc.queryForMap("select * from club_home_entry where id=?", id);
    }

    private String homeEntryTarget(Long id, Map<String, Object> input)
    {
        String kind = text(input.get("target_kind"));
        if (kind.isEmpty()) return validPageUrl(required(input, "target_url", "targetUrl")); // Older admin clients.
        if ("service".equals(kind)) return "/pages/service/customer";
        if ("players".equals(kind)) return "/pages/players/index";
        if ("products".equals(kind)) return "/pages/product/list";
        if ("category".equals(kind))
        {
            String code = text(input.get("target_category"));
            Integer found = jdbc.queryForObject("select count(1) from club_category where code=? and status='active'", Integer.class, code);
            if (code.isEmpty() || found == null || found != 1) throw new ServiceException("请选择有效的商品分类");
            try { return "/pages/product/list?category=" + java.net.URLEncoder.encode(code, "UTF-8"); }
            catch (java.io.UnsupportedEncodingException e) { throw new IllegalStateException(e); }
        }
        if ("preserve".equals(kind) && id != null)
            return validPageUrl(jdbc.queryForObject("select target_url from club_home_entry where id=?", String.class, id));
        throw new ServiceException("请选择入口去向");
    }

    private Map<String, Object> issueCoupons(Map<String, Object> input, Long adminId)
    {
        Long couponId = optionalLong(first(input, "coupon_id", "couponId"));
        if (couponId == null) throw new ServiceException("请选择优惠券");
        int quantity = integer(input.get("quantity"), 1);
        if (quantity < 1 || quantity > 100) throw new ServiceException("单次发放数量必须为1至100");
        String requestKey = required(input, "idempotency_key", "idempotencyKey");
        if (requestKey.length() > 80) throw new ServiceException("幂等键过长");
        List<Long> userIds = userIds(first(input, "user_ids", "userIds"));
        if (userIds.isEmpty()) throw new ServiceException("请选择至少一名用户");
        if (userIds.size() > 100) throw new ServiceException("单次最多选择100名用户");
        List<Map<String, Object>> coupons = jdbc.queryForList("select * from club_coupon where id=? for update", couponId);
        if (coupons.isEmpty()) throw new ServiceException("优惠券不存在");
        Map<String, Object> coupon = coupons.get(0);
        if (!"active".equals(text(coupon.get("status")))) throw new ServiceException("优惠券未启用");
        Integer valid = jdbc.queryForObject("select count(1) from club_coupon where id=? and now() between valid_from and valid_until", Integer.class, couponId);
        if (valid == null || valid != 1) throw new ServiceException("当前不在优惠券有效期内，请检查生效时间和失效时间：" + text(coupon.get("valid_from")) + " 至 " + text(coupon.get("valid_until")));
        int available = integer(coupon.get("total_count"), 0) - integer(coupon.get("issued_count"), 0);
        int limit = Math.max(1, integer(coupon.get("per_user_limit"), 1));
        List<Long> issuedUsers = new ArrayList<>();
        Map<Long, String> skippedUsers = new LinkedHashMap<>();
        int issued = 0;
        for (Long userId : userIds)
        {
            Integer active = jdbc.queryForObject("select count(1) from club_user where id=? and status='active'", Integer.class, userId);
            if (active == null || active != 1) { skippedUsers.put(userId, "用户不存在或已停用"); continue; }
            Integer owned = jdbc.queryForObject("select coalesce(sum(quantity),0) from club_coupon_issue where user_id=? and coupon_id=?", Integer.class, userId, couponId);
            if ((owned == null ? 0 : owned) + quantity > limit) { skippedUsers.put(userId, "超过每人领取上限"); continue; }
            if (available - issued < quantity) throw new ServiceException("优惠券库存不足");
            String perUserKey = requestKey + ":" + userId + ":" + couponId;
            int changed = jdbc.update("insert ignore into club_coupon_issue(user_id,coupon_id,quantity,remaining_count,source,idempotency_key,status) values(?,?,?,?, 'admin',?,'active')",
                    userId, couponId, quantity, quantity, perUserKey);
            if (changed == 1)
            {
                issued += quantity;
                issuedUsers.add(userId);
            }
            else skippedUsers.put(userId, "重复请求，未重复发放");
        }
        if (issued > 0)
        {
            if (jdbc.update("update club_coupon set issued_count=issued_count+? where id=? and issued_count+?<=total_count", issued, couponId, issued) != 1)
                throw new ServiceException("优惠券库存发生变化，请重试");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("couponId", couponId);
        result.put("issuedCount", issued);
        result.put("issuedUsers", issuedUsers);
        result.put("skippedUsers", skippedUsers);
        result.put("adminId", adminId);
        return result;
    }

    private Map<String, Object> saveBusinessConfig(Map<String, Object> input, Long adminId)
    {
        String key = required(input, "config_key", "configKey");
        String value = required(input, "config_value", "configValue");
        if (!Arrays.asList("platform_commission_rate", "auto_complete_hours", "recharge_enabled", "ranking_enabled", "ranking_period", "ranking_limit", "ranking_mask_nickname",
                "aftersale_protection_hours", "aftersale_provider_response_hours", "teen_block_order", "teen_block_recharge",
                "teen_block_balance_payment", "teen_block_withdrawal", "teen_single_spend_limit", "teen_daily_spend_limit",
                "teen_allowed_start", "teen_allowed_end", "teen_content_scope").contains(key))
            throw new ServiceException("不支持的业务配置");
        if ("ranking_period".equals(key) && !Arrays.asList("all", "month", "week", "day").contains(value))
            throw new ServiceException("排行榜周期只能为all、month、week或day");
        boolean textConfig = Arrays.asList("ranking_period", "teen_allowed_start", "teen_allowed_end", "teen_content_scope").contains(key);
        BigDecimal number = textConfig ? BigDecimal.ZERO : money(value);
        if ("platform_commission_rate".equals(key) && (number.compareTo(BigDecimal.ZERO) < 0 || number.compareTo(new BigDecimal("100")) > 0))
            throw new ServiceException("平台佣金比例必须在0至100之间");
        if ("auto_complete_hours".equals(key) && (number.compareTo(BigDecimal.ONE) < 0 || number.compareTo(new BigDecimal("720")) > 0))
            throw new ServiceException("自动确认时间必须在1至720小时之间");
        if (Arrays.asList("recharge_enabled", "ranking_enabled", "ranking_mask_nickname", "teen_block_order", "teen_block_recharge",
                "teen_block_balance_payment", "teen_block_withdrawal").contains(key) && !("0".equals(value) || "1".equals(value)))
            throw new ServiceException("开关配置只能填写0或1");
        if ("ranking_limit".equals(key) && (number.compareTo(BigDecimal.ONE) < 0 || number.compareTo(new BigDecimal("100")) > 0))
            throw new ServiceException("排行榜人数必须在1至100之间");
        if (Arrays.asList("aftersale_protection_hours", "aftersale_provider_response_hours").contains(key)
                && (number.compareTo(BigDecimal.ZERO) < 0 || number.compareTo(new BigDecimal("2160")) > 0))
            throw new ServiceException("售后时限必须在0至2160小时之间");
        if (Arrays.asList("teen_single_spend_limit", "teen_daily_spend_limit").contains(key)
                && (number.compareTo(BigDecimal.ZERO) < 0 || number.compareTo(new BigDecimal("100000")) > 0))
            throw new ServiceException("青少年消费限额必须在0至100000元之间");
        if (Arrays.asList("teen_allowed_start", "teen_allowed_end").contains(key))
        {
            try { java.time.LocalTime.parse(value); }
            catch (Exception e) { throw new ServiceException("青少年允许时间必须使用HH:mm格式"); }
        }
        if ("teen_content_scope".equals(key))
        {
            for (String scope : value.split(","))
                if (!Arrays.asList("product", "news", "announcement", "promotion", "document", "ranking").contains(scope.trim()))
                    throw new ServiceException("青少年内容范围包含不支持的类型");
        }
        jdbc.update("insert into club_business_config(config_key,config_value,description,updated_by) values(?,?,?,?) on duplicate key update config_value=values(config_value),description=values(description),updated_by=values(updated_by)",
                key, value, text(input.get("description")), adminId);
        return jdbc.queryForMap("select * from club_business_config where config_key=?", key);
    }

    private Map<String, Object> saveRechargeTier(Long id, Map<String, Object> input)
    {
        String name = required(input, "name");
        BigDecimal amount = money(input.get("amount"));
        BigDecimal bonus = money(first(input, "bonus_amount", "bonusAmount"));
        if (amount.compareTo(BigDecimal.ONE) < 0 || amount.compareTo(new BigDecimal("50000")) > 0)
            throw new ServiceException("充值金额必须在1元至50000元之间");
        if (bonus.compareTo(BigDecimal.ZERO) < 0 || bonus.compareTo(amount) > 0)
            throw new ServiceException("赠送金额必须在0元至充值金额之间");
        Integer duplicate = jdbc.queryForObject("select count(1) from club_recharge_tier where amount=? and id<>?", Integer.class, amount, id == null ? 0L : id);
        if (duplicate != null && duplicate > 0)
            throw new ServiceException("该充值金额已有档位，请编辑原档位，不能重复添加");
        if (id == null)
        {
            id = insertAndKey("insert into club_recharge_tier(name,amount,bonus_amount,sort_no,status) values(?,?,?,?,?)",
                    name, amount, bonus, integer(first(input,"sort_no","sortNo"),0), activeDisabledState(input));
        }
        else jdbc.update("update club_recharge_tier set name=?,amount=?,bonus_amount=?,sort_no=?,status=? where id=?",
                name, amount, bonus, integer(first(input,"sort_no","sortNo"),0), activeDisabledState(input), id);
        return jdbc.queryForMap("select * from club_recharge_tier where id=?", id);
    }

    private Map<String, Object> saveCustomerConfig(Long id, Map<String, Object> input)
    {
        String icon = "customer-cartoon";
        String pageUrl = "/pages/service/customer";
        required(input,"qr_image","qrImage");
        String online = text(first(input, "online_status", "onlineStatus"));
        if (!Arrays.asList("online", "offline").contains(online)) throw new ServiceException("请选择客服在线或离线状态");
        String avatar = text(first(input, "avatar_image", "avatar"));
        if (avatar.isEmpty()) avatar = "/static/images/customer-cartoon.png";
        Object[] values = new Object[] { required(input,"service_name","serviceName"), icon, avatar, online,
                text(first(input,"contact_text","contactText")), pageUrl, text(first(input,"qr_image","qrImage")), activeDisabledState(input) };
        if (id == null)
        {
            id = insertAndKey("insert into club_customer_config(service_name,icon,avatar_image,online_status,contact_text,page_url,qr_image,status) values(?,?,?,?,?,?,?,?)", values);
        }
        else
        {
            jdbc.update("update club_customer_config set service_name=?,icon=?,avatar_image=?,online_status=?,contact_text=?,page_url=?,qr_image=?,status=? where id=?",
                    values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], id);
        }
        return jdbc.queryForMap("select * from club_customer_config where id=?", id);
    }

    private Map<String, Object> writeoffReceivable(Long id, Map<String, Object> input, Long adminId)
    {
        String reason = required(input, "reason");
        BigDecimal requested = money(first(input, "amount"));
        if (requested.compareTo(BigDecimal.ZERO) <= 0) throw new ServiceException("核销金额必须大于0");
        List<Map<String, Object>> rows = jdbc.queryForList("select * from club_provider_receivable where id=? for update", id);
        if (rows.isEmpty()) throw new ServiceException("追偿应收不存在");
        Map<String, Object> debt = rows.get(0);
        BigDecimal outstanding = money(debt.get("amount")).subtract(money(debt.get("recovered_amount")));
        if (!"outstanding".equals(text(debt.get("status"))) || outstanding.compareTo(BigDecimal.ZERO) <= 0)
            throw new ServiceException("该追偿应收已经结清");
        if (requested.compareTo(outstanding) > 0) throw new ServiceException("核销金额不能超过剩余追偿金额");
        jdbc.update("update club_provider_receivable set recovered_amount=recovered_amount+?,status=case when recovered_amount+?=amount then 'recovered' else 'outstanding' end,updated_at=now() where id=?",
                requested, requested, id);
        String recoveryNo = "RW" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("insert into club_provider_receivable_recovery(recovery_no,receivable_id,provider_user_id,order_id,aftersale_id,amount,recovery_type,operator_id,reason) values(?,?,?,?,?,?,'manual_writeoff',?,?)",
                recoveryNo, id, debt.get("provider_user_id"), debt.get("order_id"), debt.get("aftersale_id"), requested, adminId, reason);
        Map<String, Object> wallet = jdbc.queryForMap("select balance,frozen from club_wallet where user_id=? for update", debt.get("provider_user_id"));
        jdbc.update("insert into club_wallet_record(user_id,record_type,amount,balance_before,balance_after,frozen_after,reference_no,order_id,counterparty_type,description) values(?,?,?,?,?,?,?,?,?,?)",
                debt.get("provider_user_id"), "receivable_writeoff", requested.negate(), wallet.get("balance"), wallet.get("balance"), wallet.get("frozen"), recoveryNo,
                debt.get("order_id"), "manual_writeoff", "财务手工核销退款追偿：" + reason);
        return detail("receivables", id);
    }

    public Map<String, Object> detail(String type, Long id)
    {
        if ("aftersales".equals(type)) return afterSale.adminDetail(id);
        if ("receivables".equals(type))
        {
            List<Map<String, Object>> rows = jdbc.queryForList("select * from club_provider_receivable where id=?", id);
            if (rows.isEmpty()) throw new ServiceException("追偿应收不存在");
            Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
            result.put("recoveries", jdbc.queryForList("select id,recovery_no,settlement_id,order_id,aftersale_id,amount,recovery_type,operator_id,reason,created_at from club_provider_receivable_recovery where receivable_id=? order by id", id));
            return result;
        }
        if ("orders".equals(type))
        {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "select o.*,"+ClubPlayerCapacity.elapsed("o")+" as occupied_seconds,u.account,u.nickname,u.phone user_phone,s.name shop_name,pp.display_name player_name " +
                    "from club_order o join club_user u on u.id=o.user_id left join club_product p on p.id=o.product_id " +
                    "left join club_shop s on s.id=p.shop_id left join club_player_profile pp on pp.id=o.player_id where o.id=?", id);
            if (rows.isEmpty()) throw new ServiceException("订单不存在");
            Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
            result.put("logs", jdbc.queryForList("select * from club_order_log where order_id=? order by id", id));
            result.put("payments", jdbc.queryForList("select id,payment_no,mode as pay_method,amount,refunded_amount,status,mock_transaction_no,created_at,paid_at,refunded_at from club_payment where order_id=? order by id", id));
            result.put("paymentAudits", jdbc.queryForList("select id,payment_id,action,result_status,request_id,detail,created_at from club_payment_audit where order_id=? order by id", id));
            result.put("coupon", jdbc.queryForList("select oc.coupon_issue_id,c.name,oc.discount_amount,oc.status,oc.created_at from club_order_coupon oc join club_coupon_issue ci on ci.id=oc.coupon_issue_id join club_coupon c on c.id=ci.coupon_id where oc.order_id=?", id));
            result.put("walletRecords", jdbc.queryForList("select id,user_id,record_type,amount,balance_before,balance_after,frozen_after,reference_no,counterparty_type,description,created_at from club_wallet_record where order_id=? order by id", id));
            result.put("settlement", jdbc.queryForList("select * from club_order_settlement where order_id=?", id));
            result.put("aftersales", jdbc.queryForList("select * from club_aftersale where order_id=? order by id desc", id));
            return result;
        }
        Entity entity = entity(type);
        List<Map<String, Object>> rows = jdbc.queryForList("select * from " + entity.table + " where " + entity.idColumn + "=?", id);
        if (rows.isEmpty()) throw new ServiceException("数据不存在");
        if ("products".equals(type)) {
            List<Map<String,Object>> skus=new ClubProductSpecs(jdbc).read(id,false);
            rows.get(0).put("skus",skus);rows.get(0).put("sku_version",ClubProductSpecs.version(skus));
        }
        if (!"adminAudits".equals(type)) maskSensitive(type, rows);
        return rows.get(0);
    }

    public Map<String, Object> detail(String type, Long id, Long adminId)
    {
        if("applications".equals(type)){
            Map<String,Object> result=detail(type,id);
            Long userId=longValue(result.get("user_id"),0);
            Integer count=jdbc.queryForObject("select count(1) from club_identity where user_id=?",Integer.class,userId);
            if(count!=null&&count>0)result.put("identity",detail("identities",userId,adminId));
            return result;
        }
        if (!"identities".equals(type)) return detail(type, id);
        List<Map<String, Object>> rows = jdbc.queryForList("select * from club_identity where user_id=?", id);
        if (rows.isEmpty()) throw new ServiceException("实名认证记录不存在");
        Map<String, Object> source = rows.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", source.get("user_id"));
        result.put("realName", text(source.get("real_name_cipher")).isEmpty()?source.get("real_name_mask"):identityCrypto.decrypt(text(source.get("real_name_cipher"))));
        result.put("idNumber", text(source.get("id_no_cipher")).isEmpty()?source.get("id_no_mask"):identityCrypto.decrypt(text(source.get("id_no_cipher"))));
        result.put("evidenceJson", identityCrypto.decrypt(text(source.get("evidence_cipher"))));
        result.put("status", source.get("status"));
        result.put("reviewNote", source.get("review_note"));
        result.put("submittedAt", source.get("submitted_at"));
        result.put("reviewedAt", source.get("reviewed_at"));
        jdbc.update("insert into club_admin_audit(admin_id,permission_code,entity_type,entity_id,action,request_id,before_json,after_json,reason) values(?,?,?,?,?,?,?,?,?)",
                adminId, "club:identity:detail", "identities", String.valueOf(id), "view_sensitive", "VIEW-" + UUID.randomUUID(), "{}", "{}", "查看实名认证审核材料");
        return result;
    }

    private Map<String, Object> saveUser(Long id, Map<String, Object> input)
    {
        input = new HashMap<>(input);
        if (input.containsKey("gender")) input.put("gender", ClubGender.normalize(input.get("gender")));
        List<Map<String, Object>> rows = jdbc.queryForList("select * from club_user where id=? for update", id);
        if (rows.isEmpty()) throw new ServiceException("用户不存在");
        Map<String, Object> current = rows.get(0);
        String oldRole = text(current.get("user_type"));
        String targetRole = text(input.get("user_type"));
        if (targetRole.isEmpty()) targetRole = oldRole;
        if (!Arrays.asList("user", "player").contains(targetRole)) throw new ServiceException("用户角色不正确");
        String targetStatus = text(input.get("status"));
        if (!targetStatus.isEmpty() && !Arrays.asList("active", "disabled").contains(targetStatus)) throw new ServiceException("用户状态不正确");
        if (!oldRole.equals(targetRole))
        {
            if ("player".equals(targetRole)) throw new ServiceException("请通过打手入驻审核开通打手身份");
            roleLifecycle.switchRole(id, targetRole, text(first(input, "nickname")), "");
        }
        if ("disabled".equals(targetStatus) && "active".equals(text(current.get("status")))) roleLifecycle.deactivateAccount(id);
        updateOptionalFields("club_user", id, input, fields("nickname", "phone", "email", "avatar", "gender", "city", "status"));
        Map<String, Object> result = jdbc.queryForMap("select * from club_user where id=?", id);
        result.remove("password_hash");
        return result;
    }

    private boolean activeUser(Long userId)
    {
        Integer count = jdbc.queryForObject("select count(1) from club_user where id=? and status='active'", Integer.class, userId);
        return count != null && count == 1;
    }

    private void updateOptionalFields(String table, Long id, Map<String, Object> input, List<String> allowed)
    {
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (String field : allowed) if (input.containsKey(field)) { sets.add(field + "=?"); args.add(input.get(field)); }
        if (!sets.isEmpty())
        {
            args.add(id);
            jdbc.update("update " + table + " set " + String.join(",", sets) + " where id=?", args.toArray());
        }
    }

    private Map<String, Object> snapshot(String type, Long id)
    {
        if (id == null || !entities.containsKey(type)) return new LinkedHashMap<>();
        Entity entity = entity(type);
        List<Map<String, Object>> rows = jdbc.queryForList("select * from " + entity.table + " where " + entity.idColumn + "=?", id);
        if (rows.isEmpty()) return new LinkedHashMap<>();
        maskSensitive(type, rows);
        return rows.get(0);
    }

    private void audit(Long adminId, String type, Object entityId, String action, Map<String, Object> before,
            Map<String, Object> after, Map<String, Object> context)
    {
        String requestId = text(first(context, "request_id", "requestId"));
        if (requestId.isEmpty()) requestId = "ADMIN-" + UUID.randomUUID();
        String reason = text(first(context, "reason", "review_note", "note"));
        jdbc.update("insert into club_admin_audit(admin_id,permission_code,entity_type,entity_id,action,request_id,before_json,after_json,reason) values(?,?,?,?,?,?,?,?,?)",
                adminId, auditPermission(type), type, entityId == null ? "" : String.valueOf(entityId), action,
                requestId, safeAuditJson(before), safeAuditJson(after), reason);
    }

    private String auditPermission(String type)
    {
        if ("orders".equals(type)) return "club:order:operate";
        if ("aftersales".equals(type)) return "club:aftersale:review";
        if ("applications".equals(type)) return "club:application:review";
        if ("identities".equals(type)) return "club:identity:review";
        if ("withdrawals".equals(type)) return "club:withdrawal:review";
        if ("receivables".equals(type)) return "club:receivable:writeoff";
        if ("users".equals(type)) return "club:user:edit";
        if (Arrays.asList("products", "skus").contains(type)) return "club:product:edit";
        if (Arrays.asList("players", "playerServices").contains(type)) return "club:player:edit";
        if ("shops".equals(type)) return "club:shop:edit";
        if (Arrays.asList("content", "categories", "messages", "reviews").contains(type)) return "club:content:edit";
        if (Arrays.asList("businessConfig", "rechargeTiers").contains(type)) return "club:finance-config:edit";
        if ("customerConfig".equals(type)) return "club:customer-service:edit";
        if ("teenSettings".equals(type)) return "club:teen-config:edit";
        if (Arrays.asList("coupons", "couponIssues", "rechargeRules", "rechargeRewards").contains(type)) return "club:coupon:edit";
        return "club:config:edit";
    }

    private String safeAuditJson(Map<String, Object> value)
    {
        return JSON.toJSONString(sanitizeAuditValue(value));
    }

    private Object sanitizeAuditValue(Object value)
    {
        if (value instanceof Map)
        {
            Map<?, ?> source = (Map<?, ?>) value;
            Map<String, Object> safe = new LinkedHashMap<>();
            for (Map.Entry<?, ?> item : source.entrySet())
            {
                String name = String.valueOf(item.getKey());
                String key = name.toLowerCase();
                if (key.contains("password") || key.contains("hash") || key.contains("token") || key.contains("secret") ||
                        key.contains("idempotency") || key.contains("session") || key.contains("credential")) continue;
                if (key.contains("phone") || key.contains("email") || key.contains("id_no") || key.contains("idcard") ||
                        key.contains("contact") || key.contains("evidence") || key.contains("voucher"))
                    safe.put(name, "[已脱敏]");
                else safe.put(name, sanitizeAuditValue(item.getValue()));
            }
            return safe;
        }
        if (value instanceof Iterable)
        {
            List<Object> safe = new ArrayList<>();
            for (Object item : (Iterable<?>) value) safe.add(sanitizeAuditValue(item));
            return safe;
        }
        return value;
    }

    private static void validateDateWindow(Object start, Object end)
    {
        if (start == null || end == null) return;
        try
        {
            LocalDateTime from = LocalDateTime.parse(text(start).replace(" ", "T"));
            LocalDateTime until = LocalDateTime.parse(text(end).replace(" ", "T"));
            if (!until.isAfter(from)) throw new ServiceException("结束时间必须晚于开始时间");
        }
        catch (ServiceException e) { throw e; }
        catch (Exception e) { throw new ServiceException("生效时间格式不正确"); }
    }

    private static void validateJson(String value, String label)
    {
        if (value.isEmpty()) return;
        try { JSON.parse(value); }
        catch (Exception e) { throw new ServiceException(label + "JSON格式不正确"); }
    }

    private List<Long> userIds(Object value)
    {
        List<Long> result = new ArrayList<>();
        if (value instanceof List)
        {
            for (Object item : (List<?>) value)
            {
                Long id = optionalLong(item);
                if (id != null && !result.contains(id)) result.add(id);
            }
        }
        else
        {
            for (String item : text(value).split(","))
            {
                Long id = optionalLong(item);
                if (id != null && !result.contains(id)) result.add(id);
            }
        }
        return result;
    }

    private Map<String, Object> reviewApplication(Long id, Map<String, Object> input, Long adminId)
    {
        String status = reviewStatus(input);
        List<Map<String, Object>> rows = jdbc.queryForList("select * from club_application where id=? for update", id);
        if (rows.isEmpty()) throw new ServiceException("申请不存在");
        Map<String, Object> application = rows.get(0);
        if (!"pending".equals(text(application.get("status")))) throw new ServiceException("申请已审核");
        if(!"player".equals(text(application.get("application_type"))))throw new ServiceException("历史商家申请不能作为打手审核，请重新提交打手申请");
        Long identityUserId=longValue(application.get("user_id"),0);
        List<Map<String,Object>> identities=jdbc.queryForList("select status from club_identity where user_id=? for update",identityUserId);
        if("approved".equals(status)&&(identities.isEmpty()||!Arrays.asList("pending","approved").contains(text(identities.get(0).get("status")))))throw new ServiceException("缺少可审核的身份资料，请先补充");
        jdbc.update("update club_identity set status=?,review_note=?,reviewed_at=now() where user_id=? and status='pending'",status,text(input.get("review_note")),identityUserId);
        jdbc.update("update club_application set status=?,review_note=?,reviewed_by=?,reviewed_at=now() where id=?",
                status, text(input.get("review_note")), adminId, id);
        if ("approved".equals(status))
        {
            String type = text(application.get("application_type"));
            Long applicantId = longValue(application.get("user_id"), 0);
            roleLifecycle.switchRole(applicantId, type, emptyFallback(application.get("display_name"), application.get("real_name")), text(application.get("remark")));
            if ("player".equals(type))
            {
                jdbc.update("insert into club_player_profile(user_id,display_name,gender,city,intro,status) select id,?,gender,city,?,'active' from club_user where id=? " +
                                "on duplicate key update display_name=values(display_name),status='active'",
                        emptyFallback(application.get("display_name"), application.get("real_name")), application.get("remark"), applicantId);
            }
            else
            {
                jdbc.update("insert into club_shop(name,description,owner_user_id,status) values(?,?,?,'active') " +
                                "on duplicate key update name=values(name),description=values(description),status='active'",
                        emptyFallback(application.get("display_name"), application.get("real_name") + "的店铺"), application.get("remark"), applicantId);
            }
        }
        notifyReview(application.get("user_id"), "入驻申请审核结果", "您的打手入驻申请" + ("approved".equals(status)?"已通过":"已驳回"),
                "application", id);
        return jdbc.queryForMap("select * from club_application where id=?", id);
    }

    private Map<String, Object> reviewWithdrawal(Long id, Map<String, Object> input, Long adminId)
    {
        String status = reviewStatus(input);
        List<Map<String, Object>> rows = jdbc.queryForList("select * from club_withdrawal where id=? for update", id);
        if (rows.isEmpty()) throw new ServiceException("提现申请不存在");
        Map<String, Object> withdrawal = rows.get(0);
        String current = text(withdrawal.get("status"));
        if (status.equals(current)) return withdrawal;
        if (!"pending".equals(current)) throw new ServiceException("提现申请已按其他结果处理，不能重复审核");
        BigDecimal amount = money(withdrawal.get("amount"));
        Long userId = longValue(withdrawal.get("user_id"), 0);
        Map<String, Object> wallet = jdbc.queryForMap("select balance,frozen from club_wallet where user_id=? for update", userId);
        BigDecimal balanceBefore = money(wallet.get("balance"));
        BigDecimal frozenBefore = money(wallet.get("frozen"));
        if (frozenBefore.compareTo(amount) < 0) throw new ServiceException("钱包冻结金额不足，审核已停止");
        if ("approved".equals(status))
        {
            String transferNo=required(input,"transfer_reference");
            if(transferNo.length()>128)throw new ServiceException("打款凭证号过长");
            if (jdbc.update("update club_wallet set frozen=frozen-?,total_withdrawn=total_withdrawn+?,version=version+1 where user_id=? and frozen>=?", amount, amount, userId, amount) != 1)
                throw new ServiceException("钱包状态发生变化，请重试");
            jdbc.update("update club_withdrawal set transfer_reference=? where id=?",transferNo,id);
            jdbc.update("insert into club_wallet_record(user_id,record_type,amount,balance_before,balance_after,frozen_after,reference_no,counterparty_type,description) values(?,?,?,?,?,?,?,?,?)",
                    userId, "withdraw_approved", BigDecimal.ZERO, balanceBefore, balanceBefore, frozenBefore.subtract(amount), withdrawal.get("withdrawal_no"), "withdrawal", "人工打款确认完成");
        }
        else
        {
            if (jdbc.update("update club_wallet set frozen=frozen-?,balance=balance+?,version=version+1 where user_id=? and frozen>=?", amount, amount, userId, amount) != 1)
                throw new ServiceException("钱包状态发生变化，请重试");
            jdbc.update("insert into club_wallet_record(user_id,record_type,amount,balance_before,balance_after,frozen_after,reference_no,counterparty_type,description) values(?,?,?,?,?,?,?,?,?)",
                    userId, "withdraw_rejected", amount, balanceBefore, balanceBefore.add(amount), frozenBefore.subtract(amount), withdrawal.get("withdrawal_no"), "withdrawal", "提现驳回退回余额");
        }
        jdbc.update("update club_withdrawal set status=?,review_note=?,reviewed_by=?,reviewed_at=now() where id=?", status, text(input.get("review_note")), adminId, id);
        notifyReview(userId, "提现审核结果", "您的提现申请" + ("approved".equals(status)?"已完成打款":"已驳回"), "withdrawal", id);
        return jdbc.queryForMap("select * from club_withdrawal where id=?", id);
    }

    private Map<String, Object> updateFields(String table, Long id, Map<String, Object> input, List<String> allowed)
    {
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (String field : allowed)
        {
            if (input.containsKey(field))
            {
                sets.add(field + "=?");
                args.add(input.get(field));
            }
        }
        if (sets.isEmpty()) throw new ServiceException("没有可更新的字段");
        args.add(id);
        jdbc.update("update " + table + " set " + String.join(",", sets) + " where id=?", args.toArray());
        return jdbc.queryForMap("select * from " + table + " where id=?", id);
    }

    private void notifyReview(Object userId, String title, String content, String referenceType, Object referenceId)
    {
        jdbc.update("insert into club_message(user_id,message_type,title,content,reference_type,reference_id) values(?,?,?,?,?,?)",
                userId, "system", title, content, referenceType, referenceId);
    }

    private String buildWhere(Entity entity, String keyword, String status)
    {
        List<String> clauses = new ArrayList<>();
        if ("club_order".equals(entity.table)) clauses.add(ClubOrderVisibility.operational("club_order"));
        if (!keyword.isEmpty())
        {
            Long uid="club_user".equals(entity.table)?ClubUidSearch.parse(keyword):null;
            if(uid!=null)clauses.add("id=?");
            else {
            List<String> likes = new ArrayList<>();
            for (String column : entity.searchColumns.split(",")) likes.add(column + " like ?");
            clauses.add("(" + String.join(" or ", likes) + ")");
            }
        }
        if ("club_product".equals(entity.table)||"club_category".equals(entity.table)||"club_content".equals(entity.table)||"club_home_entry".equals(entity.table)||"club_coupon".equals(entity.table)) clauses.add("status<>'deleted'");
        if ("club_order".equals(entity.table)&&"paid".equals(status)) clauses.add("status in ('pending','accepted','serving','completed') and exists(select 1 from club_payment p where p.order_id=club_order.id and p.mode in ('wechat','balance') and p.status='success')");
        else if (!status.isEmpty() && hasStatus(entity.table)) clauses.add("status=?");
        return clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    }

    private List<Object> buildArgs(Entity entity, String keyword, String status)
    {
        List<Object> args = new ArrayList<>();
        if (!keyword.isEmpty()) {Long uid="club_user".equals(entity.table)?ClubUidSearch.parse(keyword):null;
            if(uid!=null)args.add(uid);else for (String ignored : entity.searchColumns.split(",")) args.add("%" + keyword + "%");}
        if (!status.isEmpty() && hasStatus(entity.table) && !("club_order".equals(entity.table)&&"paid".equals(status))) args.add(status);
        return args;
    }

    private void maskSensitive(String type, List<Map<String, Object>> rows)
    {
        if ("users".equals(type))
        {
            for (Map<String, Object> row : rows) row.remove("password_hash");
        }
        if ("identities".equals(type))
        {
            for (Map<String, Object> row : rows)
            {
                row.remove("id_no_hash");
                row.remove("real_name_cipher");
                row.remove("id_no_cipher");
                row.remove("evidence_cipher");
            }
        }
        if ("payments".equals(type))
        {
            for (Map<String, Object> row : rows) row.remove("idempotency_key");
        }
        if ("rechargeOrders".equals(type))
        {
            for (Map<String, Object> row : rows)
            {
                row.remove("idempotency_key");
                row.remove("callback_key");
            }
        }
        if ("teenSettings".equals(type))
        {
            for (Map<String, Object> row : rows) row.remove("pin_hash");
        }
        if ("adminAudits".equals(type))
        {
            for (Map<String, Object> row : rows)
            {
                row.remove("before_json");
                row.remove("after_json");
            }
        }
    }

    private Entity entity(String type)
    {
        Entity entity = entities.get(type);
        if (entity == null) throw new ServiceException("不支持的业务类型");
        return entity;
    }

    private Long scalar(String sql)
    {
        Long result = jdbc.queryForObject(sql, Long.class);
        return result == null ? 0L : result;
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

    private static boolean hasStatus(String table)
    {
        return !Arrays.asList("club_message", "club_wallet", "club_wallet_record", "club_order_log", "club_business_config", "club_teen_setting").contains(table);
    }

    private static String reviewStatus(Map<String, Object> input)
    {
        String status = text(input.get("status"));
        if (!Arrays.asList("approved", "rejected").contains(status)) throw new ServiceException("审核状态只能为approved或rejected");
        return status;
    }

    private static String state(Map<String, Object> input)
    {
        String status = text(input.get("status"));
        return status.isEmpty() ? "active" : status;
    }

    private static String activeInactiveState(Map<String, Object> input)
    {
        String status = state(input);
        if (!Arrays.asList("active", "inactive").contains(status)) throw new ServiceException("状态只能为启用或停用");
        return status;
    }

    private static String activeDisabledState(Map<String, Object> input)
    {
        String status = state(input);
        if (!Arrays.asList("active", "disabled").contains(status)) throw new ServiceException("状态只能为正常或停用");
        return status;
    }

    private static List<String> fields(String... values) { return Arrays.asList(values); }
    private static Long requireId(Long id) { if (id == null) throw new ServiceException("缺少数据编号"); return id; }
    private static Long optionalLong(Object value) { return value == null || text(value).isEmpty() ? null : longValue(value, 0); }
    private static Long longValue(Object value, long fallback) { try { return value == null ? fallback : Long.valueOf(text(value)); } catch (Exception e) { return fallback; } }
    private static int integer(Object value, int fallback) { try { return value == null || text(value).isEmpty() ? fallback : Integer.parseInt(text(value)); } catch (Exception e) { return fallback; } }
    private static boolean bool(Object value) { return Boolean.TRUE.equals(value) || "1".equals(text(value)) || "true".equalsIgnoreCase(text(value)); }
    private static BigDecimal money(Object value) { try { return new BigDecimal(text(value)); } catch (Exception e) { throw new ServiceException("金额格式不正确"); } }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static String required(Map<String, Object> input, String... keys)
    {
        Object value = first(input, keys);
        if (text(value).isEmpty()) throw new ServiceException("请填写" + keys[0]);
        return text(value);
    }
    private static Object first(Map<String, Object> input, String... keys)
    {
        for (String key : keys) if (input.containsKey(key) && input.get(key) != null) return input.get(key);
        return null;
    }
    private static String emptyFallback(Object value, Object fallback) { return text(value).isEmpty() ? text(fallback) : text(value); }
    private static Object emptyToNull(Object value) { return text(value).isEmpty() ? null : value; }
    private static String roleScope(Map<String, Object> input)
    {
        String role = text(first(input,"role_scope","roleScope"));
        if (role.isEmpty()) return "all";
        if (!Arrays.asList("all","user","player").contains(role)) throw new ServiceException("适用角色只能选择全部、顾客或打手");
        return role;
    }
    private String validPageUrl(String url)
    {
        String path = url.split("\\?",2)[0];
        if (!allowedPageRoutes.contains(path)) throw new ServiceException("跳转地址不是已注册的小程序页面");
        return url;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> loadPageRoutes()
    {
        try
        {
            String json = StreamUtils.copyToString(new ClassPathResource("club-page-routes.json").getInputStream(), StandardCharsets.UTF_8);
            List<String> routes = JSON.parseObject(json, List.class);
            if (routes == null || routes.isEmpty()) throw new IllegalStateException("页面路由清单为空");
            return new HashSet<>(routes);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("无法加载由pages.json生成的页面路由清单", e);
        }
    }

    private static class Entity
    {
        private final String table;
        private final String searchColumns;
        private final String orderBy;
        private final String idColumn;
        private Entity(String table, String searchColumns, String orderBy) { this(table, searchColumns, orderBy, "id"); }
        private Entity(String table, String searchColumns, String orderBy, String idColumn)
        {
            this.table = table; this.searchColumns = searchColumns; this.orderBy = orderBy; this.idColumn = idColumn;
        }
    }
}
