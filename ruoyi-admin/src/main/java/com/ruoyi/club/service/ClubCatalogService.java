package com.ruoyi.club.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;

/** 商品、SKU、店铺计数和唯一履约主体的共享业务规则。 */
@Service
public class ClubCatalogService
{
    private final JdbcTemplate jdbc;

    public ClubCatalogService(JdbcTemplate jdbc)
    {
        this.jdbc = jdbc;
    }

    public void validatePriceAndStock(BigDecimal price, int stock)
    {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0 || price.scale() > 2)
        {
            throw new ServiceException("价格必须大于0且最多保留两位小数");
        }
        if (stock < 0)
        {
            throw new ServiceException("库存不能小于0");
        }
    }

    public void syncProductSummary(Long productId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select min(price) min_price,coalesce(sum(stock),0) total_stock,count(1) sku_count " +
                "from club_product_sku where product_id=? and status='active'", productId);
        if (rows.isEmpty() || number(rows.get(0).get("sku_count")) < 1)
        {
            jdbc.update("update club_product set stock=0 where id=?", productId);
            return;
        }
        jdbc.update("update club_product set price=?,stock=? where id=?",
                rows.get(0).get("min_price"), rows.get(0).get("total_stock"), productId);
    }

    public Map<String, Object> syncShopFans(Long shopId)
    {
        List<Map<String, Object>> shops = jdbc.queryForList(
                "select id,base_fans_count,status from club_shop where id=? for update", shopId);
        if (shops.isEmpty()) throw new ServiceException("店铺不存在");
        int relationCount = jdbc.queryForObject("select count(1) from club_follow where shop_id=?", Integer.class, shopId);
        int base = integer(shops.get(0).get("base_fans_count"));
        int display = Math.max(0, base + relationCount);
        jdbc.update("update club_shop set fans_count=? where id=?", display, shopId);
        Map<String, Object> result = new HashMap<>();
        result.put("relationCount", relationCount);
        result.put("baseFans", base);
        result.put("fans", display);
        result.put("shopStatus", text(shops.get(0).get("status")));
        return result;
    }

    /** 订单创建时解析并固化唯一履约主体。 */
    public Map<String, Object> resolveProvider(Long productId, Long skuId, Long playerId)
    {
        List<Map<String,Object>> bindings=jdbc.queryForList("select bound_player_id from club_product where id=? and status='active'",productId);
        if(bindings.isEmpty()||bindings.get(0).get("bound_player_id")==null)throw new ServiceException("商品尚未绑定打手，请联系客服");
        Long assigned=((Number)bindings.get(0).get("bound_player_id")).longValue();
        if(playerId!=null&&!assigned.equals(playerId))throw new ServiceException("所选打手不是该商品绑定的打手");
        List<Map<String,Object>> rows=jdbc.queryForList(
            "select pp.id provider_id,pp.user_id provider_user_id from club_player_profile pp "+
            "join club_user u on u.id=pp.user_id and u.status='active' and u.user_type='player' "+
            "join club_role_status rs on rs.user_id=pp.user_id and rs.role_type='player' and rs.service_status='online' "+
            "where pp.id=? and pp.status='active' and pp.online_status=1 and "+ClubPlayerEligibility.approved("pp")+" and not "+ClubPlayerCapacity.busy("pp.user_id"),assigned);
        if(rows.isEmpty())throw new ServiceException("该商品的打手正在服务或暂停接单，请稍后再试");
        Map<String,Object> result=new HashMap<>(rows.get(0));result.put("provider_type","player");return result;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static int integer(Object value) { return value == null ? 0 : ((Number) value).intValue(); }
    private static long number(Object value) { return value == null ? 0L : ((Number) value).longValue(); }
}
