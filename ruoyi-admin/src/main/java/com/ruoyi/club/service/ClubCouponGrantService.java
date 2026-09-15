package com.ruoyi.club.service;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.exception.ServiceException;

/** Called inside the owning app/admin transaction. Lock coupon before issuing. */
public class ClubCouponGrantService {
    private final JdbcTemplate jdbc;
    public ClubCouponGrantService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Map<String,Object>> publicCoupons(Long userId){
        return jdbc.queryForList("select c.id,c.name,c.amount,c.min_spend as minSpend,c.valid_until as validUntil,c.product_id as productId,"+
            "greatest(c.total_count-c.issued_count,0) as available,(select count(1) from club_coupon_issue i where i.coupon_id=c.id and i.user_id=? and i.source='public') as claimed "+
            "from club_coupon c where c.distribution_mode='public' and c.status='active' and now() between c.valid_from and c.valid_until order by c.id desc",userId);
    }
    public void claim(Long userId,Long couponId){
        String error=grant(userId,couponId,1,"public","public:"+userId+":"+couponId,true);
        if(error!=null)throw new ServiceException(error);
    }
    public void snapshotReward(Long rechargeId,Long userId,BigDecimal amount){
        List<Map<String,Object>> rules=jdbc.queryForList("select id,coupon_id,quantity from club_recharge_coupon_rule where status='active' and min_amount<=? order by min_amount desc,id desc limit 1",amount);
        if(rules.isEmpty())return;
        Map<String,Object> rule=rules.get(0);
        jdbc.update("insert into club_recharge_reward(recharge_id,user_id,rule_id,coupon_id,quantity) values(?,?,?,?,?)",rechargeId,userId,rule.get("id"),rule.get("coupon_id"),rule.get("quantity"));
    }
    public void issueReward(Long rechargeId){
        List<Map<String,Object>> rows=jdbc.queryForList("select r.* from club_recharge_reward r join club_recharge_order o on o.id=r.recharge_id where r.recharge_id=? and o.status='success' for update",rechargeId);
        if(rows.isEmpty()||"issued".equals(rows.get(0).get("status")))return;
        Map<String,Object> r=rows.get(0);
        String error=grant(((Number)r.get("user_id")).longValue(),((Number)r.get("coupon_id")).longValue(),((Number)r.get("quantity")).intValue(),"recharge","recharge:"+rechargeId,false);
        if(error==null)jdbc.update("update club_recharge_reward set status='issued',last_error='',issued_at=now() where id=?",r.get("id"));
        else jdbc.update("update club_recharge_reward set last_error=? where id=?",error,r.get("id"));
    }
    private String grant(Long userId,Long couponId,int quantity,String source,String key,boolean publicOnly){
        List<Map<String,Object>> rows=jdbc.queryForList("select c.*,case when now() between valid_from and valid_until then 1 else 0 end valid_now from club_coupon c where id=? for update",couponId);
        if(rows.isEmpty())return "优惠券不存在";
        Map<String,Object> c=rows.get(0);
        if(publicOnly&&!"public".equals(c.get("distribution_mode")))return "该优惠券不开放领取";
        Integer previous=jdbc.queryForObject("select count(1) from club_coupon_issue where user_id=? and idempotency_key=?",Integer.class,userId,key);
        if(previous!=null&&previous>0)return null;
        if(!"active".equals(c.get("status"))||number(c.get("valid_now"))!=1)return "优惠券未生效、已过期或停用，请配置后补发";
        if(quantity<1||number(c.get("total_count"))-number(c.get("issued_count"))<quantity)return "优惠券库存不足，请补充库存后补发";
        Integer owned=jdbc.queryForObject("select coalesce(sum(quantity),0) from club_coupon_issue where user_id=? and coupon_id=?",Integer.class,userId,couponId);
        if((owned==null?0:owned)+quantity>number(c.get("per_user_limit")))return "已达到该优惠券每人发放上限";
        jdbc.update("insert into club_coupon_issue(user_id,coupon_id,quantity,remaining_count,source,idempotency_key,status) values(?,?,?,?,?,?,'active')",userId,couponId,quantity,quantity,source,key);
        if(jdbc.update("update club_coupon set issued_count=issued_count+? where id=? and issued_count+?<=total_count",quantity,couponId,quantity)!=1)throw new ServiceException("优惠券库存冲突，请重试");
        return null;
    }
    private static int number(Object v){return v==null?0:((Number)v).intValue();}
}
