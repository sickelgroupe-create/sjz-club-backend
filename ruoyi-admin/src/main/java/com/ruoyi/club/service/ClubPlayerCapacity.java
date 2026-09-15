package com.ruoyi.club.service;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.exception.ServiceException;

/** A player's user row serializes starts/accepts across all of their products. */
final class ClubPlayerCapacity {
    private ClubPlayerCapacity() { }
    static String busy(String userExpression) { return busy(userExpression, null); }
    static String busy(String userExpression, String excludedOrder) {
        return "exists(select 1 from club_order busy_order where busy_order.provider_user_id="+userExpression+" and busy_order.provider_type='player' and "+occupied("busy_order")+(excludedOrder==null?"":" and busy_order.id<>"+excludedOrder)+")";
    }
    static String occupied(String alias) {
        return alias+".provider_completed_at is null and ("+alias+".status in ('accepted','serving') or ("+alias+".status='refunding' and (select capacity_log.from_status from club_order_log capacity_log where capacity_log.order_id="+alias+".id and capacity_log.to_status='refunding' order by capacity_log.id desc limit 1) in ('accepted','serving')))";
    }
    /** The latest pending -> accepted/direct-start log starts this occupancy round. */
    static String elapsed(String alias) {
        String start="(select clock_log.created_at from club_order_log clock_log where clock_log.order_id="+alias+".id and clock_log.from_status='pending' and clock_log.to_status in ('accepted','serving') order by clock_log.id desc limit 1)";
        return "case when "+alias+".provider_type='player' and "+occupied(alias)+" then greatest(timestampdiff(second,"+start+",now()),0) else null end";
    }
    static String playerElapsed(String userExpression) {
        return "(select max("+elapsed("elapsed_order")+") from club_order elapsed_order where elapsed_order.provider_user_id="+userExpression+" and elapsed_order.provider_type='player' and "+occupied("elapsed_order")+")";
    }
    static void assertAvailable(JdbcTemplate jdbc,Object userId,Object currentOrderId) {
        if(userId==null)throw new ServiceException("订单没有绑定有效打手，请联系平台处理");
        if(jdbc.queryForList("select id from club_user where id=? for update",userId).isEmpty())throw new ServiceException("打手账号不存在");
        // Locking read sees a previous concurrent start even under repeatable-read isolation.
        List<Map<String,Object>> busy=jdbc.queryForList("select capacity_order.id from club_order capacity_order where capacity_order.provider_user_id=? and capacity_order.provider_type='player' and "+occupied("capacity_order")+" and capacity_order.id<>? for update",userId,currentOrderId==null?0L:currentOrderId);
        if(!busy.isEmpty())throw new ServiceException("该打手接单中，暂时无法服务其他订单，请等待本单服务结束");
    }
}
