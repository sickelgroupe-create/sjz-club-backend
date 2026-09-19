-- Existing installations only. New databases use 001-schema.sql and corrected 002-seed.sql.
-- Back up sys_menu/sys_role_menu; run during an administration maintenance window.
-- No business records, users, or existing role-menu assignments are deleted.
-- Existing permission codes are preserved separately before correcting their menu placement.
SET NAMES utf8mb4;
START TRANSACTION;
DROP TEMPORARY TABLE IF EXISTS sjz_menu_permission_before;
CREATE TEMPORARY TABLE sjz_menu_permission_before AS
SELECT DISTINCT rm.role_id,m.perms
FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id
WHERE m.menu_id IN (2109,2112,2118,2122,2123,2125,2207,2209) AND m.perms IS NOT NULL AND m.perms<>'';

UPDATE sys_menu SET perms='club:withdrawal:list',update_by='system',update_time=NOW() WHERE menu_id=2109 AND perms='club:finance:list';
UPDATE sys_menu SET perms='club:coupon:list',update_by='system',update_time=NOW() WHERE menu_id=2112 AND perms='club:config:list';
UPDATE sys_menu SET perms='club:wallet:list',update_by='system',update_time=NOW() WHERE menu_id=2118 AND perms='club:coupon:list';
UPDATE sys_menu SET perms='club:finance-config:list',update_by='system',update_time=NOW() WHERE menu_id=2122 AND perms='club:customer-service:list';
UPDATE sys_menu SET perms='club:finance:list',update_by='system',update_time=NOW() WHERE menu_id=2123 AND perms='club:withdrawal:list';
UPDATE sys_menu SET perms='club:customer-service:list',update_by='system',update_time=NOW() WHERE menu_id=2125 AND perms='club:finance-config:list';
UPDATE sys_menu SET perms='club:withdrawal:review',update_by='system',update_time=NOW() WHERE menu_id=2207 AND perms='club:finance:review';
UPDATE sys_menu SET perms='club:coupon:edit',update_by='system',update_time=NOW() WHERE menu_id=2209 AND perms='club:config:edit';
UPDATE sys_menu SET parent_id=2109,update_by='system',update_time=NOW() WHERE menu_id=2217 AND parent_id=2123 AND perms='club:withdrawal:review';
UPDATE sys_menu SET parent_id=2125,update_by='system',update_time=NOW() WHERE menu_id=2221 AND parent_id=2122 AND perms='club:customer-service:edit';

-- Preserve old custom-role grants, including now-unused legacy permission codes.
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,menu_type,visible,status,perms,create_by,create_time,remark)
SELECT CONCAT('保留权限 ',old.perms),2100,99,'','F','1','0',old.perms,'system',NOW(),'迁移前已授予的权限，保留以避免撤销自定义角色授权'
FROM (SELECT DISTINCT perms FROM sjz_menu_permission_before) old
WHERE NOT EXISTS (SELECT 1 FROM sys_menu current_menu WHERE current_menu.perms=old.perms);
INSERT IGNORE INTO sys_role_menu (role_id,menu_id)
SELECT old.role_id,MIN(m.menu_id) FROM sjz_menu_permission_before old JOIN sys_menu m ON m.perms=old.perms GROUP BY old.role_id,old.perms;

-- Add missing business actions. IDs are allocated by MySQL to avoid custom-menu collisions.
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '内容停用及删除',2110,8,'',NULL,'F','club:content:disable','#',1,1,'0','0','system',NOW(),'20260919补齐业务操作权限' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='club:content:disable');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '优惠券删除',2112,8,'',NULL,'F','club:coupon:disable','#',1,1,'0','0','system',NOW(),'20260919补齐业务操作权限' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='club:coupon:disable');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '充值档位停用',2124,8,'',NULL,'F','club:finance-config:disable','#',1,1,'0','0','system',NOW(),'20260919补齐业务操作权限' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='club:finance-config:disable');
INSERT IGNORE INTO sys_role_menu (role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.perms IN ('club:content:disable','club:coupon:disable','club:finance-config:disable')
WHERE r.role_id=105 AND r.role_key='club_admin';

-- Role 103 explicitly performs identity/application reviews. No other role gains plaintext access.
INSERT IGNORE INTO sys_role_menu (role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.menu_id=2226 AND m.perms='club:identity:detail'
WHERE r.role_id=103 AND r.role_key='club_reviewer'
AND EXISTS (SELECT 1 FROM sys_role_menu rm JOIN sys_menu p ON p.menu_id=rm.menu_id WHERE rm.role_id=r.role_id AND p.perms='club:identity:review')
AND EXISTS (SELECT 1 FROM sys_role_menu rm JOIN sys_menu p ON p.menu_id=rm.menu_id WHERE rm.role_id=r.role_id AND p.perms='club:application:review');

-- Minimal employee administration: no sys_role_menu grants are created for these menus.
-- RuoYi's existing user_id=1 super-administrator can access them; all others require explicit grants.
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '系统管理',0,9,'system',NULL,'M','','system',1,1,'0','0','system',NOW(),'员工与权限管理，默认仅超级管理员' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id=0 AND path='system');
SET @sjz_system_menu_id=(SELECT MIN(menu_id) FROM sys_menu WHERE parent_id=0 AND path='system');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '员工账号',@sjz_system_menu_id,1,'user','system/user/index','C','system:user:list','user',1,1,'0','0','system',NOW(),'默认仅超级管理员；其他角色需明确授权' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component='system/user/index');
SET @sjz_user_menu_id=(SELECT MIN(menu_id) FROM sys_menu WHERE component='system/user/index');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '角色权限',@sjz_system_menu_id,2,'role','system/role/index','C','system:role:list','peoples',1,1,'0','0','system',NOW(),'默认仅超级管理员；其他角色需明确授权' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component='system/role/index');
SET @sjz_role_menu_id=(SELECT MIN(menu_id) FROM sys_menu WHERE component='system/role/index');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '菜单权限',@sjz_system_menu_id,3,'menu','system/menu/index','C','system:menu:list','tree-table',1,1,'0','0','system',NOW(),'默认仅超级管理员；其他角色需明确授权' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE component='system/menu/index');
SET @sjz_menu_menu_id=(SELECT MIN(menu_id) FROM sys_menu WHERE component='system/menu/index');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '员工查询',@sjz_user_menu_id,2311,'',NULL,'F','system:user:query','#',1,1,'0','0','system',NOW(),'仅显式授权，不向业务角色分配' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:user:query');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '员工新增',@sjz_user_menu_id,2312,'',NULL,'F','system:user:add','#',1,1,'0','0','system',NOW(),'仅显式授权，不向业务角色分配' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:user:add');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '员工修改',@sjz_user_menu_id,2313,'',NULL,'F','system:user:edit','#',1,1,'0','0','system',NOW(),'仅显式授权，不向业务角色分配' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:user:edit');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '员工删除',@sjz_user_menu_id,2314,'',NULL,'F','system:user:remove','#',1,1,'0','0','system',NOW(),'仅显式授权，不向业务角色分配' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:user:remove');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '员工导出',@sjz_user_menu_id,2315,'',NULL,'F','system:user:export','#',1,1,'0','0','system',NOW(),'仅显式授权，不向业务角色分配' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:user:export');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '员工导入',@sjz_user_menu_id,2316,'',NULL,'F','system:user:import','#',1,1,'0','0','system',NOW(),'仅显式授权，不向业务角色分配' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:user:import');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '员工重置密码',@sjz_user_menu_id,2317,'',NULL,'F','system:user:resetPwd','#',1,1,'0','0','system',NOW(),'仅显式授权，不向业务角色分配' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:user:resetPwd');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '角色查询',@sjz_role_menu_id,2321,'',NULL,'F','system:role:query','#',1,1,'0','0','system',NOW(),'仅显式授权，不向业务角色分配' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:role:query');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '角色新增',@sjz_role_menu_id,2322,'',NULL,'F','system:role:add','#',1,1,'0','0','system',NOW(),'仅显式授权，不向业务角色分配' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:role:add');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '角色修改',@sjz_role_menu_id,2323,'',NULL,'F','system:role:edit','#',1,1,'0','0','system',NOW(),'仅显式授权，不向业务角色分配' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:role:edit');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '角色删除',@sjz_role_menu_id,2324,'',NULL,'F','system:role:remove','#',1,1,'0','0','system',NOW(),'仅显式授权，不向业务角色分配' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:role:remove');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '角色导出',@sjz_role_menu_id,2325,'',NULL,'F','system:role:export','#',1,1,'0','0','system',NOW(),'仅显式授权，不向业务角色分配' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:role:export');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '菜单查询',@sjz_menu_menu_id,2331,'',NULL,'F','system:menu:query','#',1,1,'0','0','system',NOW(),'仅显式授权，不向业务角色分配' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:menu:query');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '菜单新增',@sjz_menu_menu_id,2332,'',NULL,'F','system:menu:add','#',1,1,'0','0','system',NOW(),'仅显式授权，不向业务角色分配' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:menu:add');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '菜单修改',@sjz_menu_menu_id,2333,'',NULL,'F','system:menu:edit','#',1,1,'0','0','system',NOW(),'仅显式授权，不向业务角色分配' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:menu:edit');
INSERT INTO sys_menu (menu_name,parent_id,order_num,path,component,menu_type,perms,icon,is_frame,is_cache,visible,status,create_by,create_time,remark) SELECT '菜单删除',@sjz_menu_menu_id,2334,'',NULL,'F','system:menu:remove','#',1,1,'0','0','system',NOW(),'仅显式授权，不向业务角色分配' WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='system:menu:remove');

COMMIT;
DROP TEMPORARY TABLE IF EXISTS sjz_menu_permission_before;
-- Have affected staff sign out and back in so cached permissions and routes are refreshed.
