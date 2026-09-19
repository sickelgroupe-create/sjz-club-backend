package com.ruoyi.club.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.ruoyi.common.exception.ServiceException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ClubAccountMoneyGuardTest {
    @Test void concurrentWithdrawalRecoveryUsesCommittedStateAfterWalletLock() throws Exception {
        org.springframework.transaction.annotation.Transactional transaction=ClubAppService.class
                .getMethod("withdraw",Long.class,Map.class).getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertEquals(org.springframework.transaction.annotation.Isolation.READ_COMMITTED,transaction.isolation());
    }
    ClubAppService app(JdbcTemplate db) {
        return new ClubAppService(db,mock(ClubAuthService.class),mock(ClubBusinessService.class),
                mock(ClubCatalogService.class),mock(ClubTeenPolicyService.class),
                mock(ClubMiniProgramCodeService.class),mock(ClubIdentityCryptoService.class),mock(ClubWechatPayService.class));
    }
    @Test void fractionalCentsAreRejectedBeforeAnyWalletMutation() {
        JdbcTemplate db=mock(JdbcTemplate.class);
        when(db.queryForObject(contains("user_type='player'"),eq(Integer.class),eq(7L))).thenReturn(1);
        Map<String,Object> input=new HashMap<>();input.put("idempotencyKey","audit-fractional");
        for(String amount:Arrays.asList("0.005","1.001","10000000000","0","-0.01","1E1000000","1E-1000000")) {
            input.put("amount",amount);
            assertThrows(ServiceException.class,()->app(db).withdraw(7L,input),amount);
        }
        verify(db,never()).update(anyString(),any(Object[].class));
        verify(db,never()).queryForList(contains("for update"),anyLong());
    }
    @Test void refreshCannotReuseTokenConsumedByAnotherRequest() {
        JdbcTemplate db=mock(JdbcTemplate.class);
        ClubAuthService auth=spy(new ClubAuthService(db,new BCryptPasswordEncoder()));
        Map<String,Object> row=new HashMap<>();row.put("id",3L);row.put("user_id",7L);row.put("status","active");row.put("token_type","refresh");
        when(db.queryForList(contains("select t.id,t.user_id,t.token_type,u.status"),anyString())).thenReturn(Collections.singletonList(row));
        when(db.update("update club_user_token set revoked_at=now() where id=? and revoked_at is null and expires_at>now()",3L)).thenReturn(1,0);
        doReturn(Collections.singletonMap("id",7L)).when(auth).userView(7L);
        Map<String,Object> input=Collections.singletonMap("refreshToken","audit-token");
        assertNotNull(auth.refresh(input).get("accessToken"));
        assertThrows(ServiceException.class,()->auth.refresh(input));
        verify(db,times(1)).update(contains("insert into club_user_token"),eq(7L),eq("access"),anyString(),any(java.sql.Timestamp.class));
    }
    @Test void messagesUsePerRecordOwnershipAndNeverLinkOtherUsersRecords() {
        JdbcTemplate db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:messages"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("create table club_message(id bigint,user_id bigint,message_type varchar(32),title varchar(32),content varchar(32),reference_type varchar(32),reference_id bigint,is_read int,created_at timestamp)");
        db.execute("create table club_order(id bigint,user_id bigint,provider_type varchar(20),provider_user_id bigint)");
        db.execute("create table club_aftersale(id bigint,user_id bigint,provider_user_id bigint)");
        db.execute("create table club_recharge_order(id bigint,user_id bigint)");
        db.execute("create table club_withdrawal(id bigint,user_id bigint)");
        db.execute("insert into club_order values(1,7,'player',8),(2,8,'player',7),(3,8,'player',9)");
        db.execute("insert into club_aftersale values(4,7,8),(5,8,7)");
        db.execute("insert into club_recharge_order values(6,7),(7,8)");
        db.execute("insert into club_withdrawal values(8,7)");
        String[] types={"order","order","order","aftersale","aftersale","recharge","recharge","withdrawal","identity","application","system"};
        String[] expected={"/pages/order/detail?id=1","/pages/workbench/order-detail?id=2","","/pages/aftersale/detail?id=4","/pages/aftersale/detail?id=5&workbench=1","/pages/wallet/recharge-detail?id=6","","/pages/wallet/withdrawal-detail?id=8","/pages/settings/identity","/pages/apply/index",""};
        for(int i=0;i<types.length;i++) db.update("insert into club_message values(?,7,'system','','',?,?,0,now())",i+1,types[i],i+1);
        db.execute("insert into club_message values(99,8,'system','','','order',1,0,now())");
        List<Map<String,Object>> messages=app(db).messages(7L);
        assertEquals(expected.length,messages.size());
        for(Map<String,Object> m:messages) assertEquals(expected[((Number)m.get("id")).intValue()-1],m.get("targetUrl"));
    }
}
