package com.ruoyi.club.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.club.web.ClubConflictException;
import com.wechat.pay.java.service.payments.model.Transaction;

/** 小程序端业务服务，所有写操作均以服务端数据和当前登录用户为准。 */
@Service
public class ClubAppService
{
    private static final Pattern ID_CARD = Pattern.compile("^[1-9]\\d{5}(18|19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx]$");
    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    private final JdbcTemplate jdbc;
    private final ClubAuthService auth;
    private final ClubBusinessService business;
    private final ClubCatalogService catalog;
    private final ClubTeenPolicyService teenPolicy;
    private final ClubMiniProgramCodeService miniProgramCode;
    private final ClubIdentityCryptoService identityCrypto;
    private final ClubWechatPayService wechatPay;

    @org.springframework.beans.factory.annotation.Autowired
    private ClubAfterSaleService afterSale;

    @Value("${club.payment-mode:disabled}")
    private String paymentMode;
    private boolean simulation(){return "simulation".equalsIgnoreCase(paymentMode);}

    @Value("${token.secret:local-development-secret-change-before-deploy}")
    private String identitySalt;

    public ClubAppService(JdbcTemplate jdbc, ClubAuthService auth, ClubBusinessService business,
            ClubCatalogService catalog, ClubTeenPolicyService teenPolicy, ClubMiniProgramCodeService miniProgramCode,
            ClubIdentityCryptoService identityCrypto, ClubWechatPayService wechatPay)
    {
        this.jdbc = jdbc;
        this.auth = auth;
        this.business = business;
        this.catalog = catalog;
        this.teenPolicy = teenPolicy;
        this.miniProgramCode = miniProgramCode;
        this.identityCrypto = identityCrypto;
        this.wechatPay = wechatPay;
    }

    public Map<String, Object> home()
    {
        Map<String, Object> result = new HashMap<>();
        result.put("categories", categories());
        result.put("products", productItems("", "", 1, 8, "default", true).get("items"));
        result.put("news", contentList("article", "newsCards"));
        Map<String, Object> promotions = new HashMap<>();
        promotions.put("startup", startupContent());
        promotions.put("homeBanners", contentList("promotion", "homeBanners"));
        promotions.put("newsCards", contentList("article", "newsCards"));
        result.put("promotions", promotions);
        return result;
    }

    public Map<String, Object> home(Long userId)
    {
        Map<String, Object> result = home();
        result.put("categories", categories(userId));
        if (!teenPolicy.isContentAllowed(userId, "product"))
        {
            result.put("categories", Collections.emptyList());
            result.put("products", Collections.emptyList());
        }
        if (!teenPolicy.isContentAllowed(userId, "news")) result.put("news", Collections.emptyList());
        @SuppressWarnings("unchecked")
        Map<String, Object> promotions = (Map<String, Object>) result.get("promotions");
        if (!teenPolicy.isContentAllowed(userId, "promotion"))
        {
            promotions.put("startup", null);
            promotions.put("homeBanners", Collections.emptyList());
        }
        if (!teenPolicy.isContentAllowed(userId, "news")) promotions.put("newsCards", Collections.emptyList());
        return result;
    }

    public List<Map<String, Object>> categories()
    {
        return categories(null);
    }

    public List<Map<String, Object>> categories(Long userId)
    {
        String role = "guest";
        if (userId != null)
        {
            List<String> roles = jdbc.query("select user_type from club_user where id=? and status='active'",
                    (rs, n) -> rs.getString(1), userId);
            role = roles.isEmpty() || roles.get(0) == null ? "user" : roles.get(0);
        }
        return jdbc.queryForList("select entry_code as id,name,icon,avatar_image as avatarImage,target_url as targetUrl,role_scope as roleScope " +
                "from club_home_entry where status='active' and (role_scope is null or role_scope='' or role_scope='all' or find_in_set(?,replace(role_scope,' ',''))>0) order by sort_no,id", role);
    }

    public Object promotions(String scene)
    {
        if ("startup".equals(scene))
        {
            return startupContent();
        }
        if (scene == null || scene.trim().isEmpty())
        {
            Map<String, Object> all = new HashMap<>();
            all.put("startup", startupContent());
            all.put("homeBanners", contentList("promotion", "homeBanners"));
            all.put("newsCards", contentList("article", "newsCards"));
            return all;
        }
        return contentList("newsCards".equals(scene) ? "article" : "promotion", scene);
    }

    public Map<String, Object> article(Long id)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,title,subtitle,image,body_text,style_json from club_content where id=? and content_type='article' and status='active' " +
                "and (start_at is null or start_at<=now()) and (end_at is null or end_at>=now())", id);
        if (rows.isEmpty())
        {
            throw new ServiceException("资讯不存在或已下架");
        }
        Map<String, Object> row = rows.get(0);
        row.put("body", lines(text(row.remove("body_text"))));
        row.put("bodyStyle", jsonMap(row.remove("style_json")));
        return row;
    }

    public Map<String, Object> document(String scene)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,title,subtitle,body_text as content,updated_at as updatedAt from club_content " +
                "where content_type='document' and scene=? and status='active' " +
                "and (start_at is null or start_at<=now()) and (end_at is null or end_at>=now()) order by sort_no,id limit 1", scene);
        if (rows.isEmpty())
        {
            throw new ServiceException("内容暂未配置");
        }
        Map<String, Object> row = rows.get(0);
        row.put("version", documentVersion(row));
        return row;
    }

    public Map<String, Object> productItems(String category, String keyword, int page, int pageSize, String sort)
    {
        return productItems(category, keyword, page, pageSize, sort, false);
    }

    public Map<String, Object> productItems(String category, String keyword, int page, int pageSize, String sort, boolean hotOnly)
    {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(50, Math.max(1, pageSize));
        String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
        String cat = category == null ? "" : category.trim();
        Integer total = jdbc.queryForObject(
                "select count(1) from club_product p join club_shop sh on sh.id=p.shop_id and sh.status='active' " +
                "where p.status='active' " + (hotOnly ? "and p.is_hot=1 " : "") + "and exists(select 1 from club_product_sku sx where sx.product_id=p.id and sx.status='active' and sx.stock>0) " +
                "and (?='' or p.category_code=?) and (?='' or p.name like ? or p.subtitle like ?)",
                Integer.class, cat, cat, keyword == null ? "" : keyword.trim(), like, like);
        String orderBy = productOrder(sort);
        List<Map<String, Object>> items = jdbc.queryForList(
                "select p.id,p.category_code as category,p.name,p.subtitle,p.image,sku.price,p.old_price as oldPrice,p.sales,p.views,p.is_hot as hot," +
                "s.name as shop,(s.base_fans_count+(select count(1) from club_follow f where f.shop_id=s.id)) as fans " +
                "from club_product p join club_shop s on s.id=p.shop_id and s.status='active' " +
                "join (select product_id,min(price) price from club_product_sku where status='active' and stock>0 group by product_id) sku on sku.product_id=p.id " +
                "where p.status='active' " + (hotOnly ? "and p.is_hot=1 " : "") + "and (?='' or p.category_code=?) and (?='' or p.name like ? or p.subtitle like ?) " +
                "order by " + orderBy + " limit ? offset ?",
                cat, cat, keyword == null ? "" : keyword.trim(), like, like, safeSize, (safePage - 1) * safeSize);
        normalizeBoolean(items, "hot");
        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("total", total == null ? 0 : total);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        result.put("hasMore", safePage * safeSize < (total == null ? 0 : total));
        result.put("sort", normalizeProductSort(sort));
        return result;
    }

    public Map<String, Object> product(Long id)
    {
        // Product views are defined as page views (PV), not unique visitors.  The
        // guarded atomic update avoids lost increments and never counts a hidden
        // product or a product belonging to a disabled shop.
        int viewed = jdbc.update("update club_product p join club_shop s on s.id=p.shop_id set p.views=p.views+1 " +
                "where p.id=? and p.status='active' and s.status='active'", id);
        if (viewed != 1) throw new ServiceException("商品不存在或已下架");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select p.id,p.shop_id as shopId,p.category_code as category,p.name,p.subtitle,p.image,p.detail_text as detailText,p.bound_player_id as playerId,(select display_name from club_player_profile where id=p.bound_player_id) as playerName," +
                "sku.price,p.old_price as oldPrice,sku.stock,p.sales,p.views,p.is_hot as hot,s.name as shop," +
                "(s.base_fans_count+(select count(1) from club_follow f where f.shop_id=s.id)) as fans " +
                "from club_product p join club_shop s on s.id=p.shop_id and s.status='active' " +
                "join (select product_id,min(price) price,sum(stock) stock from club_product_sku where status='active' group by product_id) sku on sku.product_id=p.id " +
                "where p.id=? and p.status='active'", id);
        if (rows.isEmpty()) throw new ServiceException("商品不存在或已下架");
        Map<String, Object> result = rows.get(0);
        result.put("hot", number(result.get("hot")) == 1L);
        result.put("skus", jdbc.queryForList("select id,name,price,stock from club_product_sku where product_id=? and status='active' order by id", id));
        result.put("reviews", jdbc.queryForList(
                "select r.id,r.rating,r.content,r.created_at as createdAt,u.nickname,u.avatar from club_review r " +
                "join club_user u on u.id=r.user_id where r.product_id=? and r.status='visible' order by r.id desc limit 20", id));
        Map<String, Object> reviewSummary = jdbc.queryForMap(
                "select count(1) as reviewCount,coalesce(round(avg(rating),1),0) as averageRating," +
                "coalesce(round(100*sum(case when rating>=4 then 1 else 0 end)/nullif(count(1),0),0),0) as goodRate " +
                "from club_review where product_id=? and status='visible'", id);
        result.putAll(reviewSummary);
        return result;
    }

    public Map<String, Object> playerItems(String gender, String keyword, int page, int pageSize, String sort, Long productId, Long skuId)
    {
        String sex = gender == null || "all".equals(gender) ? "" : gender;
        String word = keyword == null ? "" : keyword.trim();
        String like = "%" + word + "%";
        int safePage = Math.max(1, page);
        int safeSize = Math.min(50, Math.max(1, pageSize));
        String validService = "select skx.id from club_product pr join club_product_sku skx on skx.product_id=pr.id and skx.status='active' and skx.stock>0 " +
                "where pr.bound_player_id=pp.id and pr.status='active' and (? is null or pr.id=?) and (? is null or skx.id=?) order by skx.price,skx.id limit 1";
        Integer total = jdbc.queryForObject("select count(1) from club_player_profile pp join club_user u on u.id=pp.user_id and u.status='active' " +
                        "where pp.status='active' and pp.online_status=1 and "+(ClubPlayerEligibility.approved("pp")+" and not "+ClubPlayerCapacity.busy("pp.user_id"))+" and (" + validService + ") is not null and (?='' or pp.gender=?) and (?='' or pp.display_name like ?)",
                Integer.class, productId, productId, skuId, skuId, sex, sex, word, like);
        String orderBy = playerOrder(sort);
        List<Map<String, Object>> items = jdbc.queryForList(
                "select pp.id,pp.display_name as name,pp.gender,pp.age,pp.city,pp.intro,pp.image,pp.voice_url as voiceUrl,pp.voice_seconds as voice,pp.online_status as online," +
                "ps.product_id as productId,ps.id as skuId,ps.price as minPrice from club_player_profile pp join club_user u on u.id=pp.user_id and u.status='active' " +
                "join club_product_sku ps on ps.id=(" + validService + ") where pp.status='active' and pp.online_status=1 and "+(ClubPlayerEligibility.approved("pp")+" and not "+ClubPlayerCapacity.busy("pp.user_id"))+" " +
                "and (?='' or pp.gender=?) and (?='' or pp.display_name like ?) order by " + orderBy + " limit ? offset ?",
                productId, productId, skuId, skuId, sex, sex, word, like, safeSize, (safePage - 1) * safeSize);
        normalizeBoolean(items, "online");
        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("total", total == null ? 0 : total);
        result.put("page", safePage);
        result.put("pageSize", safeSize);
        result.put("hasMore", safePage * safeSize < (total == null ? 0 : total));
        result.put("sort", normalizePlayerSort(sort));
        return result;
    }

    /** Only currently purchasable services; the final order still revalidates binding and price. */
    public List<Map<String, Object>> playerOffers(Long playerId)
    {
        return jdbc.queryForList("select pr.id as productId,pr.name as productName,pr.image,pr.category_code as category," +
                "sk.id as skuId,sk.name as skuName,sk.price,sk.stock,pp.id as playerId " +
                "from club_player_profile pp join club_product pr on pr.bound_player_id=pp.id and pr.status='active' " +
                "join club_product_sku sk on sk.product_id=pr.id and sk.status='active' and sk.stock>0 " +
                "where pp.id=? and pp.status='active' and pp.online_status=1 and " + (ClubPlayerEligibility.approved("pp")+" and not "+ClubPlayerCapacity.busy("pp.user_id")) +
                " order by sk.price,pr.id,sk.sort_no,sk.id", playerId);
    }

    public List<Map<String, Object>> ranking()
    {
        if (!"1".equals(configValue("ranking_enabled", "1"))) return Collections.emptyList();
        String period = configValue("ranking_period", "all");
        String timeClause = "";
        if ("day".equals(period)) timeClause = " and o.paid_at>=curdate() ";
        else if ("week".equals(period)) timeClause = " and o.paid_at>=date_sub(curdate(),interval weekday(curdate()) day) ";
        else if ("month".equals(period)) timeClause = " and o.paid_at>=date_format(curdate(),'%Y-%m-01') ";
        int limit = Math.min(100, Math.max(1, integer(configValue("ranking_limit", "50"), 50)));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select u.id,u.nickname,u.avatar,coalesce(sum(o.total_amount),0) as amount from club_user u " +
                "join club_order o on o.user_id=u.id and o.status in ('pending','accepted','serving','completed') " +
                timeClause + "group by u.id,u.nickname,u.avatar order by amount desc,u.id limit ?", limit);
        if ("1".equals(configValue("ranking_mask_nickname", "1")))
        {
            for (Map<String, Object> row : rows) row.put("nickname", maskNickname(text(row.get("nickname"))));
        }
        return rows;
    }

    public Map<String, Object> announcement(Long id)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,title,subtitle,image,body_text,target_url as target,start_at as startAt,end_at as endAt " +
                "from club_content where id=? and content_type='promotion' and scene='startup' and status='active' " +
                "and (start_at is null or start_at<=now()) and (end_at is null or end_at>=now())", id);
        if (rows.isEmpty()) throw new ServiceException("公告不存在、未生效或已下架");
        Map<String, Object> result = rows.get(0);
        result.put("body", lines(text(result.remove("body_text"))));
        return result;
    }

    public Map<String, Object> customerService()
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select service_name as name,icon,avatar_image as avatar,online_status as onlineStatus,contact_text as contactText,page_url as pageUrl,qr_image as qrImage,status " +
                "from club_customer_config where status='active' order by id desc limit 1");
        if (!rows.isEmpty())
        {
            Map<String, Object> result = rows.get(0);
            result.put("enabled", "active".equals(text(result.remove("status"))));
            return result;
        }
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("name", "暂未配置");
        fallback.put("icon", "customer-cartoon");
        fallback.put("avatar", "");
        fallback.put("onlineStatus", "offline");
        fallback.put("contactText", "暂未配置");
        fallback.put("pageUrl", "/pages/service/customer");
        fallback.put("enabled", false);
        return fallback;
    }

    public Map<String, Object> profile(Long userId)
    {
        Map<String, Object> result = new HashMap<>(auth.userView(userId));
        List<Map<String, Object>> identities = jdbc.queryForList(
                "select real_name_mask as realName,id_no_mask as idNumber,status,review_note as reviewNote from club_identity where user_id=?", userId);
        result.put("identity", identities.isEmpty() ? null : identities.get(0));
        return result;
    }

    public Map<String, Object> updateProfile(Long userId, Map<String, Object> input)
    {
        String nickname = required(input, "nickname", "请输入昵称");
        if (nickname.length() > 64)
        {
            throw new ServiceException("昵称不能超过64个字符");
        }
        String avatar = limited(input.get("avatar"), 500);
        String gender = ClubGender.normalize(input.get("gender"));
        String city = limited(input.get("city"), 64);
        String birthday = text(input.get("birthday"));
        if (!birthday.isEmpty() && !birthday.matches("^\\d{4}-\\d{2}-\\d{2}$"))
        {
            throw new ServiceException("生日格式应为YYYY-MM-DD");
        }
        jdbc.update("update club_user set nickname=?,avatar=?,gender=?,city=?,birthday=? where id=?",
                nickname, avatar, gender.isEmpty() ? "unknown" : gender, city, birthday.isEmpty() ? null : Date.valueOf(birthday), userId);
        return profile(userId);
    }

    public Map<String, Object> relationState(Long userId, Long productId)
    {
        Map<String, Object> result = new HashMap<>();
        Integer favorite = jdbc.queryForObject("select count(1) from club_favorite where user_id=? and product_id=?", Integer.class, userId, productId);
        Long shopId = jdbc.queryForObject("select shop_id from club_product where id=?", Long.class, productId);
        Integer followed = jdbc.queryForObject("select count(1) from club_follow where user_id=? and shop_id=?", Integer.class, userId, shopId);
        result.put("favorite", favorite != null && favorite > 0);
        result.put("followed", followed != null && followed > 0);
        result.putAll(catalog.syncShopFans(shopId));
        return result;
    }

    @Transactional
    public Map<String, Object> setFavorite(Long userId, Long productId, boolean target)
    {
        List<Map<String, Object>> products = jdbc.queryForList(
                "select p.id,p.status,s.status as shop_status from club_product p join club_shop s on s.id=p.shop_id where p.id=? for update",
                productId);
        if (products.isEmpty()) throw new ServiceException("商品不存在");
        if (target && (!"active".equals(text(products.get(0).get("status")))
                || !"active".equals(text(products.get(0).get("shop_status")))))
        {
            throw new ServiceException("商品不存在、已下架或店铺已停用");
        }
        if (target)
        {
            jdbc.update("insert ignore into club_favorite(user_id,product_id) values(?,?)", userId, productId);
        }
        else
        {
            jdbc.update("delete from club_favorite where user_id=? and product_id=?", userId, productId);
        }
        Integer mine = jdbc.queryForObject("select count(1) from club_favorite where user_id=? and product_id=?", Integer.class, userId, productId);
        Integer total = jdbc.queryForObject("select count(1) from club_favorite where product_id=?", Integer.class, productId);
        Map<String, Object> result = flag("favorite", mine != null && mine == 1);
        result.put("relationCount", total == null ? 0 : total);
        return result;
    }

    public List<Map<String, Object>> favorites(Long userId)
    {
        return jdbc.queryForList(
                "select p.id,p.name,p.subtitle,p.image,p.price,p.old_price as oldPrice,p.sales,p.views from club_favorite f " +
                "join club_product p on p.id=f.product_id and p.status='active' where f.user_id=? order by f.id desc", userId);
    }

    @Transactional
    public Map<String, Object> setFollow(Long userId, Long shopId, boolean target)
    {
        List<Map<String, Object>> shops = jdbc.queryForList("select id,status from club_shop where id=? for update", shopId);
        if (shops.isEmpty()) throw new ServiceException("店铺不存在");
        if (target && !"active".equals(text(shops.get(0).get("status"))))
        {
            throw new ServiceException("店铺已停用，暂时不能关注");
        }
        if (target) jdbc.update("insert ignore into club_follow(user_id,shop_id) values(?,?)", userId, shopId);
        else jdbc.update("delete from club_follow where user_id=? and shop_id=?", userId, shopId);
        Map<String, Object> result = catalog.syncShopFans(shopId);
        Integer mine = jdbc.queryForObject("select count(1) from club_follow where user_id=? and shop_id=?", Integer.class, userId, shopId);
        result.put("followed", mine != null && mine == 1);
        return result;
    }

    public List<Map<String, Object>> follows(Long userId)
    {
        return jdbc.queryForList("select s.id,s.name,s.logo,s.description,(s.base_fans_count+(select count(1) from club_follow x where x.shop_id=s.id)) as fans " +
                "from club_follow f join club_shop s on s.id=f.shop_id and s.status='active' where f.user_id=? order by f.id desc", userId);
    }

    public Map<String, Object> createPoster(Long userId, Long productId)
    {
        ensureExists("club_product", productId, "商品不存在");
        String qrImage;
        try { qrImage = simulation() ? "" : miniProgramCode.productCode(productId); }
        catch (ServiceException unavailable) { qrImage = ""; } // Poster can still be saved without a fake QR code; UI clearly indicates unavailable.
        String status = qrImage.isEmpty() ? "unavailable" : "ready";
        Long posterId = insertAndKey("insert into club_poster(user_id,product_id,qr_image,code_status) values(?,?,?,?)", userId, productId, qrImage, status);
        return jdbc.queryForMap("select cp.id,cp.product_id as productId,p.name as productName,p.image,cp.qr_image as qrImage,cp.code_status as codeStatus,cp.created_at as createdAt from club_poster cp join club_product p on p.id=cp.product_id where cp.id=? and cp.user_id=?", posterId, userId);
    }

    public List<Map<String, Object>> posters(Long userId)
    {
        return jdbc.queryForList("select cp.id,cp.product_id as productId,p.name as productName,p.image,cp.qr_image as qrImage,cp.code_status as codeStatus,cp.created_at as createdAt from club_poster cp join club_product p on p.id=cp.product_id where cp.user_id=? order by cp.id desc", userId);
    }

    public List<Map<String,Object>> claimableCoupons(Long userId){return new ClubCouponGrantService(jdbc).publicCoupons(userId);}
    @Transactional
    public void claimCoupon(Long userId,Long couponId){new ClubCouponGrantService(jdbc).claim(userId,couponId);}

    public List<Map<String, Object>> coupons(Long userId)
    {
        return jdbc.queryForList(
                "select i.id,i.coupon_id as couponId,c.name,c.amount,c.min_spend as minSpend,c.valid_from as validFrom,c.valid_until as validUntil," +
                "c.product_id as productId,i.quantity,i.reserved_count as reservedCount,i.remaining_count as remainingCount," +
                "case when now()>c.valid_until then 'expired' when i.remaining_count<=0 then 'used' else i.status end as status " +
                "from club_coupon_issue i join club_coupon c on c.id=i.coupon_id where i.user_id=? order by i.id desc", userId);
    }

    /** 服务端价格试算；前端不得自行决定优惠金额或可用优惠券。 */
    public Map<String, Object> quoteOrder(Long userId, Map<String, Object> input)
    {
        Long productId = nestedId(input, "productId", "product");
        Long skuId = nestedId(input, "skuId", "sku");
        Long playerId = nestedOptionalId(input, "playerId", "player");
        int quantity = integer(input.get("qty"), 1);
        if (quantity < 1 || quantity > 10) throw new ServiceException("单次购买数量必须在1至10之间");
        List<Map<String, Object>> items = jdbc.queryForList(
                "select p.id as productId,p.name as productName,s.id as skuId,s.name as skuName,s.price,s.stock " +
                "from club_product p join club_shop sh on sh.id=p.shop_id and sh.status='active' " +
                "join club_product_sku s on s.product_id=p.id and s.status='active' where p.id=? and s.id=? and p.status='active'",
                productId, skuId);
        if (items.isEmpty()) throw new ServiceException("商品或规格不可购买");
        Map<String, Object> item = items.get(0);
        if (integer(item.get("stock"), 0) < quantity) throw new ServiceException("库存不足");
        Map<String, Object> provider = catalog.resolveProvider(productId, skuId, playerId);
        BigDecimal original = decimal(item.get("price")).multiply(BigDecimal.valueOf(quantity));
        Long selectedId = input.get("couponIssueId") == null || text(input.get("couponIssueId")).trim().isEmpty()
                ? null : number(input.get("couponIssueId"));
        BigDecimal selectedDiscount = BigDecimal.ZERO;
        List<Map<String, Object>> couponRows = jdbc.queryForList(
                "select i.id,i.coupon_id as couponId,c.name,c.amount,c.min_spend as minSpend,c.valid_from as validFrom,c.valid_until as validUntil," +
                "i.remaining_count as remainingCount,i.reserved_count as reservedCount,i.status,c.status as couponStatus,c.product_id as restrictedProductId " +
                "from club_coupon_issue i join club_coupon c on c.id=i.coupon_id where i.user_id=? order by c.amount desc,i.id desc", userId);
        boolean selectedFound = selectedId == null;
        for (Map<String, Object> coupon : couponRows)
        {
            boolean available = "active".equals(text(coupon.get("status"))) && "active".equals(text(coupon.get("couponStatus")))
                    && integer(coupon.get("remainingCount"), 0) - integer(coupon.get("reservedCount"), 0) > 0;
            Integer current = jdbc.queryForObject(
                    "select count(1) from club_coupon_issue i join club_coupon c on c.id=i.coupon_id where i.id=? and now() between c.valid_from and c.valid_until",
                    Integer.class, coupon.get("id"));
            boolean scopeMatches = coupon.get("restrictedProductId")==null || productId.equals(number(coupon.get("restrictedProductId")));
            boolean applicable = scopeMatches && available && current != null && current == 1 && original.compareTo(decimal(coupon.get("minSpend"))) >= 0;
            BigDecimal discount = applicable ? decimal(coupon.get("amount")).min(original) : BigDecimal.ZERO;
            coupon.put("applicable", applicable);
            coupon.put("discount", discount);
            coupon.put("payable", original.subtract(discount));
            if (selectedId != null && selectedId.equals(number(coupon.get("id"))))
            {
                selectedFound = true;
                if (!applicable) throw new ServiceException("所选优惠券当前不可使用");
                selectedDiscount = discount;
            }
        }
        if (!selectedFound) throw new ServiceException("优惠券不存在");
        BigDecimal payable = original.subtract(selectedDiscount);
        teenPolicy.assertCanOrder(userId, payable);
        Map<String, Object> result = new HashMap<>();
        result.put("product", item);
        result.put("provider", provider);
        result.put("quantity", quantity);
        result.put("originalAmount", original);
        result.put("discountAmount", selectedDiscount);
        result.put("payableAmount", payable);
        result.put("couponIssueId", selectedId);
        result.put("coupons", couponRows);
        return result;
    }

    @Transactional
    public Map<String, Object> createOrder(Long userId, Map<String, Object> input)
    {
        String clientRequestId = required(input, "clientRequestId", "缺少订单幂等编号");
        if (clientRequestId.length() > 96) throw new ServiceException("订单幂等编号过长");
        Long productId = nestedId(input, "productId", "product");
        Long skuId = nestedId(input, "skuId", "sku");
        Long playerId = nestedOptionalId(input, "playerId", "player");
        Long couponIssueId = input.get("couponIssueId") == null || text(input.get("couponIssueId")).isEmpty() ? null : number(input.get("couponIssueId"));
        int quantity = integer(input.get("qty"), 1);
        Map<String, Object> form = map(input.get("form"));
        String requestPayloadHash = orderPayloadHash(productId, skuId, playerId, couponIssueId, quantity, form);
        List<Map<String, Object>> existing = jdbc.queryForList("select id,request_payload_hash from club_order where user_id=? and client_request_id=?", userId, clientRequestId);
        if (!existing.isEmpty()) return existingOrderOrConflict(userId, existing.get(0), requestPayloadHash);
        if (quantity < 1 || quantity > 10)
        {
            throw new ServiceException("单次购买数量必须在1至10之间");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select p.id as product_id,p.name as product_name,p.image,p.status as product_status,sh.status as shop_status," +
                "s.id as sku_id,s.name as sku_name,s.price,s.stock,s.status as sku_status " +
                "from club_product p join club_shop sh on sh.id=p.shop_id join club_product_sku s on s.product_id=p.id " +
                "where p.id=? and s.id=? for update", productId, skuId);
        if (rows.isEmpty() || !"active".equals(text(rows.get(0).get("product_status"))) || !"active".equals(text(rows.get(0).get("sku_status"))))
        {
            throw new ServiceException("商品或规格不可购买");
        }
        Map<String, Object> item = rows.get(0);
        // 相同幂等号的并发请求可能在上面的SKU行锁处等待。拿到锁后必须再次查询，
        // 否则第二个请求会继续校验已被第一个请求预占的优惠券并错误失败。
        existing = jdbc.queryForList("select id,request_payload_hash from club_order where user_id=? and client_request_id=? for update", userId, clientRequestId);
        if (!existing.isEmpty()) return existingOrderOrConflict(userId, existing.get(0), requestPayloadHash);
        if (!"active".equals(text(item.get("shop_status")))) throw new ServiceException("商品所属店铺已停用");
        if (integer(item.get("stock"), 0) < quantity)
        {
            throw new ServiceException("库存不足");
        }
        String gameId = limited(form.get("gameId"), 100);
        String gameName = limited(form.get("gameName"), 100);
        if (gameId.isEmpty() || gameName.isEmpty())
        {
            throw new ServiceException("请填写游戏ID和游戏昵称");
        }
        String contact = limited(form.get("contact"), 64);
        String phone = limited(form.get("phone"), 20);
        if (!PHONE.matcher(phone).matches())
        {
            throw new ServiceException("请填写有效的联系电话（中国大陆手机号）");
        }
        String remark = limited(form.get("remark"), 500);
        BigDecimal price = decimal(item.get("price"));
        BigDecimal original = price.multiply(BigDecimal.valueOf(quantity));
        BigDecimal discount = BigDecimal.ZERO;
        if (couponIssueId != null)
        {
            List<Map<String, Object>> coupons = jdbc.queryForList(
                    "select i.id,i.remaining_count,i.reserved_count,i.status,c.amount,c.min_spend,c.valid_from,c.valid_until,c.status as coupon_status,c.product_id as restricted_product_id " +
                    "from club_coupon_issue i join club_coupon c on c.id=i.coupon_id where i.id=? and i.user_id=? for update",
                    couponIssueId, userId);
            if (coupons.isEmpty()) throw new ServiceException("优惠券不存在");
            Map<String, Object> coupon = coupons.get(0);
            if(coupon.get("restricted_product_id")!=null&&!productId.equals(number(coupon.get("restricted_product_id"))))throw new ServiceException("优惠券不适用于该商品");
            if (!"active".equals(text(coupon.get("status"))) || !"active".equals(text(coupon.get("coupon_status"))))
                throw new ServiceException("优惠券不可使用");
            Integer valid = jdbc.queryForObject("select count(1) from club_coupon_issue i join club_coupon c on c.id=i.coupon_id where i.id=? and now() between c.valid_from and c.valid_until", Integer.class, couponIssueId);
            if (valid == null || valid != 1) throw new ServiceException("优惠券未生效或已过期");
            if (integer(coupon.get("remaining_count"), 0) - integer(coupon.get("reserved_count"), 0) < 1)
                throw new ServiceException("优惠券已被占用或使用");
            if (original.compareTo(decimal(coupon.get("min_spend"))) < 0) throw new ServiceException("订单金额未达到优惠券使用门槛");
            discount = decimal(coupon.get("amount")).min(original);
        }
        BigDecimal total = original.subtract(discount);
        teenPolicy.assertCanOrder(userId, total);
        Map<String, Object> provider = catalog.resolveProvider(productId, skuId, playerId);
        ClubPlayerCapacity.assertAvailable(jdbc,provider.get("provider_user_id"),null);
        String orderNo = "SJZ" + System.currentTimeMillis() + randomDigits();
        int inserted = jdbc.update("insert ignore into club_order(order_no,client_request_id,request_payload_hash,user_id,product_id,sku_id,player_id,provider_type,provider_id,provider_user_id,product_name,sku_name,product_image,quantity,unit_price,original_amount,discount_amount,coupon_issue_id,total_amount,game_id,game_nickname,contact_name,contact_phone,contact,remark,status,payment_expires_at) " +
                        "values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'unpaid',date_add(now(),interval 30 minute))",
                orderNo, clientRequestId, requestPayloadHash, userId, productId, skuId, provider.get("provider_id"), provider.get("provider_type"), provider.get("provider_id"), provider.get("provider_user_id"),
                item.get("product_name"), item.get("sku_name"), item.get("image"), quantity,
                price, original, discount, couponIssueId, total, gameId, gameName, contact, phone,
                (contact + (phone.isEmpty() ? "" : " " + phone)).trim(), remark);
        if (inserted != 1)
        {
            Map<String, Object> duplicate = jdbc.queryForMap("select id,request_payload_hash from club_order where user_id=? and client_request_id=? for update", userId, clientRequestId);
            return existingOrderOrConflict(userId, duplicate, requestPayloadHash);
        }
        Long orderId = jdbc.queryForObject("select id from club_order where order_no=?", Long.class, orderNo);
        if (couponIssueId != null)
        {
            jdbc.update("update club_coupon_issue set reserved_count=reserved_count+1 where id=?", couponIssueId);
            jdbc.update("insert into club_order_coupon(order_id,coupon_issue_id,discount_amount,status) values(?,?,?,'reserved')", orderId, couponIssueId, discount);
        }
        logOrder(orderId, "", "unpaid", "user", userId, "用户创建订单");
        return order(userId, orderId);
    }

    private Map<String, Object> existingOrderOrConflict(Long userId, Map<String, Object> existing, String payloadHash)
    {
        String previous = text(existing.get("request_payload_hash"));
        if (!previous.isEmpty() && !previous.equals(payloadHash))
            throw new ClubConflictException("同一订单幂等编号不能用于不同的商品、规格或下单资料");
        return order(userId, number(existing.get("id")));
    }

    private String orderPayloadHash(Long productId, Long skuId, Long playerId, Long couponId, int quantity, Map<String, Object> form)
    {
        String normalized = productId + "|" + skuId + "|" + String.valueOf(playerId) + "|" + String.valueOf(couponId) + "|" + quantity + "|" +
                text(form.get("gameId")) + "|" + text(form.get("gameName")) + "|" + text(form.get("contact")) + "|" +
                text(form.get("phone")) + "|" + text(form.get("remark"));
        return sha256(normalized);
    }

    @Transactional
    public List<Map<String, Object>> orders(Long userId, String status)
    {
        lockPaymentUser(userId);
        expireUnpaidOrdersForUser(userId);
        String filter = status == null || "all".equals(status) ? "" : status;
        boolean aftersale = "aftersale".equals(filter);
        boolean reviewPending = "review".equals(filter);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select o.*,"+ClubPlayerCapacity.elapsed("o")+" as occupied_seconds,case when o.status='unpaid' and o.payment_expires_at is not null then greatest(timestampdiff(second,now(),o.payment_expires_at),0) else 0 end as payment_remaining_seconds," +
                "pp.display_name as player_name,pp.image as player_image,exists(select 1 from club_review r where r.order_id=o.id) as reviewed from club_order o left join club_player_profile pp on pp.id=o.player_id " +
                "where " + ClubOrderVisibility.operational("o") + " and o.user_id=? and (?='' or (?=1 and o.status in ('refunding','refunded')) or (?=1 and (o.status='completed' or (o.status in ('refunding','refunded') and o.completed_at is not null)) and not exists(select 1 from club_review rr where rr.order_id=o.id)) or (?=0 and ?=0 and o.status=?)) order by o.id desc",
                userId, filter, aftersale ? 1 : 0, reviewPending ? 1 : 0, aftersale ? 1 : 0, reviewPending ? 1 : 0, filter);
        return orderViews(rows);
    }

    @Transactional
    public Map<String, Object> order(Long userId, Long orderId)
    {
        lockPaymentUser(userId);
        expireUnpaidOrder(userId, orderId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select o.*,"+ClubPlayerCapacity.elapsed("o")+" as occupied_seconds,case when o.status='unpaid' and o.payment_expires_at is not null then greatest(timestampdiff(second,now(),o.payment_expires_at),0) else 0 end as payment_remaining_seconds," +
                "pp.display_name as player_name,pp.image as player_image,exists(select 1 from club_review r where r.order_id=o.id) as reviewed from club_order o left join club_player_profile pp on pp.id=o.player_id " +
                "where o.id=? and o.user_id=? for update", orderId, userId);
        if (rows.isEmpty())
        {
            throw new ServiceException("订单不存在");
        }
        Map<String, Object> result = orderViews(rows).get(0);
        if (wechatPay.virtualEnabled())
        {
            List<Map<String,Object>> channel=jdbc.queryForList("select v.order_type from club_virtual_payment v join club_payment p on p.payment_no=v.payment_no where p.order_id=? and p.user_id=? and p.status in ('success','refunded') order by p.id desc limit 1",orderId,userId);
            if(!channel.isEmpty())result.put("paymentChannel",Integer.valueOf(7).equals(channel.get(0).get("order_type"))?"apple_iap":"virtual_wechat");
        }
        result.put("review", jdbc.queryForList("select rating,content,created_at as createdAt from club_review where order_id=? and user_id=?", orderId, userId).stream().findFirst().orElse(null));
        result.put("logs", jdbc.queryForList(
                "select id,from_status as fromStatus,to_status as toStatus,operator_type as operatorType,note,created_at as createdAt " +
                "from club_order_log where order_id=? order by id", orderId));
        return result;
    }

    @Transactional
    public Map<String, Object> balancePay(Long userId, Long orderId, Map<String, Object> input)
    {
        lockPaymentUser(userId);
        String method = text(input.get("method")).trim().toLowerCase();
        if (method.isEmpty()) method = "wechat";
        if (!Arrays.asList("wechat", "balance").contains(method)) throw new ServiceException("不支持的支付方式");
        if (!simulation() && (!"wechat".equalsIgnoreCase(paymentMode) || !"balance".equals(method)))
        {
            throw new ServiceException("请使用真实微信支付或钱包余额支付");
        }
        String channel=simulation()&&"wechat".equals(method)?"mock_wechat":"balance";
        String notice="mock_wechat".equals(channel)?"模拟微信支付（不扣真实资金）":"钱包余额支付";
        String outcome = "mock_wechat".equals(channel)?text(input.get("outcome")):"success";
        if(outcome.isEmpty())outcome="success";
        if(!Arrays.asList("success","failed","cancelled").contains(outcome))throw new ServiceException("模拟结果不正确");
        String idempotencyKey = required(input, "idempotencyKey", "缺少幂等键");
        if (idempotencyKey.length() > 80)
        {
            throw new ServiceException("幂等键过长");
        }
        List<Map<String, Object>> previous = jdbc.queryForList(
                "select p.id,p.payment_no as paymentNo,p.status,p.mock_transaction_no as transactionNo,p.paid_at as paidAt from club_payment p " +
                "where p.user_id=? and p.order_id=? and p.mode=? and p.idempotency_key=?", userId, orderId, channel, channel+":"+sha256(idempotencyKey));
        if (!previous.isEmpty())
        {
            Map<String, Object> result = new HashMap<>();
            result.put("payment", previous.get(0));
            result.put("order", order(userId, orderId));
            result.put("idempotent", true);
            result.put("notice", notice);
            return result;
        }
        List<Map<String, Object>> orderRows = jdbc.queryForList(
                "select o.*,case when o.payment_expires_at is not null and o.payment_expires_at<=now() then 1 else 0 end as payment_expired " +
                "from club_order o where o.id=? and o.user_id=? for update", orderId, userId);
        if (orderRows.isEmpty())
        {
            throw new ServiceException("订单不存在");
        }
        Map<String, Object> current = orderRows.get(0);
        String currentStatus = text(current.get("status"));
        if ("unpaid".equals(currentStatus) && number(current.get("payment_expired")) == 1L)
        {
            expireUnpaidOrder(userId, orderId);
            Map<String, Object> result = new HashMap<>();
            Map<String,Object> snapshot=order(userId,orderId);
            result.put("order", snapshot);
            result.put("expired", "cancelled".equals(text(snapshot.get("status"))));
            result.put("idempotent", false);
            result.put("notice", "支付等待已超过30分钟，订单已关闭");
            return result;
        }
        if (!"unpaid".equals(currentStatus))
        {
            if (Arrays.asList("pending", "accepted", "serving", "completed").contains(currentStatus))
            {
                Map<String, Object> result = new HashMap<>();
                result.put("order", order(userId, orderId));
                result.put("idempotent", true);
                result.put("notice", "该订单已完成支付，不会重复处理");
                return result;
            }
            throw new ServiceException("当前订单状态不可支付");
        }
        // Resolve the old external trade before charging another channel.
        if (!simulation() && !closePendingWechatPayment(orderId)) {
            Map<String,Object> paid=new HashMap<>();paid.put("order",order(userId,orderId));paid.put("alreadyPaid",true);return paid;
        }
        teenPolicy.assertPaymentCapacity(userId, orderId, decimal(current.get("total_amount")), method);
        String paymentNo = "BP" + System.currentTimeMillis() + randomDigits();
        Long paymentId = insertAndKey("insert into club_payment(payment_no,order_id,user_id,amount,mode,status,idempotency_key) values(?,?,?,?,?,?,?)",
                paymentNo, orderId, userId, current.get("total_amount"), channel, "created", channel+":"+sha256(idempotencyKey));
        String transactionNo = null;
        if ("success".equals(outcome))
        {
            teenPolicy.reserveSuccessfulSpend(userId, decimal(current.get("total_amount")));
            if ("balance".equals(method))
            {
                jdbc.update("insert ignore into club_wallet(user_id,balance,frozen,total_income,total_withdrawn) values(?,0,0,0,0)", userId);
                Map<String, Object> wallet = jdbc.queryForMap("select balance,frozen from club_wallet where user_id=? for update", userId);
                BigDecimal before = decimal(wallet.get("balance"));
                BigDecimal amount = decimal(current.get("total_amount"));
                if (before.compareTo(amount) < 0) throw new ServiceException("钱包余额不足，请充值后重试或选择微信支付");
                BigDecimal after = before.subtract(amount);
                jdbc.update("update club_wallet set balance=?,version=version+1 where user_id=?", after, userId);
                jdbc.update("insert into club_wallet_record(user_id,record_type,amount,balance_before,balance_after,frozen_after,reference_no,order_id,counterparty_type,description) values(?,?,?,?,?,?,?,?,?,?)",
                        userId, "order_payment", amount.negate(), before, after, wallet.get("frozen"), current.get("order_no"), orderId, "order", "订单余额支付");
            }
            int stockRows = jdbc.update("update club_product_sku set stock=stock-? where id=? and stock>=?",
                    current.get("quantity"), current.get("sku_id"), current.get("quantity"));
            if (stockRows != 1)
            {
                throw new ServiceException("库存不足，支付未完成");
            }
            catalog.syncProductSummary(number(current.get("product_id")));
            transactionNo = ("mock_wechat".equals(channel)?"SIMULATED-":"BALANCE-") + UUID.randomUUID().toString().replace("-", "").toUpperCase();
            jdbc.update("update club_payment set status='success',mock_transaction_no=?,paid_at=now() where id=?", transactionNo, paymentId);
            jdbc.update("update club_order set status='pending',payment_method=?,paid_at=now(),version=version+1 where id=? and status='unpaid'", channel, orderId);
            business.consumeReservedCoupon(orderId);
            jdbc.update("update club_product set sales=sales+? where id=?", current.get("quantity"), current.get("product_id"));
            logOrder(orderId, "unpaid", "pending", "user", userId, notice+"成功");
            message(userId, "order", "订单支付成功", notice+"已完成。" + "订单号：" + current.get("order_no"), "order", orderId);
            if (current.get("provider_user_id") != null)
            {
                message(number(current.get("provider_user_id")), "order", "您有新的待接订单",
                        "订单号：" + current.get("order_no") + "，请进入工作台查看并处理。", "order", orderId);
            }
        }
        else
        {
            jdbc.update("update club_payment set status=? where id=?", outcome, paymentId);
        }
        jdbc.update("insert into club_payment_audit(payment_id,order_id,user_id,action,result_status,request_id,detail) values(?,?,?,?,?,?,?)",
                paymentId, orderId, userId, "mock_wechat".equals(channel)?"simulation_pay":"balance_pay", outcome, idempotencyKey,
                notice);
        Map<String, Object> payment = new HashMap<>();
        payment.put("id", paymentId);
        payment.put("paymentNo", paymentNo);
        payment.put("status", outcome);
        payment.put("method", channel);
        payment.put("transactionNo", transactionNo);
        Map<String, Object> result = new HashMap<>();
        result.put("payment", payment);
        result.put("order", order(userId, orderId));
        result.put("idempotent", false);
        result.put("notice", notice);
        return result;
    }

    @Transactional
    public Map<String, Object> wechatPrepay(Long userId, Long orderId, Map<String, Object> input)
    {
        if (simulation()) throw new ServiceException("联调环境不连接微信支付");
        lockPaymentUser(userId);
        if (!wechatPay.enabled()) throw new ServiceException("真实微信支付尚未开启");
        String idempotencyKey = required(input, "idempotencyKey", "缺少幂等键");
        if (idempotencyKey.length() > 80) throw new ServiceException("幂等键过长");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select o.*,p.name product_name from club_order o join club_product p on p.id=o.product_id where o.id=? and o.user_id=? for update", orderId, userId);
        if (rows.isEmpty()) throw new ServiceException("订单不存在");
        Map<String, Object> order = rows.get(0);
        if (!"unpaid".equals(text(order.get("status")))) {
            if(Arrays.asList("pending","accepted","serving","completed","refunding","refunded").contains(text(order.get("status"))))return paymentSnapshot(userId,orderId);
            throw new ServiceException("当前订单状态不可支付");
        }
        Instant expiry = paymentExpiryInstant(order.get("payment_expires_at"));
        if (expiry == null || !expiry.isAfter(Instant.now()))
        {
            expireUnpaidOrder(userId, orderId); return paymentSnapshot(userId,orderId);
        }
        teenPolicy.assertPaymentCapacity(userId, orderId, decimal(order.get("total_amount")), "wechat");
        List<String> identities = jdbc.query("select openid from club_wechat_identity where user_id=?", (rs, n) -> rs.getString(1), userId);
        if (identities.isEmpty() || identities.get(0) == null || identities.get(0).trim().isEmpty()) throw new ServiceException("请先使用当前微信登录或绑定微信后再支付");
        List<Map<String, Object>> payments = jdbc.queryForList(
                "select id,payment_no,stock_reserved,prepay_id from club_payment where user_id=? and order_id=? and mode='wechat' and status='created' order by id desc limit 1 for update", userId, orderId);
        Long paymentId; String paymentNo;
        if (payments.isEmpty())
        {
            paymentNo = (wechatPay.virtualEnabled() ? "VP" : "WX") + System.currentTimeMillis() + randomDigits();
            paymentId = insertAndKey("insert into club_payment(payment_no,order_id,user_id,amount,mode,status,idempotency_key) values(?,?,?,?,?,'created',?)",
                    paymentNo, orderId, userId, order.get("total_amount"), "wechat", "wechat:"+paymentNo);
            payments = jdbc.queryForList("select id,payment_no,stock_reserved,prepay_id from club_payment where id=? for update", paymentId);
        }
        else { paymentId = number(payments.get(0).get("id")); paymentNo = text(payments.get(0).get("payment_no")); }
        if (number(payments.get(0).get("stock_reserved")) != 1L)
        {
            int stockRows = jdbc.update("update club_product_sku set stock=stock-? where id=? and stock>=?",
                    order.get("quantity"), order.get("sku_id"), order.get("quantity"));
            if (stockRows != 1) throw new ServiceException("库存不足，无法发起微信支付");
            jdbc.update("update club_payment set stock_reserved=1 where id=? and status='created'", paymentId);
            catalog.syncProductSummary(number(order.get("product_id")));
        }
        if (ClubVirtualPayGateway.owns(paymentNo))
        {
            Map<String,Object> params = wechatPay.virtualPrepay(paymentNo, order, identities.get(0), text(input.get("wechatCode")));
            jdbc.update("update club_payment set prepay_id='virtual' where id=? and status='created'", paymentId);
            jdbc.update("insert into club_payment_audit(payment_id,order_id,user_id,action,result_status,request_id,detail) values(?,?,?,?,?,?,?)",
                    paymentId, orderId, userId, "virtual_prepay", "created", idempotencyKey, "虚拟支付签名生成，尚未确认付款");
            Map<String,Object> result = new HashMap<>(); result.put("requestVirtualPayment",params); result.put("paymentNo",paymentNo); result.put("expired",false); return result;
        }
        Map<String, Object> requestPayment = wechatPay.prepay(paymentNo, text(order.get("product_name")), decimal(order.get("total_amount")),
                identities.get(0), expiry.atOffset(ZoneOffset.UTC));
        String packageValue = text(requestPayment.get("package"));
        jdbc.update("update club_payment set prepay_id=? where id=? and status='created'", packageValue.startsWith("prepay_id=") ? packageValue.substring(10) : packageValue, paymentId);
        jdbc.update("insert into club_payment_audit(payment_id,order_id,user_id,action,result_status,request_id,detail) values(?,?,?,?,?,?,?)",
                paymentId, orderId, userId, "wechat_prepay", "created", idempotencyKey, "微信支付APIv3预下单");
        Map<String, Object> result = new HashMap<>(); result.put("requestPayment", requestPayment); result.put("paymentNo", paymentNo); result.put("expired", false); return result;
    }

    @Transactional
    public Map<String, Object> syncWechatPayment(Long userId, Long orderId)
    {
        if (simulation()) throw new ServiceException("联调环境不连接微信支付");
        lockPaymentUser(userId);
        List<Map<String, Object>> payments = jdbc.queryForList(
                "select payment_no from club_payment where user_id=? and order_id=? and mode='wechat' order by id desc limit 1", userId, orderId);
        if (payments.isEmpty()) throw new ServiceException("未找到微信支付记录");
        Transaction tx = wechatPay.query(text(payments.get(0).get("payment_no")));
        if (Transaction.TradeStateEnum.SUCCESS.equals(tx.getTradeState())) completeWechatPayment(tx);
        Map<String, Object> result = new HashMap<>(); result.put("order", order(userId, orderId)); result.put("tradeState", String.valueOf(tx.getTradeState())); return result;
    }

    @Transactional
    public void completeWechatPayment(Transaction tx)
    {
        if (simulation()) throw new ServiceException("联调环境不连接微信支付");
        wechatPay.validateIdentity(tx);
        if (!Transaction.TradeStateEnum.SUCCESS.equals(tx.getTradeState())) return;
        if (text(tx.getOutTradeNo()).startsWith("RC") && completeWechatRecharge(tx)) return;
        List<Map<String,Object>> owner=jdbc.queryForList("select user_id,order_id from club_payment where payment_no=? and mode='wechat'",tx.getOutTradeNo());
        if(owner.isEmpty())throw new ServiceException("微信支付订单不存在");
        lockPaymentUser(number(owner.get(0).get("user_id")));
        jdbc.queryForList("select id from club_order where id=? for update",owner.get(0).get("order_id"));
        List<Map<String, Object>> payments = jdbc.queryForList("select * from club_payment where payment_no=? and mode='wechat' for update", tx.getOutTradeNo());
        if (payments.isEmpty()) throw new ServiceException("微信支付订单不存在");
        Map<String, Object> payment = payments.get(0); Long paymentId = number(payment.get("id")); Long orderId = number(payment.get("order_id")); Long userId = number(payment.get("user_id"));
        if ("success".equals(text(payment.get("status"))))
        {
            if (!tx.getTransactionId().equals(text(payment.get("mock_transaction_no")))) throw new ServiceException("微信支付流水号冲突");
            return;
        }
        int expected = ClubWechatPayService.cents(decimal(payment.get("amount")));
        if (tx.getAmount() == null || tx.getAmount().getTotal() == null || tx.getAmount().getTotal() != expected) throw new ServiceException("微信支付回调金额不匹配");
        List<String> openids = jdbc.query("select openid from club_wechat_identity where user_id=?", (rs, n) -> rs.getString(1), userId);
        if (tx.getPayer() == null || openids.isEmpty() || !text(tx.getPayer().getOpenid()).equals(openids.get(0))) throw new ServiceException("微信支付付款人不匹配");
        Map<String, Object> current = jdbc.queryForMap("select * from club_order where id=? for update", orderId);
        if (!"unpaid".equals(text(current.get("status")))) throw new ServiceException("微信支付对应订单状态异常");
        if (number(payment.get("stock_reserved")) != 1L) throw new ServiceException("微信支付订单库存未预占，需要人工处理");
        boolean limitReview=teenPolicy.recordConfirmedWechatSpend(userId, decimal(current.get("total_amount")));
        jdbc.update("update club_payment set status='success',mock_transaction_no=?,paid_at=now() where id=? and status='created'", tx.getTransactionId(), paymentId);
        jdbc.update("update club_order set status='pending',payment_method='wechat',paid_at=now(),version=version+1 where id=? and status='unpaid'", orderId);
        business.consumeReservedCoupon(orderId); jdbc.update("update club_product set sales=sales+? where id=?", current.get("quantity"), current.get("product_id"));
        logOrder(orderId, "unpaid", "pending", "wechat", null, "微信支付成功");
        message(userId, "order", "订单支付成功", "微信支付已完成。订单号：" + current.get("order_no"), "order", orderId);
        if (current.get("provider_user_id") != null) message(number(current.get("provider_user_id")), "order", "您有新的待接订单", "订单号：" + current.get("order_no") + "，请进入工作台查看并处理。", "order", orderId);
        jdbc.update("insert into club_payment_audit(payment_id,order_id,user_id,action,result_status,request_id,detail) values(?,?,?,?,?,?,?)",
                paymentId, orderId, userId, "wechat_notify", "success", tx.getTransactionId(), "微信支付回调验签、身份及金额校验通过");
        if(limitReview)afterSale.reviewPaidLimit(orderId);
    }

    private boolean completeWechatRecharge(Transaction tx)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from club_recharge_order where recharge_no=? and payment_mode='wechat' for update", tx.getOutTradeNo());
        if (rows.isEmpty()) return false;
        Map<String, Object> current = rows.get(0);
        Long id = number(current.get("id"));
        Long userId = number(current.get("user_id"));
        if ("success".equals(text(current.get("status"))))
        {
            if (!tx.getTransactionId().equals(text(current.get("mock_transaction_no")))) throw new ServiceException("微信充值流水号冲突");
            return true;
        }
        if (!"created".equals(text(current.get("status")))) throw new ServiceException("微信充值订单状态异常");
        int expected = ClubWechatPayService.cents(decimal(current.get("amount")));
        if (tx.getAmount() == null || tx.getAmount().getTotal() == null || tx.getAmount().getTotal() != expected)
            throw new ServiceException("微信充值回调金额不匹配");
        if (tx.getAmount().getCurrency() == null || !"CNY".equals(tx.getAmount().getCurrency())) throw new ServiceException("微信充值币种不匹配");
        if (text(tx.getTransactionId()).isEmpty()) throw new ServiceException("微信充值缺少交易流水号");
        if (tx.getPayer() == null || text(current.get("payment_openid")).isEmpty()
                || !text(tx.getPayer().getOpenid()).equals(text(current.get("payment_openid"))))
            throw new ServiceException("微信充值付款人不匹配");
        jdbc.update("insert ignore into club_wallet(user_id,balance,frozen) values(?,0,0)", userId);
        Map<String, Object> wallet = jdbc.queryForMap("select balance,frozen from club_wallet where user_id=? for update", userId);
        BigDecimal before = decimal(wallet.get("balance"));
        BigDecimal credited = decimal(current.get("credited_amount"));
        BigDecimal after = before.add(credited);
        int updated = jdbc.update("update club_recharge_order set status='success',callback_key=?,mock_transaction_no=?,paid_at=now() where id=? and status='created'",
                tx.getTransactionId(), tx.getTransactionId(), id);
        if (updated != 1) throw new ServiceException("微信充值订单入账状态冲突");
        jdbc.update("update club_wallet set balance=?,version=version+1 where user_id=?", after, userId);
        jdbc.update("insert into club_wallet_record(user_id,record_type,amount,balance_before,balance_after,frozen_after,reference_no,counterparty_type,description) values(?,?,?,?,?,?,?,?,?)",
                userId, "recharge", credited, before, after, wallet.get("frozen"), current.get("recharge_no"), "wechat", "微信充值入账");
        new ClubCouponGrantService(jdbc).issueReward(id);
        message(userId, "system", "微信充值成功", "充值金额已加入钱包余额。充值单号：" + current.get("recharge_no"), "recharge", id);
        return true;
    }

    @Transactional
    public int expireUnpaidOrders()
    {
        List<Long> ids = jdbc.queryForList(
                "select id from club_order where status='unpaid' and payment_expires_at is not null and payment_expires_at<=now() order by id limit 200",
                Long.class);
        for (Long id : ids) expireUnpaidOrder(null, id);
        return ids.size();
    }

    private void expireUnpaidOrdersForUser(Long userId)
    {
        List<Long> ids = jdbc.queryForList(
                "select id from club_order where user_id=? and status='unpaid' and payment_expires_at is not null and payment_expires_at<=now() for update",
                Long.class, userId);
        for (Long id : ids) expireUnpaidOrder(userId, id);
    }

    private void expireUnpaidOrder(Long userId, Long orderId)
    {
        if(userId==null) {
            List<Map<String,Object>> owner=jdbc.queryForList("select user_id from club_order where id=?",orderId);
            if(owner.isEmpty())return;
            lockPaymentUser(number(owner.get(0).get("user_id")));
        }
        // Ownership and expiry MUST be checked before any external close request.
        List<Map<String,Object>> eligible = userId == null
                ? jdbc.queryForList("select status,case when payment_expires_at<=now() then 1 else 0 end as expired from club_order where id=? for update",orderId)
                : jdbc.queryForList("select status,case when payment_expires_at<=now() then 1 else 0 end as expired from club_order where id=? and user_id=? for update",orderId,userId);
        if(eligible.isEmpty() || !"unpaid".equals(text(eligible.get(0).get("status"))) || number(eligible.get(0).get("expired"))!=1L)return;
        if (!closePendingWechatPayment(orderId)) return;
        int changed = userId == null
                ? jdbc.update("update club_order set status='cancelled',cancel_reason='payment_timeout',cancelled_at=now(),version=version+1 where id=? and status='unpaid' and payment_expires_at<=now()", orderId)
                : jdbc.update("update club_order set status='cancelled',cancel_reason='payment_timeout',cancelled_at=now(),version=version+1 where id=? and user_id=? and status='unpaid' and payment_expires_at<=now()", orderId, userId);
        if (changed == 1)
        {
            business.releaseCoupon(orderId);
            logOrder(orderId, "unpaid", "cancelled", "system", null, "支付等待超过30分钟，订单自动关闭");
        }
    }

    @Transactional
    public Map<String, Object> cancelOrder(Long userId, Long orderId)
    {
        return cancelOrderAs(userId,orderId,"user",userId,"用户取消订单");
    }

    @Transactional
    public Map<String,Object> adminCancelOrder(Long adminId,Long orderId,String note) {
        List<Map<String,Object>> owner=jdbc.queryForList("select user_id from club_order where id=?",orderId);
        if(owner.isEmpty())throw new ServiceException("订单不存在");
        return cancelOrderAs(number(owner.get(0).get("user_id")),orderId,"admin",adminId,"管理员取消未付款订单："+note);
    }

    private Map<String,Object> cancelOrderAs(Long userId,Long orderId,String actor,Long actorId,String note) {
        lockPaymentUser(userId);
        List<Map<String, Object>> rows = jdbc.queryForList("select status from club_order where id=? and user_id=? for update", orderId, userId);
        if (rows.isEmpty())
        {
            throw new ServiceException("订单不存在");
        }
        String status = text(rows.get(0).get("status"));
        if ("cancelled".equals(status)) return order(userId, orderId);
        if (!"unpaid".equals(status))
        {
            throw new ServiceException("只有待付款订单可以直接取消");
        }
        // A successful external payment discovered during cancellation must commit.
        if (!closePendingWechatPayment(orderId)) return order(userId, orderId);
        jdbc.update("update club_order set status='cancelled',cancel_reason=?,cancelled_at=now(),version=version+1 where id=?", "admin".equals(actor)?"admin_cancelled":"user_cancelled",orderId);
        business.releaseCoupon(orderId);
        logOrder(orderId, status, "cancelled", actor, actorId, note);
        return order(userId, orderId);
    }

    /** Close any outstanding WeChat trade before releasing its reserved inventory. */
    private boolean closePendingWechatPayment(Long orderId)
    {
        List<Map<String, Object>> payments = jdbc.queryForList(
                "select p.id,p.payment_no,p.prepay_id,p.stock_reserved,o.product_id,o.sku_id,o.quantity " +
                "from club_payment p join club_order o on o.id=p.order_id " +
                "where p.order_id=? and p.mode='wechat' and p.status='created' order by p.id desc limit 1 for update", orderId);
        if (payments.isEmpty()) return true;
        if (simulation()) throw new ServiceException("联调环境发现非模拟支付记录，禁止处理");
        Map<String, Object> payment = payments.get(0);
        if (number(payment.get("stock_reserved")) != 1L) return true;
        String paymentNo = text(payment.get("payment_no"));
        if (!text(payment.get("prepay_id")).isEmpty())
        {
            try { wechatPay.close(paymentNo); }
            catch (RuntimeException closeFailure)
            {
                Transaction tx = wechatPay.query(paymentNo);
                if (Transaction.TradeStateEnum.SUCCESS.equals(tx.getTradeState()))
                {
                    completeWechatPayment(tx);
                    return false;
                }
                if (!Transaction.TradeStateEnum.CLOSED.equals(tx.getTradeState())) {
                    if (ClubVirtualPayGateway.owns(paymentNo)) return false;
                    throw closeFailure;
                }
            }
        }
        int closed = jdbc.update("update club_payment set status='closed',stock_reserved=0 where id=? and status='created' and stock_reserved=1", payment.get("id"));
        if (closed == 1)
        {
            jdbc.update("update club_product_sku set stock=stock+? where id=?", payment.get("quantity"), payment.get("sku_id"));
            catalog.syncProductSummary(number(payment.get("product_id")));
            jdbc.update("insert into club_payment_audit(payment_id,order_id,user_id,action,result_status,request_id,detail) " +
                            "select id,order_id,user_id,'wechat_close','closed',payment_no,'微信支付关单并释放预占库存' from club_payment where id=?",
                    payment.get("id"));
        }
        return true;
    }

    private void lockPaymentUser(Long userId) {
        if(jdbc.queryForList("select id from club_user where id=? for update",userId).isEmpty())throw new ServiceException("账号不存在");
    }

    private Map<String,Object> paymentSnapshot(Long userId,Long orderId) {
        Map<String,Object> snapshot=order(userId,orderId),result=new HashMap<>();
        result.put("order",snapshot);result.put("expired","cancelled".equals(text(snapshot.get("status"))));
        return result;
    }

    public Map<String, Object> confirmOrder(Long userId, Long orderId, Map<String, Object> input)
    {
        return business.confirmOrder(userId, orderId, input);
    }

    @Transactional
    public Map<String, Object> review(Long userId, Long orderId, Map<String, Object> input)
    {
        int rating = integer(input.get("rating"), 0);
        String content = text(input.get("content")).trim();
        if (content.isEmpty() || content.length() > 500) throw new ServiceException("请填写1至500字的评价内容");
        lockPaymentUser(userId);
        if (rating < 1 || rating > 5)
        {
            throw new ServiceException("评分必须为1至5分");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("select product_id,status,completed_at from club_order where id=? and user_id=? for update", orderId, userId);
        if (rows.isEmpty() || !("completed".equals(text(rows.get(0).get("status"))) || (Arrays.asList("refunding","refunded").contains(text(rows.get(0).get("status"))) && rows.get(0).get("completed_at") != null)))
        {
            throw new ServiceException("当前订单不可评价");
        }
        if (!jdbc.queryForList("select id from club_review where order_id=?", orderId).isEmpty()) return order(userId, orderId);
        jdbc.update("insert into club_review(order_id,product_id,user_id,rating,content) values(?,?,?,?,?)", orderId, rows.get(0).get("product_id"), userId, rating, content);
        logOrder(orderId, text(rows.get(0).get("status")), text(rows.get(0).get("status")), "user", userId, "用户完成评价");
        return order(userId, orderId);
    }

    public List<Map<String, Object>> messages(Long userId)
    {
        return jdbc.queryForList("select id,message_type as type,title,content,reference_type as referenceType,reference_id as referenceId,is_read as isRead,created_at as createdAt from club_message where user_id=? order by id desc", userId);
    }

    public void readMessage(Long userId, Long messageId)
    {
        if (jdbc.update("update club_message set is_read=1 where id=? and user_id=?", messageId, userId) != 1)
        {
            throw new ServiceException("消息不存在");
        }
    }

    public Map<String, Object> unreadMessageCount(Long userId)
    {
        Integer count = jdbc.queryForObject("select count(1) from club_message where user_id=? and is_read=0", Integer.class, userId);
        Map<String, Object> result = new HashMap<>();
        result.put("count", count == null ? 0 : count);
        return result;
    }

    public Map<String, Object> readAllMessages(Long userId)
    {
        int changed = jdbc.update("update club_message set is_read=1 where user_id=? and is_read=0", userId);
        Map<String, Object> result = new HashMap<>();
        result.put("changed", changed);
        result.put("count", 0);
        return result;
    }

    public Map<String, Object> wallet(Long userId)
    {
        List<Map<String, Object>> wallets = jdbc.queryForList("select balance,frozen,total_income as totalIncome,total_withdrawn as totalWithdrawn,updated_at as updatedAt from club_wallet where user_id=?", userId);
        if (wallets.isEmpty())
        {
            jdbc.update("insert into club_wallet(user_id,balance,frozen) values(?,0,0)", userId);
            wallets = jdbc.queryForList("select balance,frozen,total_income as totalIncome,total_withdrawn as totalWithdrawn,updated_at as updatedAt from club_wallet where user_id=?", userId);
        }
        Map<String, Object> result = new HashMap<>(wallets.get(0));
        result.put("withdrawable",ClubWalletPolicy.withdrawable(result));
        result.put("records", jdbc.queryForList("select id,record_type as type,amount,balance_before as balanceBefore,balance_after as balanceAfter,frozen_after as frozenAfter,reference_no as referenceNo,order_id as orderId,counterparty_type as counterpartyType,description,created_at as createdAt from club_wallet_record where user_id=? order by id desc limit 100", userId));
        return result;
    }

    public Map<String, Object> teenMode(Long userId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select enabled,case when locked_until>now() then timestampdiff(second,now(),locked_until) else 0 end as lockedSeconds,updated_at as updatedAt " +
                "from club_teen_setting where user_id=?", userId);
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", !rows.isEmpty() && number(rows.get(0).get("enabled")) == 1L);
        result.put("lockedSeconds", rows.isEmpty() ? 0 : Math.max(0, number(rows.get(0).get("lockedSeconds"))));
        if (!rows.isEmpty()) result.put("updatedAt", rows.get(0).get("updatedAt"));
        return result;
    }

    @Transactional
    public Map<String, Object> enableTeenMode(Long userId, Map<String, Object> input)
    {
        String pin = required(input, "password", "请输入4位监护密码");
        if (!pin.matches("^\\d{4}$")) throw new ServiceException("监护密码必须为4位数字");
        String hash = sha256(identitySalt + ":teen:" + userId + ":" + pin);
        List<Map<String, Object>> rows = jdbc.queryForList("select pin_hash,enabled from club_teen_setting where user_id=? for update", userId);
        if (!rows.isEmpty() && number(rows.get(0).get("enabled")) == 1L)
        {
            if (hash.equals(text(rows.get(0).get("pin_hash")))) return teenMode(userId);
            throw new ServiceException("青少年模式已经开启");
        }
        jdbc.update("insert into club_teen_setting(user_id,pin_hash,enabled,failed_attempts,locked_until) values(?,?,1,0,null) " +
                "on duplicate key update pin_hash=values(pin_hash),enabled=1,failed_attempts=0,locked_until=null", userId, hash);
        return teenMode(userId);
    }

    @Transactional(noRollbackFor = ServiceException.class)
    public Map<String, Object> disableTeenMode(Long userId, Map<String, Object> input)
    {
        String pin = required(input, "password", "请输入4位监护密码");
        if (!pin.matches("^\\d{4}$")) throw new ServiceException("监护密码必须为4位数字");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select pin_hash,enabled,failed_attempts,case when locked_until>now() then timestampdiff(second,now(),locked_until) else 0 end locked_seconds " +
                "from club_teen_setting where user_id=? for update", userId);
        if (rows.isEmpty() || number(rows.get(0).get("enabled")) != 1L) throw new ServiceException("青少年模式尚未开启");
        long lockedSeconds = Math.max(0, number(rows.get(0).get("locked_seconds")));
        if (lockedSeconds > 0) throw new ServiceException("密码错误次数过多，请" + lockedSeconds + "秒后再试");
        String hash = sha256(identitySalt + ":teen:" + userId + ":" + pin);
        if (!hash.equals(text(rows.get(0).get("pin_hash"))))
        {
            int attempts = integer(rows.get(0).get("failed_attempts"), 0) + 1;
            if (attempts >= 5)
            {
                jdbc.update("update club_teen_setting set failed_attempts=?,locked_until=date_add(now(),interval 10 minute) where user_id=?", attempts, userId);
                throw new ServiceException("监护密码错误次数过多，请10分钟后再试");
            }
            jdbc.update("update club_teen_setting set failed_attempts=? where user_id=?", attempts, userId);
            throw new ServiceException("监护密码不正确，还可尝试" + (5 - attempts) + "次");
        }
        jdbc.update("update club_teen_setting set enabled=0,failed_attempts=0,locked_until=null where user_id=?", userId);
        return teenMode(userId);
    }

    public Map<String, Object> rechargeConfig(Long userId)
    {
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", (simulation()||wechatPay.enabled()) && "1".equals(configValue("recharge_enabled", "1")));
        result.put("tiers", jdbc.queryForList("select id,name,amount,bonus_amount as bonusAmount from club_recharge_tier where status='active' order by sort_no,id"));
        result.put("wallet", wallet(userId));
        result.put("giftRules",jdbc.queryForList("select r.name,r.min_amount as minAmount,r.quantity,c.name as couponName,c.amount as couponAmount,c.min_spend as minSpend,c.valid_until as validUntil from club_recharge_coupon_rule r join club_coupon c on c.id=r.coupon_id where r.status='active' and c.status='active' order by r.min_amount desc,r.id desc"));
        result.put("notice",simulation()?"模拟充值：仅增加联调余额，不扣真实资金":"充值将调用微信支付，支付成功后余额自动到账");
        return result;
    }

    @Transactional
    public Map<String, Object> createRecharge(Long userId, Map<String, Object> input)
    {
        teenPolicy.assertCanRecharge(userId);
        if (!simulation()&&!wechatPay.enabled()) throw new ServiceException("微信充值尚未开启");
        if (!"1".equals(configValue("recharge_enabled", "1"))) throw new ServiceException("充值中心暂未开放");
        String idempotencyKey = required(input, "idempotencyKey", "缺少幂等键");
        if (idempotencyKey.length() > 96) throw new ServiceException("幂等键过长");
        List<Map<String, Object>> previous = jdbc.queryForList("select id from club_recharge_order where user_id=? and idempotency_key=?", userId, idempotencyKey);
        if (!previous.isEmpty())
        {
            Map<String, Object> result = recharge(userId, number(previous.get(0).get("id")));
            result.put("idempotent", true);
            return result;
        }
        Long tierId = nestedOptionalId(input, "tierId", "tier");
        BigDecimal amount;
        BigDecimal bonus = BigDecimal.ZERO;
        if (tierId != null)
        {
            List<Map<String, Object>> tiers = jdbc.queryForList("select amount,bonus_amount from club_recharge_tier where id=? and status='active'", tierId);
            if (tiers.isEmpty()) throw new ServiceException("充值档位不存在或已停用");
            amount = decimal(tiers.get(0).get("amount"));
            bonus = decimal(tiers.get(0).get("bonus_amount"));
        }
        else amount = decimal(input.get("amount"));
        if (amount.compareTo(BigDecimal.ONE) < 0 || amount.compareTo(new BigDecimal("50000")) > 0)
            throw new ServiceException("充值金额必须在1元至50000元之间");
        ClubWechatPayService.cents(amount);
        ClubWechatPayService.cents(bonus);
        if (bonus.signum() < 0) throw new ServiceException("充值赠送金额无效");
        String rechargeNo = "RC" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
        Long id = insertAndKey("insert into club_recharge_order(recharge_no,user_id,tier_id,amount,bonus_amount,credited_amount,status,idempotency_key,payment_mode) values(?,?,?,?,?,?, 'created',?,?)",
                rechargeNo, userId, tierId, amount, bonus, amount.add(bonus), idempotencyKey,simulation()?"mock_wechat":"wechat");
        new ClubCouponGrantService(jdbc).snapshotReward(id,userId,amount);
        Map<String, Object> result = recharge(userId, id);
        result.put("idempotent", false);
        return result;
    }

    @Transactional
    public Map<String, Object> wechatRechargePrepay(Long userId, Long id, Map<String, Object> input)
    {
        if (simulation()) throw new ServiceException("联调环境不连接微信支付");
        teenPolicy.assertCanRecharge(userId);
        if (!wechatPay.enabled()) throw new ServiceException("微信充值尚未开启");
        String idempotencyKey = required(input, "idempotencyKey", "缺少幂等键");
        if (idempotencyKey.length() > 96) throw new ServiceException("幂等键过长");
        List<Map<String, Object>> rows = jdbc.queryForList("select * from club_recharge_order where id=? and user_id=? for update", id, userId);
        if (rows.isEmpty()) throw new ServiceException("充值订单不存在");
        Map<String, Object> current = rows.get(0);
        if (!"wechat".equals(text(current.get("payment_mode")))) throw new ServiceException("历史充值单不可支付，请重新创建充值");
        if ("success".equals(text(current.get("status")))) return flag("paid", true);
        if (!"created".equals(text(current.get("status")))) throw new ServiceException("当前充值订单状态不可支付");
        Instant expiry = paymentExpiryInstant(current.get("created_at")).plusSeconds(1800);
        if (!expiry.isAfter(Instant.now())) return flag("expired", true);
        List<String> identities = jdbc.query("select openid from club_wechat_identity where user_id=?", (rs, n) -> rs.getString(1), userId);
        if (identities.isEmpty() || identities.get(0) == null || identities.get(0).trim().isEmpty())
            throw new ServiceException("请先使用当前微信登录后再充值");
        String openid = identities.get(0);
        if (!text(current.get("payment_openid")).isEmpty() && !openid.equals(text(current.get("payment_openid"))))
            throw new ServiceException("充值付款微信不一致，请重新登录原微信");
        jdbc.update("update club_recharge_order set payment_openid=? where id=? and status='created'", openid, id);
        Map<String, Object> requestPayment = wechatPay.prepay(text(current.get("recharge_no")), "钱包充值",
                decimal(current.get("amount")), openid, expiry.atOffset(ZoneOffset.UTC));
        Map<String, Object> result = new HashMap<>();
        result.put("requestPayment", requestPayment);
        result.put("rechargeNo", current.get("recharge_no"));
        result.put("expired", false);
        return result;
    }

    @Transactional
    public Map<String, Object> syncWechatRecharge(Long userId, Long id)
    {
        if (simulation()) throw new ServiceException("联调环境不连接微信支付");
        List<Map<String, Object>> rows = jdbc.queryForList("select recharge_no,status,payment_mode from club_recharge_order where id=? and user_id=?", id, userId);
        if (rows.isEmpty()) throw new ServiceException("充值订单不存在");
        Map<String, Object> current = rows.get(0);
        if ("success".equals(text(current.get("status"))))
        {
            Map<String, Object> result = new HashMap<>();
            result.put("order", recharge(userId, id));
            result.put("wallet", wallet(userId));
            result.put("tradeState", "SUCCESS");
            return result;
        }
        if (!"wechat".equals(text(current.get("payment_mode")))) throw new ServiceException("历史充值记录不可发起支付");
        Transaction tx = wechatPay.query(text(current.get("recharge_no")));
        wechatPay.validateIdentity(tx);
        if (!text(current.get("recharge_no")).equals(tx.getOutTradeNo())) throw new ServiceException("充值查单返回单号不匹配");
        if (Arrays.asList(Transaction.TradeStateEnum.CLOSED, Transaction.TradeStateEnum.REVOKED, Transaction.TradeStateEnum.PAYERROR).contains(tx.getTradeState()))
            jdbc.update("update club_recharge_order set status='cancelled' where id=? and user_id=? and status='created'", id, userId);
        if (Transaction.TradeStateEnum.SUCCESS.equals(tx.getTradeState())) completeWechatPayment(tx);
        Map<String, Object> result = new HashMap<>();
        result.put("order", recharge(userId, id));
        result.put("wallet", wallet(userId));
        result.put("tradeState", String.valueOf(tx.getTradeState()));
        return result;
    }

    @Transactional
    public Map<String,Object> simulateRecharge(Long userId,Long id,Map<String,Object> input) {
        if(!simulation())throw new ServiceException("模拟支付未开启");
        lockPaymentUser(userId);
        String key=required(input,"idempotencyKey","缺少幂等键");
        if(key.length()>80)throw new ServiceException("幂等键过长");
        List<Map<String,Object>> rows=jdbc.queryForList("select * from club_recharge_order where id=? and user_id=? for update",id,userId);
        if(rows.isEmpty())throw new ServiceException("充值订单不存在");
        Map<String,Object> current=rows.get(0);
        if(!"mock_wechat".equals(text(current.get("payment_mode"))))throw new ServiceException("只能模拟联调环境充值订单");
        if(!"success".equals(text(current.get("status")))) {
            if(!"created".equals(text(current.get("status"))))throw new ServiceException("该充值订单不能支付");
            if(!paymentExpiryInstant(current.get("created_at")).plusSeconds(1800).isAfter(Instant.now())) {
                jdbc.update("update club_recharge_order set status='cancelled' where id=? and status='created'",id);
                return flag("expired",true);
            }
            teenPolicy.assertCanRecharge(userId);
            jdbc.update("insert ignore into club_wallet(user_id,balance,frozen) values(?,0,0)",userId);
            Map<String,Object> wallet=jdbc.queryForMap("select balance,frozen from club_wallet where user_id=? for update",userId);
            BigDecimal before=decimal(wallet.get("balance")),credited=decimal(current.get("credited_amount")),after=before.add(credited);
            String transaction="SIM-RECHARGE-"+id;
            if(jdbc.update("update club_recharge_order set status='success',callback_key=?,mock_transaction_no=?,paid_at=now() where id=? and status='created'",transaction,transaction,id)!=1)
                throw new ServiceException("充值状态已变化，请刷新");
            jdbc.update("update club_wallet set balance=?,version=version+1 where user_id=?",after,userId);
            jdbc.update("insert into club_wallet_record(user_id,record_type,amount,balance_before,balance_after,frozen_after,reference_no,counterparty_type,description) values(?,?,?,?,?,?,?,?,?)",
                userId,"recharge",credited,before,after,wallet.get("frozen"),current.get("recharge_no"),"mock_wechat","模拟充值入账（无真实资金）");
            new ClubCouponGrantService(jdbc).issueReward(id);
            message(userId,"system","模拟充值成功","测试余额已入账，不涉及真实资金。充值单号："+current.get("recharge_no"),"recharge",id);
        }
        Map<String,Object> result=new HashMap<>();result.put("paid",true);result.put("order",recharge(userId,id));result.put("wallet",wallet(userId));return result;
    }

    public List<Map<String, Object>> recharges(Long userId)
    {
        return jdbc.queryForList("select id,recharge_no as rechargeNo,amount,bonus_amount as bonusAmount,credited_amount as creditedAmount,payment_mode as paymentMode,status,mock_transaction_no as transactionNo,paid_at as paidAt,created_at as createdAt " +
                "from club_recharge_order where user_id=? order by id desc limit 100", userId);
    }

    public Map<String, Object> recharge(Long userId, Long id)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("select id,recharge_no as rechargeNo,tier_id as tierId,amount,bonus_amount as bonusAmount,credited_amount as creditedAmount,payment_mode as paymentMode,status,mock_transaction_no as transactionNo,paid_at as paidAt,created_at as createdAt " +
                "from club_recharge_order where id=? and user_id=?", id, userId);
        if (rows.isEmpty()) throw new ServiceException("充值订单不存在");
        Map<String,Object> result=new HashMap<>(rows.get(0));
        result.put("rewards",jdbc.queryForList("select r.quantity,r.status,r.last_error as lastError,c.name as couponName from club_recharge_reward r join club_coupon c on c.id=r.coupon_id where r.recharge_id=? and r.user_id=?",id,userId));
        return result;
    }

    @Transactional
    public Map<String, Object> withdraw(Long userId, Map<String, Object> input)
    {
        teenPolicy.assertCanWithdraw(userId);
        Integer eligible=jdbc.queryForObject("select count(1) from club_user where id=? and user_type='player' and status='active'",Integer.class,userId);
        if(eligible==null||eligible!=1)throw new ServiceException("仅打手可申请收益提现");
        Integer outstanding = jdbc.queryForObject("select count(1) from club_provider_receivable where provider_user_id=? and status='outstanding' and recovered_amount<amount", Integer.class, userId);
        if (outstanding != null && outstanding > 0)
        {
            throw new ServiceException("您存在未结清的退款追偿，结清前不能提现");
        }
        BigDecimal amount = decimal(input.get("amount"));
        String idempotencyKey = required(input, "idempotencyKey", "缺少幂等键");
        if (idempotencyKey.length() > 96) throw new ServiceException("幂等键过长");
        if (amount.compareTo(BigDecimal.ZERO) <= 0)
        {
            throw new ServiceException("提现金额必须大于0");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("select balance,frozen,total_income as totalIncome,total_withdrawn as totalWithdrawn from club_wallet where user_id=? for update", userId);
        List<Map<String, Object>> previous = jdbc.queryForList("select id from club_withdrawal where user_id=? and idempotency_key=?", userId, idempotencyKey);
        if (!previous.isEmpty())
        {
            Map<String, Object> result = wallet(userId);
            result.put("idempotent", true);
            return result;
        }
        if (rows.isEmpty() || ClubWalletPolicy.withdrawable(rows.get(0)).compareTo(amount) < 0)
        {
            throw new ServiceException("可提现余额不足");
        }
        String no = "WD" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        try
        {
            jdbc.update("insert into club_withdrawal(withdrawal_no,user_id,amount,idempotency_key) values(?,?,?,?)", no, userId, amount, idempotencyKey);
        }
        catch (DataIntegrityViolationException duplicate)
        {
            Map<String, Object> result = wallet(userId);
            result.put("idempotent", true);
            return result;
        }
        int changed = jdbc.update("update club_wallet set balance=balance-?,frozen=frozen+?,version=version+1 where user_id=? and balance>=?", amount, amount, userId, amount);
        if (changed != 1)
        {
            throw new ServiceException("余额发生变化，请重新提交");
        }
        BigDecimal balanceBefore = decimal(rows.get(0).get("balance"));
        BigDecimal balance = balanceBefore.subtract(amount);
        BigDecimal frozen = decimal(rows.get(0).get("frozen")).add(amount);
        jdbc.update("insert into club_wallet_record(user_id,record_type,amount,balance_before,balance_after,frozen_after,reference_no,counterparty_type,description) values(?,?,?,?,?,?,?,?,?)",
                userId, "withdraw_frozen", amount.negate(), balanceBefore, balance, frozen, no, "withdrawal", "提现申请冻结");
        Map<String, Object> result = wallet(userId);
        result.put("idempotent", false);
        return result;
    }

    public List<Map<String, Object>> withdrawals(Long userId)
    {
        return jdbc.queryForList(
                "select id,withdrawal_no as withdrawalNo,amount,status,review_note as reviewNote,reviewed_at as reviewedAt,created_at as createdAt " +
                "from club_withdrawal where user_id=? order by id desc", userId);
    }

    public Map<String, Object> withdrawal(Long userId, Long id)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,withdrawal_no as withdrawalNo,amount,status,review_note as reviewNote,reviewed_at as reviewedAt,created_at as createdAt " +
                "from club_withdrawal where id=? and user_id=?", id, userId);
        if (rows.isEmpty()) throw new ServiceException("提现记录不存在");
        Map<String, Object> result = new HashMap<>(rows.get(0));
        result.put("records", jdbc.queryForList(
                "select id,record_type as type,amount,balance_before as balanceBefore,balance_after as balanceAfter,frozen_after as frozenAfter,description,created_at as createdAt " +
                "from club_wallet_record where user_id=? and reference_no=? order by id", userId, rows.get(0).get("withdrawalNo")));
        return result;
    }

    @Transactional
    public Map<String, Object> identity(Long userId, Map<String, Object> input)
    {
        requireSensitiveConsent(input);
        List<Map<String, Object>> current = jdbc.queryForList("select status from club_identity where user_id=? for update", userId);
        if (!current.isEmpty() && "approved".equals(text(current.get(0).get("status"))))
            throw new ServiceException("实名认证已经通过，如需变更请联系平台处理");
        if (!current.isEmpty() && "pending".equals(text(current.get(0).get("status"))))
            throw new ServiceException("实名认证正在审核中，请勿重复提交");
        String realName = required(input, "name", "请输入真实姓名");
        String idNumber = required(input, "idNumber", "请输入身份证号").toUpperCase();
        if (realName.length() < 2 || realName.length() > 32 || !ID_CARD.matcher(idNumber).matches())
        {
            throw new ServiceException("请填写正确的姓名和身份证号");
        }
        String nameMask = realName.substring(0, 1) + repeat("*", realName.length() - 1);
        String numberMask = idNumber.substring(0, 3) + "***********" + idNumber.substring(idNumber.length() - 4);
        String hash = sha256(identitySalt + ":" + idNumber);
        Integer used = jdbc.queryForObject("select count(1) from club_identity where id_no_hash=? and user_id<>?", Integer.class, hash, userId);
        if (used != null && used > 0)
        {
            throw new ServiceException("该身份信息已被其他账号使用");
        }
        String evidence = JSON.toJSONString(input.get("evidence") == null ? Collections.emptyList() : input.get("evidence"));
        if (evidence.length() > 16000) throw new ServiceException("实名认证审核材料数量过多");
        String nameCipher = identityCrypto.encrypt(realName);
        String numberCipher = identityCrypto.encrypt(idNumber);
        String evidenceCipher = identityCrypto.encrypt(evidence);
        jdbc.update("insert into club_identity(user_id,real_name_mask,id_no_mask,id_no_hash,real_name_cipher,id_no_cipher,evidence_cipher,status,submitted_at) values(?,?,?,?,?,?,?,'pending',now()) " +
                        "on duplicate key update real_name_mask=values(real_name_mask),id_no_mask=values(id_no_mask),id_no_hash=values(id_no_hash),real_name_cipher=values(real_name_cipher),id_no_cipher=values(id_no_cipher),evidence_cipher=values(evidence_cipher),status='pending',review_note='',submitted_at=now(),reviewed_at=null",
                userId, nameMask, numberMask, hash, nameCipher, numberCipher, evidenceCipher);
        recordSensitiveConsent(userId, "identity");
        return profile(userId);
    }

    private void recordSensitiveConsent(Long userId, String scene)
    {
        jdbc.update("insert into club_sensitive_consent(user_id,scene,notice_version,agreed_at) values(?,?,?,now())",
                userId, scene, "IDENTITY-20260910-v1");
    }

    static void requireSensitiveConsent(Map<String, Object> input)
    {
        if (!Boolean.TRUE.equals(input.get("sensitiveConsent")) ||
                !"IDENTITY-20260910-v1".equals(input.get("sensitiveConsentVersion")))
            throw new ServiceException("请更新小程序并单独同意身份信息处理后提交");
    }

    public Map<String, Object> identityStatus(Long userId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select real_name_mask as realName,id_no_mask as idNumber,status,review_note as reviewNote,submitted_at as submittedAt,reviewed_at as reviewedAt " +
                "from club_identity where user_id=?", userId);
        if (rows.isEmpty())
        {
            Map<String, Object> empty = new HashMap<>();
            empty.put("status", "unsubmitted");
            empty.put("canSubmit", true);
            return empty;
        }
        Map<String, Object> result = new HashMap<>(rows.get(0));
        result.put("canSubmit", "rejected".equals(text(result.get("status"))));
        return result;
    }

    public Map<String, Object> notificationSettings(Long userId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("select 1 as orderEnabled,promotion_enabled as promotionEnabled,1 as systemEnabled from club_notification_setting where user_id=?", userId);
        return rows.isEmpty() ? new HashMap<String, Object>() : rows.get(0);
    }

    public Map<String, Object> updateNotificationSettings(Long userId, Map<String, Object> input)
    {
        int promotion = bool(input.get("promotionEnabled")) ? 1 : 0;
        jdbc.update("insert into club_notification_setting(user_id,order_enabled,promotion_enabled,system_enabled) values(?,?,?,?) " +
                "on duplicate key update order_enabled=1,promotion_enabled=values(promotion_enabled),system_enabled=1", userId, 1, promotion, 1);
        return notificationSettings(userId);
    }

    @Transactional
    public Map<String, Object> application(Long userId, Map<String, Object> input)
    {
        requireSensitiveConsent(input);
        String type = required(input, "type", "缺少申请类型");
        if (!"player".equals(type))
        {
            throw new ServiceException("平台只接受打手入驻，不再支持商家开店");
        }
        String name = required(input, "name", "请输入联系人");
        String phone = required(input, "phone", "请输入手机号");
        if (!phone.matches("^1\\d{10}$"))
        {
            throw new ServiceException("请输入正确的手机号");
        }
        Integer existing = jdbc.queryForObject("select count(1) from club_application where user_id=? and application_type=? and status in ('pending','approved')", Integer.class, userId, type);
        if (existing != null && existing > 0)
        {
            throw new ServiceException("您已有审核中或已通过的同类申请");
        }
        List<Map<String,Object>> identityRows=jdbc.queryForList("select status from club_identity where user_id=? for update",userId);
        if(identityRows.isEmpty()||"rejected".equals(text(identityRows.get(0).get("status")))) identity(userId,input);
        String agreementVersion = null;
        if ("merchant".equals(type))
        {
            Map<String, Object> agreement = document("merchant-agreement");
            agreementVersion = required(input, "agreementVersion", "请先阅读并同意当前版本的商家入驻协议");
            if (!agreementVersion.equals(text(agreement.get("version"))))
                throw new ServiceException("商家入驻协议已更新，请重新阅读并同意后提交");
        }
        final String acceptedVersion = agreementVersion;
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "insert into club_application(user_id,application_type,display_name,real_name,phone,remark,invite_code,agreement_version,agreed_at) values(?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, userId);
            statement.setString(2, type);
            statement.setString(3, limited(input.get("nickname"), 100));
            statement.setString(4, limited(name, 64));
            statement.setString(5, phone);
            statement.setString(6, limited(input.get("remark"), 1000));
            statement.setString(7, limited(input.get("invite"), 64));
            statement.setString(8, acceptedVersion);
            statement.setObject(9, acceptedVersion == null ? null : new java.sql.Timestamp(System.currentTimeMillis()));
            return statement;
        }, key);
        Long id = key.getKey().longValue();
        recordSensitiveConsent(userId, "application");
        return jdbc.queryForMap("select id,application_type as type,status,agreement_version as agreementVersion,agreed_at as agreedAt,created_at as createdAt from club_application where id=?", id);
    }

    public List<Map<String, Object>> applications(Long userId, String type)
    {
        String filter = type == null ? "" : type.trim();
        if (!filter.isEmpty() && !Arrays.asList("player", "merchant").contains(filter))
            throw new ServiceException("申请类型不正确");
        return jdbc.queryForList(
                "select id,application_type as type,display_name as displayName,real_name as realName,phone,remark,invite_code as inviteCode," +
                "status,agreement_version as agreementVersion,agreed_at as agreedAt,review_note as reviewNote,reviewed_at as reviewedAt,created_at as createdAt " +
                "from club_application where user_id=? and (?='' or application_type=?) order by id desc", userId, filter, filter);
    }

    public Map<String, Object> applicationDetail(Long userId, Long id)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,application_type as type,display_name as displayName,real_name as realName,phone,remark,invite_code as inviteCode," +
                "status,agreement_version as agreementVersion,agreed_at as agreedAt,review_note as reviewNote,reviewed_at as reviewedAt,created_at as createdAt " +
                "from club_application where id=? and user_id=?", id, userId);
        if (rows.isEmpty()) throw new ServiceException("入驻申请不存在");
        return rows.get(0);
    }

    private String documentVersion(Map<String, Object> document)
    {
        return "AGR-" + document.get("id") + "-" + sha256(text(document.get("updatedAt"))).substring(0, 12);
    }

    private Map<String, Object> startupContent()
    {
        List<Map<String, Object>> rows = jdbc.queryForList("select id,title,subtitle,image,body_text,target_url,style_json,sort_no,start_at,end_at from club_content " +
                "where content_type='promotion' and scene='startup' and status='active' and popup_enabled=1 " +
                "and (start_at is null or start_at<=now()) and (end_at is null or end_at>=now()) order by sort_no,id limit 1");
        if (rows.isEmpty())
        {
            Map<String, Object> disabled = new HashMap<>();
            disabled.put("enabled", false);
            return disabled;
        }
        Map<String, Object> source = rows.get(0);
        Map<String, Object> result = new HashMap<>();
        result.put("id", source.get("id"));
        result.put("enabled", true);
        result.put("image", source.get("image"));
        result.put("brand", "品奢电竞");
        result.put("eyebrow", "PIN SHE ESPORTS");
        result.put("slogan", source.get("title"));
        result.put("subtitle", source.get("subtitle"));
        result.put("target", source.get("target_url"));
        result.put("startAt", source.get("start_at"));
        result.put("endAt", source.get("end_at"));
        Map<String, Object> style = jsonMap(source.get("style_json"));
        result.put("style", style);
        result.put("confirmText", style.containsKey("confirmText") ? style.get("confirmText") : "确认");
        List<Map<String, Object>> rules = new ArrayList<>();
        for (String line : lines(text(source.get("body_text"))))
        {
            Map<String, Object> rule = new HashMap<>();
            rule.put("title", line);
            rule.put("copy", "具体服务标准以商品详情及订单约定为准，如有问题请联系客服。");
            rules.add(rule);
        }
        result.put("rules", rules);
        result.put("body", lines(text(source.get("body_text"))));
        return result;
    }

    private List<Map<String, Object>> contentList(String type, String scene)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,title,subtitle,image,body_text,target_url as target,style_json,sort_no as sort from club_content " +
                "where content_type=? and scene=? and status='active' " +
                "and (start_at is null or start_at<=now()) and (end_at is null or end_at>=now()) order by sort_no,id", type, scene);
        for (Map<String, Object> row : rows)
        {
            row.put("enabled", true);
            row.put("body", lines(text(row.remove("body_text"))));
            Map<String, Object> style = jsonMap(row.remove("style_json"));
            row.putAll(style);
            if (style.containsKey("size"))
            {
                row.put("bodyStyle", style);
            }
        }
        return rows;
    }

    private List<Map<String, Object>> orderViews(List<Map<String, Object>> rows)
    {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows)
        {
            Map<String, Object> item = new HashMap<>();
            item.put("id", row.get("id"));
            item.put("orderNo", row.get("order_no"));
            item.put("status", row.get("status"));
            item.put("occupiedSeconds", row.get("occupied_seconds"));
            item.put("contactName", row.get("contact_name"));
            item.put("contactPhone", row.get("contact_phone"));
            item.put("gameId", row.get("game_id"));
            item.put("gameNickname", row.get("game_nickname"));
            item.put("remark", row.get("remark"));
            item.put("paymentExpiresAt", row.get("payment_expires_at"));
            item.put("paymentMethod", row.get("payment_method"));
            item.put("cancelReason", row.get("cancel_reason"));
            long remainingSeconds = Math.max(0L, number(row.get("payment_remaining_seconds")));
            item.put("remainingSeconds", remainingSeconds);
            item.put("serverTime", System.currentTimeMillis());
            item.put("qty", row.get("quantity"));
            item.put("total", row.get("total_amount"));
            item.put("originalAmount", row.get("original_amount"));
            item.put("discountAmount", row.get("discount_amount"));
            item.put("platformFee", row.get("platform_fee"));
            item.put("providerIncome", row.get("provider_income"));
            item.put("providerCompletedAt", row.get("provider_completed_at"));
            item.put("completedAt", row.get("completed_at"));
            item.put("reviewed", bool(row.get("reviewed")));
            item.put("canReview", !bool(row.get("reviewed")) && ("completed".equals(text(row.get("status"))) || (Arrays.asList("refunding","refunded").contains(text(row.get("status"))) && row.get("completed_at") != null)));
            item.put("createdAt", row.get("created_at"));
            item.put("paidAt", row.get("paid_at"));
            Map<String, Object> product = new HashMap<>();
            product.put("id", row.get("product_id"));
            product.put("name", row.get("product_name"));
            product.put("image", row.get("product_image"));
            item.put("product", product);
            Map<String, Object> sku = new HashMap<>();
            sku.put("id", row.get("sku_id"));
            sku.put("name", row.get("sku_name"));
            sku.put("price", row.get("unit_price"));
            item.put("sku", sku);
            if (row.get("player_id") != null)
            {
                Map<String, Object> player = new HashMap<>();
                player.put("id", row.get("player_id"));
                player.put("name", row.get("player_name"));
                player.put("image", row.get("player_image"));
                item.put("player", player);
            }
            result.add(item);
        }
        return result;
    }

    private void logOrder(Long orderId, String from, String to, String operatorType, Long operatorId, String note)
    {
        jdbc.update("insert into club_order_log(order_id,from_status,to_status,operator_type,operator_id,note) values(?,?,?,?,?,?)",
                orderId, from, to, operatorType, operatorId, note);
    }

    private void message(Long userId, String type, String title, String content, String referenceType, Long referenceId)
    {
        if ("promotion".equals(type))
        {
            Integer enabled = jdbc.queryForObject("select count(1) from club_notification_setting where user_id=? and promotion_enabled=1", Integer.class, userId);
            if (enabled == null || enabled == 0) return;
        }
        jdbc.update("insert into club_message(user_id,message_type,title,content,reference_type,reference_id) values(?,?,?,?,?,?)",
                userId, type, title, content, referenceType, referenceId);
    }

    private void ensureExists(String table, Long id, String message)
    {
        if (!Arrays.asList("club_product", "club_shop").contains(table))
        {
            throw new IllegalArgumentException("unsupported table");
        }
        Integer count = jdbc.queryForObject("select count(1) from " + table + " where id=? and status='active'", Integer.class, id);
        if (count == null || count == 0)
        {
            throw new ServiceException(message);
        }
    }

    private static Map<String, Object> flag(String key, boolean value)
    {
        Map<String, Object> result = new HashMap<>();
        result.put(key, value);
        return result;
    }

    private static void normalizeBoolean(List<Map<String, Object>> rows, String key)
    {
        for (Map<String, Object> row : rows)
        {
            row.put(key, number(row.get(key)) == 1L);
        }
    }

    private static String normalizeProductSort(String sort)
    {
        String value = sort == null ? "default" : sort.trim().toLowerCase();
        return Arrays.asList("default", "sales", "price", "views", "reviews").contains(value) ? value : "default";
    }

    private static String productOrder(String sort)
    {
        switch (normalizeProductSort(sort))
        {
            case "sales": return "p.sales desc,p.id desc";
            case "price": return "sku.price asc,p.id desc";
            case "views": return "p.views desc,p.id desc";
            case "reviews": return "(select count(1) from club_review r where r.product_id=p.id and r.status='visible') desc,p.id desc";
            default: return "p.is_hot desc,p.id desc";
        }
    }

    private static String normalizePlayerSort(String sort)
    {
        String value = sort == null ? "default" : sort.trim().toLowerCase();
        return Arrays.asList("default", "newest").contains(value) ? value : "default";
    }

    private static String playerOrder(String sort)
    {
        return "newest".equals(normalizePlayerSort(sort))
                ? "pp.id desc"
                : "pp.online_status desc,pp.sort_no,pp.id";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value)
    {
        return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }

    private static Long nestedId(Map<String, Object> input, String directKey, String nestedKey)
    {
        Object value = input.get(directKey);
        if (value == null)
        {
            value = map(input.get(nestedKey)).get("id");
        }
        if (value == null)
        {
            throw new ServiceException("缺少" + nestedKey + "编号");
        }
        return number(value);
    }

    private static Long nestedOptionalId(Map<String, Object> input, String directKey, String nestedKey)
    {
        Object value = input.get(directKey);
        if (value == null)
        {
            value = map(input.get(nestedKey)).get("id");
        }
        return value == null || text(value).isEmpty() ? null : number(value);
    }

    private static String required(Map<String, Object> input, String key, String message)
    {
        String value = text(input.get(key)).trim();
        if (value.isEmpty())
        {
            throw new ServiceException(message);
        }
        return value;
    }

    private static String limited(Object value, int max)
    {
        String result = text(value).trim();
        if (result.length() > max)
        {
            throw new ServiceException("输入内容过长");
        }
        return result;
    }

    private static String text(Object value)
    {
        return value == null ? "" : String.valueOf(value);
    }

    private static Long number(Object value)
    {
        return value instanceof Number ? ((Number) value).longValue() : Long.valueOf(String.valueOf(value));
    }

    private static int integer(Object value, int defaultValue)
    {
        if (value == null || text(value).isEmpty())
        {
            return defaultValue;
        }
        return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(text(value));
    }

    private static BigDecimal decimal(Object value)
    {
        try
        {
            return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(text(value));
        }
        catch (Exception e)
        {
            throw new ServiceException("金额格式不正确");
        }
    }

    static Instant paymentExpiryInstant(Object value)
    {
        if (value == null) return null;
        if (value instanceof Timestamp) return ((Timestamp) value).toInstant();
        if (value instanceof LocalDateTime) return Timestamp.valueOf((LocalDateTime) value).toInstant();
        if (value instanceof OffsetDateTime) return ((OffsetDateTime) value).toInstant();
        if (value instanceof java.util.Date) return ((java.util.Date) value).toInstant();
        throw new ServiceException("支付截止时间格式不正确");
    }

    private static boolean bool(Object value)
    {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(text(value)) || "1".equals(text(value));
    }

    private static List<String> lines(String value)
    {
        if (value.trim().isEmpty())
        {
            return new ArrayList<>();
        }
        return Arrays.asList(value.split("\\r?\\n"));
    }

    private String configValue(String key, String fallback)
    {
        List<String> values = jdbc.queryForList("select config_value from club_business_config where config_key=?", String.class, key);
        return values.isEmpty() || values.get(0) == null || values.get(0).trim().isEmpty() ? fallback : values.get(0).trim();
    }

    private static String maskNickname(String nickname)
    {
        if (nickname.isEmpty()) return "匿名用户";
        if (nickname.length() == 1) return nickname + "***";
        return nickname.substring(0, 1) + repeat("*", Math.min(4, nickname.length() - 1));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonMap(Object value)
    {
        try
        {
            String source = text(value);
            return source.isEmpty() ? new HashMap<String, Object>() : JSON.parseObject(source, Map.class);
        }
        catch (Exception e)
        {
            return new HashMap<>();
        }
    }

    private static String randomDigits()
    {
        return String.format("%04d", Math.abs(UUID.randomUUID().hashCode()) % 10000);
    }

    private static String repeat(String value, int count)
    {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) builder.append(value);
        return builder.toString();
    }

    private static String sha256(String value)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : digest) builder.append(String.format("%02x", item));
            return builder.toString();
        }
        catch (Exception e)
        {
            throw new IllegalStateException("摘要生成失败", e);
        }
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
}
