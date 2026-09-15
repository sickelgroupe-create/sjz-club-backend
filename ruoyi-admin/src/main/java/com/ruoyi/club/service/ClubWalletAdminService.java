package com.ruoyi.club.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.club.web.ClubConflictException;

/** Available balance adjustments are append-only business events, never direct balance edits. */
@Service
public class ClubWalletAdminService {
    private final JdbcTemplate jdbc;
    public ClubWalletAdminService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String,Object> list(String keyword, int page, int size) {
        String term = keyword == null ? "" : keyword.trim();
        Long uid=ClubUidSearch.parse(term);
        if(uid!=null){int limit=Math.min(100,Math.max(1,size));String scope=" from club_wallet w join club_user u on u.id=w.user_id where u.id=?";return page(jdbc.queryForList("select w.*,u.nickname,u.user_type"+scope+" limit ? offset ?",uid,limit,(Math.max(1,page)-1)*limit),jdbc.queryForObject("select count(*)"+scope,Long.class,uid));}
        String like = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String where = " from club_wallet w join club_user u on u.id=w.user_id where " +
            "(?='' or cast(u.id as char)=? or u.nickname like ? escape '!' or u.account like ? escape '!')";
        int limit = Math.min(100, Math.max(1,size));
        List<Map<String,Object>> rows = jdbc.queryForList("select w.*,u.nickname,u.user_type" + where +
            " order by w.updated_at desc,w.user_id desc limit ? offset ?", term,term,like,like,limit,(Math.max(1,page)-1)*limit);
        return page(rows,jdbc.queryForObject("select count(*)"+where,Long.class,term,term,like,like));
    }

    public Map<String,Object> detail(Long userId) {
        List<Map<String,Object>> rows=jdbc.queryForList("select w.*,u.nickname,u.user_type from club_wallet w join club_user u on u.id=w.user_id where w.user_id=?",userId);
        if(rows.isEmpty()) throw new ServiceException("用户钱包不存在");
        return rows.get(0);
    }

    public Map<String,Object> records(Long userId,String start,String end,int page,int size) {
        detail(userId);
        LocalDate from=date(start), to=date(end);
        if(from!=null && to!=null && from.isAfter(to)) throw new ServiceException("开始日期不能晚于结束日期");
        String where=" where r.user_id=?";
        List<Object> args=new ArrayList<>();args.add(userId);
        if(from!=null){where+=" and r.created_at>=?";args.add(from.toString()+" 00:00:00");}
        if(to!=null){where+=" and r.created_at<?";args.add(to.plusDays(1).toString()+" 00:00:00");}
        Long total=jdbc.queryForObject("select count(*) from club_wallet_record r"+where,Long.class,args.toArray());
        int limit=Math.min(100,Math.max(1,size)); args.add(limit);args.add((Math.max(1,page)-1)*limit);
        return page(jdbc.queryForList("select r.*,o.order_no,a.admin_id as adjusted_by,a.reason as adjustment_reason " +
            "from club_wallet_record r left join club_order o on o.id=r.order_id " +
            "left join club_wallet_adjustment a on a.adjustment_no=r.reference_no and a.user_id=r.user_id"+
            where+" order by r.created_at desc,r.id desc limit ? offset ?",args.toArray()),total);
    }

    static LocalDate date(String value) {
        if(value==null || value.trim().isEmpty())return null;
        try { if(!value.matches("\\d{4}-\\d{2}-\\d{2}"))throw new DateTimeParseException("format",value,0);
            LocalDate parsed=LocalDate.parse(value);if(parsed.getYear()<1970 || parsed.getYear()>9998)throw new ServiceException("日期超出范围");return parsed;
        } catch(DateTimeParseException e){throw new ServiceException("日期格式必须为年-月-日");}
    }

    static BigDecimal amount(Object value) {
        try {BigDecimal n=new BigDecimal(String.valueOf(value));
            if(n.signum()<=0 || n.compareTo(new BigDecimal("9999999999.99"))>0)throw new ArithmeticException();
            return n.setScale(2,RoundingMode.UNNECESSARY);
        }catch(RuntimeException e){throw new ServiceException("调整金额必须大于0，最多两位小数且不超出钱包金额范围");}
    }

    @Transactional
    public Map<String,Object> adjust(Long userId,Long adminId,Map<String,Object> input) {
        BigDecimal amount=amount(input.get("amount"));
        String direction=string(input.get("direction")),reason=string(input.get("reason")),requestId=string(input.get("requestId"));
        if(!Arrays.asList("credit","debit").contains(direction))throw new ServiceException("请选择增加或扣减余额");
        if(reason.isEmpty() || reason.length()>200)throw new ServiceException("请填写1至200字的调整原因");
        if(!requestId.matches("[A-Za-z0-9_-]{8,96}"))throw new ServiceException("缺少有效的操作幂等号，请重新打开调整窗口");
        String hash=DigestUtils.md5DigestAsHex(JSON.toJSONString(Arrays.asList(userId,direction,amount.toPlainString(),reason)).getBytes(StandardCharsets.UTF_8));
        String no="WA"+UUID.randomUUID().toString().replace("-","");
        jdbc.update("insert into club_wallet_adjustment(adjustment_no,admin_id,request_id,payload_hash,user_id,direction,amount,reason) values(?,?,?,?,?,?,?,?) " +
            "on duplicate key update id=id",no,adminId,requestId,hash,userId,direction,amount,reason);
        Map<String,Object> event=jdbc.queryForMap("select * from club_wallet_adjustment where admin_id=? and request_id=? for update",adminId,requestId);
        if(!hash.equals(event.get("payload_hash")))throw new ClubConflictException("同一操作号的金额、用户或原因不同，请勿重复提交");
        if(event.get("result_json")!=null)return JSON.parseObject(String.valueOf(event.get("result_json")));
        if(jdbc.queryForList("select id from club_user where id=? and status='active' for update",userId).isEmpty())
            throw new ServiceException("用户不存在或已停用，禁止调整钱包");
        List<Map<String,Object>> wallets=jdbc.queryForList("select * from club_wallet where user_id=? for update",userId);
        if(wallets.isEmpty())throw new ServiceException("用户钱包不存在");
        Map<String,Object> wallet=wallets.get(0);
        BigDecimal before=new BigDecimal(wallet.get("balance").toString());
        BigDecimal delta="credit".equals(direction)?amount:amount.negate(),after=before.add(delta);
        if(after.signum()<0)throw new ServiceException("可用余额不足，不能扣减冻结资金或产生负余额");
        if(after.compareTo(new BigDecimal("9999999999.99"))>0)throw new ServiceException("调整后的余额超出上限");
        jdbc.update("update club_wallet set balance=?,version=version+1,updated_at=now() where user_id=?",after,userId);
        String adjustmentNo=event.get("adjustment_no").toString();
        jdbc.update("insert into club_wallet_record(user_id,record_type,amount,balance_before,balance_after,frozen_after,reference_no,description,counterparty_type) values(?,?,?,?,?,?,?,?,?)",
            userId,"admin_"+direction,delta,before,after,wallet.get("frozen"),adjustmentNo,"管理员余额调整："+reason,"admin");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("adjustmentNo",adjustmentNo);result.put("userId",userId);result.put("amount",delta);
        result.put("balanceBefore",before);result.put("balanceAfter",after);result.put("frozen",wallet.get("frozen"));
        result.put("adminId",adminId);result.put("reason",reason);
        String json=JSON.toJSONString(result);
        jdbc.update("update club_wallet_adjustment set result_json=?,completed_at=now() where id=?",json,event.get("id"));
        // Audit contains amounts and IDs only; the business reason stays in restricted wallet detail.
        Map<String,Object> audit=new LinkedHashMap<>(result);audit.remove("reason");
        jdbc.update("insert into club_admin_audit(admin_id,permission_code,entity_type,entity_id,action,request_id,before_json,after_json,reason) values(?,?,?,?,?,?,?,?,?)",
            adminId,"club:wallet:adjust","wallets",userId.toString(),"balance_adjust",requestId,
            JSON.toJSONString(Collections.singletonMap("balance",before)),JSON.toJSONString(audit),"调整原因见钱包调整单 "+adjustmentNo);
        return result;
    }

    private static String string(Object value){return value==null?"":value.toString().trim();}
    private static Map<String,Object> page(List<Map<String,Object>> rows,Long total){Map<String,Object> out=new HashMap<>();out.put("rows",rows);out.put("total",total);return out;}
}
