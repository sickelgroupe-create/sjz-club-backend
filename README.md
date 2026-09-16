# sjz-club-backend · 俱乐部后端

Java / Maven 多模块服务，为小程序和管理后台提供接口。

## 配套仓库

- 小程序：https://github.com/sickelgroupe-create/sjz-club-app
- 管理后台：https://github.com/sickelgroupe-create/sjz-club-admin
- 原三端源码快照：https://github.com/sickelgroupe-create/sjz-club

## 配置与构建

按 pom.xml 配置 Java / Maven 环境。参考 `ruoyi-admin/src/main/resources/application.yml` 和 `application-druid.yml` 的环境变量占位符，单独设置数据库、Redis、令牌密钥、微信及其他第三方配置。不得将实际凭据提交到仓库。

运行 `mvn clean package -DskipTests` 可打包；这条命令跳过测试，不代表业务验收通过。后端不会自动加载前端 .env 文件。

完整的新服务器初始化入口为 [deploy/README.md](deploy/README.md)。`deploy/database/001-schema.sql` 和 `002-seed.sql` 已在隔离空库中重新导入验证，包含 74 张表及菜单、角色权限、字典和必要配置；管理员需设置自己的密码后启用。环境变量、Nginx、服务模板和导入工具也在 deploy/。

`database-schema-reference/` 为旧结构参考，不再作为新环境入口。新环境不要重复执行 `sql/` 下的历史变更。生产密钥、现网业务数据、上传文件和 HTTPS 证书需另行配置或安全迁移。

小程序页面路由变更后，同步其 `generated/page-routes.json` 至本仓库 `ruoyi-admin/src/main/resources/club-page-routes.json`。小程序同步脚本支持显式设置 CLUB_BACKEND_ROOT。

## 范围

未包含生产配置、证书私钥、数据库备份、用户资料、旧 Git 历史与部署产物。虚拟支付与消息推送仍需结合部署环境核验；建立仓库不代表已上线或通过审核。原开源 LICENSE 保留，业务代码未新增开源授权。
