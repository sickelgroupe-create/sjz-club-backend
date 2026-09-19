# 三端协作开发

## 获取代码

三个仓库放在同一父目录，不再需要旧的“若依”、导出目录或部署测试目录：

```text
工作目录/
  sjz-club-app/       微信小程序、H5 用户端
  sjz-club-backend/   Java 接口、database/ 全部 SQL、deploy/ 部署模板
  sjz-club-admin/     Vue 3 管理后台
```

```sh
git clone https://github.com/sickelgroupe-create/sjz-club-app.git
git clone https://github.com/sickelgroupe-create/sjz-club-backend.git
git clone https://github.com/sickelgroupe-create/sjz-club-admin.git
```

仓库如为私有，所有者需要先邀请同事成为协作者，同事接受邀请后使用自己的 GitHub 账号访问。不要共享密码、访问令牌或私钥。请以这三个仓库的 main 为协作入口，旧 sjz-club 合集仓库只是历史快照。

## 开发环境

1. 先阅读后端 `deploy/README.md`，建立自己的 MySQL 空库和 Redis；三端共用这一个后端数据库。
2. 数据库只按顺序导入后端 `database/001-schema.sql`、`002-seed.sql`，再按说明生成并执行自己的管理员激活 SQL；不要遍历运行历史 SQL。
3. 前端各自复制 `.env.example` 为所需环境文件，连接自己的后端，不连接现网做测试。实际凭据、客户数据、上传文件和证书不随 Git 提供。
4. 两个前端分别执行 `npm ci`；小程序构建需要 Windows PowerShell 和微信开发者工具。后端使用 JDK 8、Maven 3，详见各端部署说明。

## 提交约定

在自己负责的仓库操作；不要把另外两个仓库嵌入其中：

```sh
git switch main
git pull --ff-only
git switch -c feature/你的功能名
# 修改、检查后，仅添加与本任务有关的文件
git add 文件路径
git commit -m "说明本次修改"
git push -u origin feature/你的功能名
```

在 GitHub 创建拉取请求，由同事检查再合并。跨端变更在描述中关联各仓库的分支或拉取请求，说明接口兼容性和发布顺序。不要强制推送他人的分支。

- 数据库变更必须随代码提交到后端 `database/migrations/`，注明旧版本、备份要求和执行顺序；不要只在个人数据库中改表。
- 页面路由变化后，显式设置 `CLUB_BACKEND_ROOT` 为后端绝对路径，再在小程序运行 `npm run sync:routes`；检查并分别提交两端路由文件。
- 小程序保留 `check:ui-freeze`、`check:navigation`、`check:task-flows`、`check:repository` 等自检；管理后台保留 `check:voice`、`check:build-config`。这些是源码自检，不是可删除的运行产物。
- 不提交 `node_modules/`、`dist/`、`target/`、日志、临时数据库、真实环境文件、`*.local.sql` 或 `local-private/`。
- 初始化 SQL 只提供空业务系统。若需恢复现网数据，单独使用安全渠道交付，不要上传到任何代码仓库。
