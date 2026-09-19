# 支付部署与验收 · 2026-09-19

> 2026-09-20 更新：本页旧结论须结合 [商用审查记录](COMMERCIAL_AUDIT_20260920.md)。旧库还需核对新增的过期订单、原生微信退款重试字段及菜单迁移。普通虚拟支付和原生微信退款现在均先持久化退款意图，再由后台任务提交及查单；“审核通过”不等于“到账”。Apple 已全额退款但本地尚未收到付款成功的订单，也增加了仅释放预占资源的补偿分支。历史已发生但无本地退款意图的异常仍需人工对账。

本次修复的是源码及部署模板，没有向现网发起付款、退款，没有修改现网数据库或微信后台。三个独立仓库是交付入口，旧 sjz-club 总仓库不是最新版本。

## 1. 数据库升级顺序

新空库：依次导入 `database/001-schema.sql`、`database/002-seed.sql`，再按 README 激活管理员。001 已合并两列核对时间及索引，仍是 74 张表。

已有数据库（包含 20260912 虚拟支付表，后端版本 3225992 或兼容表结构）：

1. 先备份数据库和现用后端文件，停止服务；确认目标不是测试人员误选的其他业务库。
2. 用 `SHOW COLUMNS FROM club_virtual_payment` 检查当前表。尚无 `payment_checked_at` 和 `refund_checked_at` 时，执行一次 `database/migrations/20260919_virtual_payment_reconciliation.sql`。
3. 校验两列为可空 DATETIME(6)，且有 `idx_virtual_payment_check`、`idx_virtual_refund_check` 两个索引。
4. 更新后端、环境配置并启动；观察自动核对任务是否报缺列、验签、环境或金额不匹配。不要修改数据库状态来伪造支付成功。

迁移不删除订单或资金数据。重复执行会因列已存在失败，不是可反复导入的脚本。新库不要执行历史迁移。回退程序时可保留新增的可空列和索引，通常无需删列；恢复数据库备份会覆盖备份后的业务，必须另行评估。

## 2. 回调地址和开关

`PUBLIC_API_BASE` 填后端公开 HTTPS 根地址，例如 `https://api.example.com`，不带 `/app`，也不带末尾斜杠。

| 用途 | 地址 |
| --- | --- |
| 普通微信支付结果 | `https://api.example.com/app/pay/wechat/notify` |
| 普通微信退款结果 | `https://api.example.com/app/pay/wechat/refund-notify` |
| 虚拟支付消息/发货/退款通知 | `https://api.example.com/app/wechat/message` |

旧环境如果显式填写了错误的 `WECHAT_PAY_NOTIFY_URL` / `WECHAT_PAY_REFUND_NOTIFY_URL`，必须修正实际环境文件；仅更新代码默认值不会覆盖已有环境变量。旧订单可能仍使用创建时的回调地址，需核对历史未决订单，不能假定它们自动改址。

虚拟支付真实业务订单需要同时设置 `PAYMENT_MODE=wechat`、`VIRTUAL_PAYMENT_ENABLED=true`、`VIRTUAL_PAYMENT_ENV=0`；不要将沙箱付款记入正式资金。配置属于同一小程序的 WECHAT_APP_ID、WECHAT_APP_SECRET、VIRTUAL_PAYMENT_OFFER_ID、VIRTUAL_PAYMENT_LIVE_APP_KEY。普通微信支付及提现如使用商户证书，还要单独配置相应商户号、私钥及平台公钥，虚拟支付 AppKey 不能替代它们。

微信登录/绑定使用 `WECHAT_LOGIN_MODE=real`（不是 `wechat`）。账号密码登录不应因此变成必须绑定微信；支付、提现仍检查绑定。手机号授权还需当前小程序具备对应接口权限。

微信后台消息推送选“安全模式 + JSON”，URL 为上表第三项，Token、EncodingAESKey 必须和后端 WECHAT_MESSAGE_TOKEN、WECHAT_MESSAGE_AES_KEY 一致。先通过 URL 验证，再确认消息回调能验签、解密、正常应答。凭据只放服务器私密环境文件，不提交 Git。

新空库没有商品或微信道具映射：需要录入服务和 SKU，在微信后台发布对应商品，并正确配置 `club_virtual_goods` 中 environment=0 的 goods_id、price_fen、published。仅有 AppKey 不能直接售卖。Apple IAP 还需在当前小程序后台开通，不能由复制代码代替。

## 3. 本次退款及核对行为

- 普通微信回调路径与控制器统一，保留原有签名、金额、原支付流水校验。
- Apple 退款由用户向 Apple 申请。应用不会调用普通退款接口代替 Apple 审批。
- 无本地售后申请的 Apple 全额退款，也会在微信服务端查询确认后建立外部退款售后记录，复用现有库存、结算冲正和服务方追偿规则；不会伪造管理员审核批准。
- 有本地售后申请但漏收到回调，以及用户直接向 Apple 退款的订单，均由定时任务查原支付订单补偿：必须是现网、苹果支付、退款完成状态 5/8、原实付金额一致、left_fee=0、原交易号与本地一致。部分退款或信息不一致会拒绝自动冲正，需要人工核账。
- 只有原单查询证据时不伪造退款交易号；以后收到并核验合法退款通知，可补存真实退款号。重复回调/轮询不会重复冲账。
- 每批最多核对 100 笔，按持久化的最近尝试时间轮转；检查失败也记录尝试时间。旧失败单不再永久堵住后续订单，重启也保留顺序。核对不是实时到账保证，仍应监控错误日志和异常售后。

实现依据：[微信官方虚拟支付说明](https://developers.weixin.qq.com/miniprogram/dev/platform-capabilities/business-capabilities/virtual-payment.html)、[query_order 字段及状态定义](https://developers.weixin.qq.com/miniprogram/dev/server/API/VirtualPayment/api_query_order)。

## 4. 同事部署后的验收

先用独立数据库及安全测试配置启动，检查管理员登录、角色权限、账号登录、商品/服务人员、下单和联系方式/实名校验、上传文件、订单状态及售后页面。再在微信合法域名与当前 AppID 下联调登录、支付调起、回调和查单。

必须区分测试结论：自动测试使用模拟微信响应和隔离内存数据库，不证明真实微信/Apple 渠道或新服务器已验收。真实付款/退款测试本次按用户要求没有执行；运营方此前的测试结果也不能代替新环境配置核验。

仓库包含完整初始化表结构和必要系统种子配置，不包含现网客户、订单、资金、身份资料、上传图片/语音、数据库备份或真实密钥。若要求与现网完全一致，须另外通过安全渠道交付一致性数据备份、上传目录和匹配的私密配置，不能只拉 Git，也不要把上述材料放进公开或私有代码仓库。
