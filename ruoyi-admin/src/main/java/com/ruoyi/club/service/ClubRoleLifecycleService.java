package com.ruoyi.club.service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;

/** 商家/陪玩互斥身份的唯一变更入口。 */
@Service
public class ClubRoleLifecycleService
{
    private final JdbcTemplate jdbc;

    public ClubRoleLifecycleService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void switchRole(Long userId, String targetRole, String displayName, String description)
    {
        if (!Arrays.asList("user", "player", "merchant").contains(targetRole)) throw new ServiceException("用户角色不正确");
        List<Map<String, Object>> users = jdbc.queryForList("select id,user_type,status,nickname from club_user where id=? for update", userId);
        if (users.isEmpty()) throw new ServiceException("用户不存在");
        Map<String, Object> user = users.get(0);
        if (!"active".equals(text(user.get("status")))) throw new ServiceException("停用账号不能建立业务身份");
        String current = text(user.get("user_type"));
        if (!current.equals(targetRole)) assertSwitchable(userId);

        if (!"merchant".equals(targetRole))
        {
            jdbc.update("update club_shop set status='disabled' where owner_user_id=? and status<>'deleted'", userId);
            jdbc.update("update club_product p join club_shop s on s.id=p.shop_id set p.status='inactive' where s.owner_user_id=? and p.status='active'", userId);
        }
        if (!"player".equals(targetRole))
        {
            jdbc.update("update club_player_profile set status='disabled',online_status=0 where user_id=?", userId);
            jdbc.update("update club_player_service ps join club_player_profile p on p.id=ps.player_id set ps.status='inactive' where p.user_id=? and ps.status='active'", userId);
        }

        String name = text(displayName).isEmpty() ? text(user.get("nickname")) : text(displayName);
        if ("player".equals(targetRole))
        {
            jdbc.update("insert into club_player_profile(user_id,display_name,intro,online_status,status) values(?,?,?,0,'active') on duplicate key update display_name=values(display_name),intro=case when values(intro)<>'' then values(intro) else intro end,online_status=0,status='active'",
                    userId, name, text(description));
        }
        else if ("merchant".equals(targetRole))
        {
            jdbc.update("insert into club_shop(name,description,owner_user_id,status) values(?,?,?,'active') on duplicate key update name=values(name),description=case when values(description)<>'' then values(description) else description end,status='active'",
                    name.isEmpty() ? "新商家店铺" : name, text(description), userId);
        }
        jdbc.update("insert into club_role_status(user_id,role_type,service_status) values(?,?,'offline') on duplicate key update role_type=values(role_type),service_status='offline'", userId, targetRole);
        jdbc.update("update club_user set user_type=? where id=?", targetRole, userId);
    }

    @Transactional
    public void deactivateAccount(Long userId)
    {
        assertSwitchable(userId);
        jdbc.update("update club_role_status set service_status='offline' where user_id=?", userId);
        jdbc.update("update club_player_profile set status='disabled',online_status=0 where user_id=?", userId);
        jdbc.update("update club_player_service ps join club_player_profile p on p.id=ps.player_id set ps.status='inactive' where p.user_id=? and ps.status='active'", userId);
        jdbc.update("update club_shop set status='disabled' where owner_user_id=? and status<>'deleted'", userId);
        jdbc.update("update club_product p join club_shop s on s.id=p.shop_id set p.status='inactive' where s.owner_user_id=? and p.status='active'", userId);
    }

    @Transactional
    public void returnPlayerForReapplication(Long playerId, String reason)
    {
        if (reason == null || reason.trim().isEmpty() || reason.length() > 500)
            throw new ServiceException("请填写500字以内的退回原因");
        List<Map<String,Object>> profiles = jdbc.queryForList("select user_id from club_player_profile where id=?", playerId);
        if (profiles.isEmpty()) throw new ServiceException("打手不存在");
        Long userId = ((Number) profiles.get(0).get("user_id")).longValue();
        List<Map<String,Object>> users = jdbc.queryForList("select user_type from club_user where id=? for update", userId);
        if (users.isEmpty() || !"player".equals(text(users.get(0).get("user_type"))))
            throw new ServiceException("该账号已不是打手，请勿重复退回");
        switchRole(userId, "user", "", "");
        jdbc.update("update club_product set status='inactive' where bound_player_id=? and status='active'", playerId);
        jdbc.update("update club_application set status='rejected',review_note=?,reviewed_at=now() where user_id=? and application_type='player' and status in ('pending','approved')",
                "资格已撤销，可重新申请：" + reason.trim(), userId);
    }

    public void assertSwitchable(Long userId)
    {
        Integer orders = jdbc.queryForObject("select count(1) from club_order where provider_user_id=? and status in ('pending','accepted','serving','refunding')", Integer.class, userId);
        Integer aftersales = jdbc.queryForObject("select count(1) from club_aftersale where provider_user_id=? and status in ('applied','provider_approved','provider_rejected','platform_reviewing','approved')", Integer.class, userId);
        Integer withdrawals = jdbc.queryForObject("select count(1) from club_withdrawal where user_id=? and status='pending'", Integer.class, userId);
        Integer receivables = jdbc.queryForObject("select count(1) from club_provider_receivable where provider_user_id=? and status='outstanding' and recovered_amount<amount", Integer.class, userId);
        BigDecimal frozen = jdbc.queryForObject("select coalesce(max(frozen),0) from club_wallet where user_id=?", BigDecimal.class, userId);
        if (positive(orders) || positive(aftersales) || positive(withdrawals) || positive(receivables) || (frozen != null && frozen.compareTo(BigDecimal.ZERO) > 0))
            throw new ServiceException("当前账号存在未完成订单、售后、冻结资金、提现或退款追偿，不能切换或停用业务身份");
    }

    private static boolean positive(Integer value) { return value != null && value > 0; }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
