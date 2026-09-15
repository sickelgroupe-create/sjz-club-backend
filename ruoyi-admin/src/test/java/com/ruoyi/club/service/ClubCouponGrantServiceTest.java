package com.ruoyi.club.service;
import java.util.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.exception.ServiceException;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class ClubCouponGrantServiceTest {
    private Map<String,Object> coupon(){Map<String,Object> c=new HashMap<>();c.put("distribution_mode","public");c.put("status","active");c.put("valid_now",1);c.put("total_count",20);c.put("issued_count",0);c.put("per_user_limit",3);return c;}
    private JdbcTemplate db(Map<String,Object> c){JdbcTemplate db=mock(JdbcTemplate.class);when(db.queryForList(contains("select c.*"),eq(5L))).thenReturn(Collections.singletonList(c));when(db.update(contains("update club_coupon set issued_count"),eq(1),eq(5L),eq(1))).thenReturn(1);return db;}
    @Test void targetedCouponCannotBeClaimed(){Map<String,Object> c=coupon();c.put("distribution_mode","targeted");JdbcTemplate db=db(c);assertThrows(ServiceException.class,()->new ClubCouponGrantService(db).claim(2L,5L));verify(db,never()).update(contains("insert into club_coupon_issue"),any(Object[].class));}
    @Test void publicClaimIssuesExactlyOne(){JdbcTemplate db=db(coupon());new ClubCouponGrantService(db).claim(2L,5L);verify(db).update(contains("insert into club_coupon_issue"),eq(2L),eq(5L),eq(1),eq(1),eq("public"),eq("public:2:5"));}
    @Test void duplicateClaimDoesNotIssueAgain(){JdbcTemplate db=db(coupon());when(db.queryForObject(contains("idempotency_key=?"),eq(Integer.class),eq(2L),eq("public:2:5"))).thenReturn(1);new ClubCouponGrantService(db).claim(2L,5L);verify(db,never()).update(contains("update club_coupon set issued_count"),eq(1),eq(5L),eq(1));}
    @Test void expiredOrExhaustedCouponIsRejected(){Map<String,Object> c=coupon();c.put("valid_now",0);assertThrows(ServiceException.class,()->new ClubCouponGrantService(db(c)).claim(2L,5L));c.put("valid_now",1);c.put("issued_count",20);assertThrows(ServiceException.class,()->new ClubCouponGrantService(db(c)).claim(2L,5L));}
    @Test void individualLimitIsRespected(){JdbcTemplate db=db(coupon());when(db.queryForObject(contains("sum(quantity)"),eq(Integer.class),eq(2L),eq(5L))).thenReturn(3);assertThrows(ServiceException.class,()->new ClubCouponGrantService(db).claim(2L,5L));}
    @Test void unpaidOrAbsentRechargeDoesNotIssue(){JdbcTemplate db=mock(JdbcTemplate.class);new ClubCouponGrantService(db).issueReward(9L);verify(db).queryForList(contains("o.status='success'"),eq(9L));verify(db,never()).queryForList(contains("select c.*"),eq(5L));}
    @Test void paidRechargeWithStockShortageStaysPending(){Map<String,Object> c=coupon();c.put("issued_count",20);JdbcTemplate db=db(c);Map<String,Object> reward=new HashMap<>();reward.put("id",8L);reward.put("user_id",2L);reward.put("coupon_id",5L);reward.put("quantity",1);reward.put("status","pending");when(db.queryForList(contains("o.status='success'"),eq(9L))).thenReturn(Collections.singletonList(reward));new ClubCouponGrantService(db).issueReward(9L);verify(db).update(contains("set last_error=?"),anyString(),eq(8L));verify(db,never()).update(contains("set status='issued'"),eq(8L));}
    @Test void completedRewardIsIdempotent(){JdbcTemplate db=mock(JdbcTemplate.class);when(db.queryForList(contains("o.status='success'"),eq(9L))).thenReturn(Collections.singletonList(Collections.singletonMap("status","issued")));new ClubCouponGrantService(db).issueReward(9L);verify(db,never()).queryForList(contains("select c.*"),eq(5L));}
    @Test void ruleSnapshotIsHighestEligibleThreshold(){JdbcTemplate db=mock(JdbcTemplate.class);Map<String,Object> r=new HashMap<>();r.put("id",3L);r.put("coupon_id",5L);r.put("quantity",2);when(db.queryForList(contains("order by min_amount desc,id desc limit 1"),eq(new BigDecimal("100")))).thenReturn(Collections.singletonList(r));new ClubCouponGrantService(db).snapshotReward(9L,2L,new BigDecimal("100"));verify(db).update(contains("insert into club_recharge_reward"),eq(9L),eq(2L),eq(3L),eq(5L),eq(2));}
}
