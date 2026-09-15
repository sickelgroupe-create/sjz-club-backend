package com.ruoyi.club.service;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.exception.ServiceException;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class ClubReapplicationTest {
 JdbcTemplate db=mock(JdbcTemplate.class);
 ClubRoleLifecycleService service=new ClubRoleLifecycleService(db);
 void fixture(){
  when(db.queryForList("select user_id from club_player_profile where id=?",18L)).thenReturn(Collections.singletonList(Collections.singletonMap("user_id",77L)));
  when(db.queryForList("select user_type from club_user where id=? for update",77L)).thenReturn(Collections.singletonList(Collections.singletonMap("user_type","player")));
  Map<String,Object> user=new HashMap<>();user.put("user_type","player");user.put("status","active");user.put("nickname","n");
  when(db.queryForList("select id,user_type,status,nickname from club_user where id=? for update",77L)).thenReturn(Collections.singletonList(user));
 }
 @Test void returnsWithoutDeletingIdentityOrMoney(){
  fixture();service.returnPlayerForReapplication(18L,"重新申请");
  verify(db).update("update club_user set user_type=? where id=?","user",77L);
  verify(db).update("update club_product set status='inactive' where bound_player_id=? and status='active'",18L);
  verify(db).update(contains("update club_application set status='rejected'"),eq("资格已撤销，可重新申请：重新申请"),eq(77L));
  verify(db,never()).update(matches("(?i).*(delete from|update club_wallet|update club_identity).*"),any(Object[].class));
 }
 @Test void activeOrderBlocksEveryWrite(){
  fixture();when(db.queryForObject(contains("from club_order"),eq(Integer.class),eq(77L))).thenReturn(1);
  assertThrows(ServiceException.class,()->service.returnPlayerForReapplication(18L,"重试"));
  verify(db,never()).update(anyString(),any(Object[].class));
 }
 @Test void repeatedReturnCannotRejectANewApplication(){
  fixture();when(db.queryForList("select user_type from club_user where id=? for update",77L)).thenReturn(Collections.singletonList(Collections.singletonMap("user_type","user")));
  assertThrows(ServiceException.class,()->service.returnPlayerForReapplication(18L,"重试"));
  verify(db,never()).update(anyString(),any(Object[].class));
 }
 @Test void reasonRequired(){assertThrows(ServiceException.class,()->service.returnPlayerForReapplication(18L," "));verifyNoInteractions(db);}
}
