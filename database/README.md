# 数据库统一入口

小程序、管理后台通过 Java 后端使用同一个 MySQL 数据库，不需要三份数据库。

| 路径 | 用途 | 新环境是否执行 |
| --- | --- | --- |
| `001-schema.sql` | 已合并的 74 表最终结构 | 空库首先执行 |
| `002-seed.sql` | 菜单、权限、字典、配置和禁用的管理员 | 其次执行 |
| `003-admin.local.sql` | 自行生成的管理员密码初始化 | 按部署说明生成并执行，不上传 |
| `migrations/` | 保留的历史增量 SQL | 不重复执行，旧库升级须先比对版本 |
| `schema-reference/` | 早期导出的结构片段 | 仅参考，不用于初始化或自动升级 |
| `local-private/` | 本机原始历史 SQL，可能包含测试账号、数据与危险清理操作 | 不执行、不上传 Git |

完整步骤见 [部署说明](../deploy/README.md)。推荐在后端根目录运行 `python deploy/tools/init_database.py --user sjz_app --database sjz_club`；它只允许空数据库，按顺序读取本目录的 001、002，不会遍历执行其他 SQL。

`migration-order.json` 记录构建这份最终结构时使用的来源及顺序，不是待执行迁移清单。部分原始脚本含演示数据，未作为协作仓库初始化文件发布。

后续结构变更请新增 `migrations/YYYYMMDD_用途.sql`，在同一拉取请求中写明适用旧版本、备份要求、执行顺序和验证办法。不要只手动修改开发数据库而遗漏 SQL；也不要对已有业务库重新导入 001、002。

本次新增 `migrations/20260919_virtual_payment_reconciliation.sql`：仅用于已有 20260912 虚拟支付表且尚无 `payment_checked_at`、`refund_checked_at` 的旧库，先备份并停服务，再执行一次。新空库的 001 已合并，不要重复执行。操作和校验见 [支付升级说明](../deploy/PAYMENT_DEPLOYMENT.md)。

此目录不含现网客户、订单、支付、身份资料或数据库备份。需要现网迁移时，通过安全渠道另行交付，不能提交 Git。
