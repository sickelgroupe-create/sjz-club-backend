# 新服务器部署入口（2026-09-19 目录更新）

本目录保存服务器配置模板和数据库导入工具。工程仅保留三个独立仓库文件夹：

- sjz-club-app：小程序与 H5 用户端。
- sjz-club-backend：后端，本目录位于其中；SQL 统一位于后端根目录 database/。
- sjz-club-admin：管理后台。

不再依赖外层 deployment、若依或 github-split 目录。本文的命令与 database/ 路径均以 sjz-club-backend 根目录为起点。

三个仓库可独立构建；统一克隆及分支协作方式见 [CONTRIBUTING.md](../CONTRIBUTING.md)。服务器模板以本 deploy/ 目录为准，数据库以 [database/README.md](../database/README.md) 为准。

## 1. 数据库：新环境只导入两个文件

database/001-schema.sql：75 张表的最终结构，已包含历史增量字段、索引、售后审核幂等历史、授权记录表、支付表及框架补充表。
database/002-seed.sql：系统配置、85 个菜单节点、角色权限、字典、类别、首页入口与协议模板。
数据库只保留一个禁用的 admin 初始账号，无默认可用密码；不含真实用户、订单、支付记录、钱包、身份资料或演示商品。

要求 MySQL 8.0 或以上。本次在隔离的 MySQL 8.4.11 中成功导入；旧版本没有逐一测试。
由数据库管理员创建一个空的 utf8mb4 数据库及专用账号，授予该库所需权限，不要使用现网数据库名进行试验。

安装 Python 3 和 MySQL 客户端后，在后端根目录运行：

    python deploy/tools/init_database.py --host 127.0.0.1 --port 3306 --user sjz_app --database sjz_club

脚本拒绝向已有表的数据库导入，需要输入数据库密码并确认目标。也可通过数据库管理工具依次执行 001、002；必须保证目标是空库。

不要再执行 database/migrations/ 下的历史迁移，也不要把 database/schema-reference/ 当作初始化入口；最终结构已经包含变更。database/local-private/ 如存在，属于本机保留的原始 SQL，可能有测试夹具、回滚和清理操作，已排除 Git，不能一键执行。

## 2. 设置自己的管理账号密码

    python -m pip install bcrypt
    python deploy/tools/create-admin-sql.py

程序交互输入新密码，在本地产生 database/003-admin.local.sql。将该文件导入刚建立的数据库，确认 activated_admin_count=1。
然后使用 admin 和自己刚设置的密码登录后台。此本地 SQL 含密码散列，已加入忽略规则，不应上传、发到群里或用于重置已有系统。

## 3. 后端及依赖

需要 JDK 8（pom.xml 源码目标为 Java 8）、Maven 3、MySQL 8、Redis、Nginx。Redis 单独配置认证，仅内网监听；本包不包含数据库/Redis 安装程序。

后端根目录运行 `mvn clean package`，执行自动测试并生成 ruoyi-admin/target/ruoyi-admin.jar。
不要以 `-DskipTests` 的构建结果作为业务验收；自动测试也不代替新环境联调。

将 jar 放到 /opt/sjz-club/app.jar。创建专用系统用户 sjz，以及其可读写的 /var/lib/sjz-club、/var/log/sjz-club；上传目录应为 /var/lib/sjz-club/uploads。
复制 deploy/settings.env.example 到仓库以外的 /etc/sjz-club/settings.env，填写数据库、Redis、随机令牌密钥、身份加密密钥、域名等真实配置。限制配置文件读取权限；不要提交到 Git。
后端不会自行加载这个文件：通过提供的 systemd 服务的 EnvironmentFile 加载。非 systemd 环境请在启动 Java 进程前自行设置这些环境变量。

按实际路径调整 sjz-club-api.service.example，安装为 systemd 服务后启动。服务默认监听 127.0.0.1:8082，经 Nginx 访问；不要开放数据库、Redis 和应用端口到公网。

## 4. 管理后台与小程序

管理后台操作见 [sjz-club-admin 部署说明](https://github.com/sickelgroupe-create/sjz-club-admin/blob/main/部署说明.md)；用户端见 [sjz-club-app 部署说明](https://github.com/sickelgroupe-create/sjz-club-app/blob/main/部署说明.md)。
nginx.conf.example 提供 API、后台及可选 H5 的三个域名配置，需替换域名、站点目录及 HTTPS 证书路径。证书要由同事在新服务器申请，不包含在源码内。
运行 nginx -t 通过后再启用。只部署微信小程序时可省略 H5 站点。

## 5. 微信与支付

默认关闭微信登录、手机号授权、普通支付和虚拟支付。启用前必须由运营方单独提供自己的 AppID、AppSecret、商户证书、虚拟支付 AppKey 等，并配置微信后台合法域名、消息回调和商品映射。
不要认为复制源码就完成了微信支付接入。本次没有做付款或退款，也没有更改任何线上配置。

详细的开关及三种回调地址见 [PAYMENT_DEPLOYMENT.md](PAYMENT_DEPLOYMENT.md)。本版还包括权限、订单过期、退款恢复、售后重审和钱包索引升级，完整清单见 [数据库说明](../database/README.md)。新空库的 001 已包含；已有数据库必须先核对并执行缺失的增量 SQL，再启动新后端，不能直接覆盖 001。

## 6. 空系统与现网迁移的区别

此初始化得到的是新的空业务系统，管理员需配置平台、客服、商品及服务人员；协议模板中的运营主体和联系方式需核对后发布。
如要一比一迁移现网，必须额外安全交付数据库一致性备份、上传图片/语音、加密密钥、证书及环境配置。不得把这些放到公开代码仓库。
迁移旧身份密文时必须保留原加密密钥；新生成密钥不能解密旧数据。

## 已验证与未验证

2026-09-16 已补做三端独立源码构建检查并修复安装/路径问题，详见 [BUILD_VALIDATION.md](BUILD_VALIDATION.md)。普通 npm ci、微信小程序/H5/管理后台构建及后端打包均已验证；线上业务验收仍需部署方完成。

历史验证：2026-09-16的74表/62菜单是当时快照，不是当前版本；2026-09-20首轮复核为74表/85菜单，本次售后重审新增第75张表。当前结构验证及测试统计以 [商用审查记录](COMMERCIAL_AUDIT_20260920.md) 为准，管理员仍须安全初始化。
未验证：同事的新服务器、域名、证书、Redis、三端运行及支付全流程。本文件不宣称新服务器已部署成功。

部署后用 [只读检查工具及真机验收表](OPERATIONS_ACCEPTANCE.md) 收集证据；不要将工具无报错理解为所有功能已通过商用验收。
