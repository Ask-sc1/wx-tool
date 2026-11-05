<p align="center">
  <a href="https://github.com/Ask-sc1/wx-tool">
    <img src="image/logo.png" alt="wx-tool" width="45%">
  </a>
</p>

<h1 align="center">wx-tool</h1>

<p align="center">
  Windows 微信 4.x 本地聊天记录备份与管理工具
</p>

<p align="center">
  <a href="https://github.com/Ask-sc1/wx-tool">
    <img src="https://img.shields.io/github/stars/Ask-sc1/wx-tool?style=flat-square" alt="GitHub Stars">
  </a>
  <a href="https://github.com/Ask-sc1/wx-tool/fork">
    <img src="https://img.shields.io/github/forks/Ask-sc1/wx-tool?style=flat-square" alt="GitHub Forks">
  </a>
  <a href="https://github.com/Ask-sc1/wx-tool/issues">
    <img src="https://img.shields.io/github/issues/Ask-sc1/wx-tool?style=flat-square" alt="GitHub Issues">
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/github/license/Ask-sc1/wx-tool?style=flat-square" alt="License">
  </a>
</p>

## 简介

`wx-tool` 是一个运行在 Windows 上的微信本地数据备份与浏览工具。它会读取本机已经登录的桌面微信数据，在本地完成数据库解密，并提供会话、联系人、群聊、消息、朋友圈、统计和导出等功能。

本项目当前重点支持 **微信 4.x**，已使用 Windows 微信 `4.1.15.56` 进行实际验证。

> 本项目只应处理本人账号或已获得明确授权的数据。请勿将导出的聊天记录、联系人、图片或数据库密钥提供给他人。

## 功能

- 读取当前登录微信的账号、昵称、头像和本地数据目录。
- 解密微信 4.x 使用的 SQLCipher 4 数据库。
- 自动读取当前微信周期的 WAL 增量数据。
- 浏览会话、联系人、普通群聊和企业微信群聊。
- 浏览单聊与群聊消息，支持跨多个 `message_N.db` 合并。
- 查看群公告、群成员和群聊详情。
- 浏览朋友圈和最近搜索关键字。
- 展示联系人、群聊、今日收发消息、近 15 天趋势、消息类型分布和互动联系人。
- 导出联系人、群聊、聊天记录和找回联系人数据。
- 通过微信本地全文索引查找已失效或已删除的联系人记录。

## 兼容性

| 项目 | 当前状态 |
| --- | --- |
| Windows 10 / 11 | 支持 |
| 微信 4.1.15.56 | 已实际验证 |
| 微信 4.1.11+ 密钥格式 | 支持 `Config.Cipher` XOR 掩码提取 |
| 微信 3.x | 保留旧版偏移量解析路径，最高验证到 `3.9.12.51` |
| JDK | 必须使用 JDK 11 |
| macOS / Linux | 不支持，工具依赖 Windows 进程和内存接口 |

微信升级后可能改变进程结构、内存布局、数据库结构或 XOR 掩码。遇到新版微信无法识别时，请先提交包含微信版本号和错误信息的 Issue。

## 工作原理

1. 定位 `Weixin.exe` 主进程和当前账号的 `xwechat_files` 目录。
2. 从微信 4.1.11+ 的 WCDB `Config.Cipher` 对象读取经过 XOR 掩码的数据库密钥。
3. 使用每个数据库第 1 页的 salt、HMAC-SHA512 和第 1 页数据校验候选密钥。
4. 按 SQLCipher 4 的 `AES-256-CBC / page 4096 / reserve 80` 参数解密数据库。
5. 将当前 WAL 周期的已提交页面写入解密副本。
6. 仅从项目的 `data/db/<wxid>/` 目录读取和展示数据。

微信原始数据不会被修改。解密副本、导出文件和个人配置均已加入 `.gitignore`，不会提交到 Git 仓库。

## 环境要求

- Windows 10 或 Windows 11，64 位
- JDK 11，推荐最新版 Microsoft OpenJDK 11 或 Eclipse Temurin 11
- Node.js 18+
- Maven 3.9+
- 已在桌面微信中登录当前账号

JDK 11 下载：[Eclipse Temurin 11](https://adoptium.net/temurin/releases/?version=11)

Node.js 下载：[Node.js 官方网站](https://nodejs.org/)

Maven 下载：[Apache Maven](https://maven.apache.org/download.cgi)

微信下载：[微信 Windows 版](https://pc.weixin.qq.com/)

## 设置 JDK 11

请先确认 `java` 和 Maven 都使用 JDK 11：

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-11"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
mvn -version
```

Windows 用户环境变量中应设置：

```text
JAVA_HOME=C:\path\to\jdk-11
```

如果 IntelliJ IDEA 中仍显示 Java 23 或其他版本，请在以下位置改成 JDK 11：

```text
File -> Project Structure -> Project -> SDK
File -> Project Structure -> Project -> Language level = 11
Settings -> Build, Execution, Deployment -> Build Tools -> Maven -> Runner -> JRE = JDK 11
```

## 从源码运行

### 1. 克隆仓库

```powershell
git clone https://github.com/Ask-sc1/wx-tool.git
cd wx-tool
```

### 2. 构建后端

```powershell
mvn clean package -DskipTests
```

### 3. 构建前端

```powershell
cd wx-dump-ui
npm ci
npm run build
cd ..
```

### 4. 启动完整应用

```powershell
java -jar wx-dump-admin/target/wx-dump-admin.jar
```

访问 [http://localhost:8080](http://localhost:8080)。

### 前端开发模式

后端运行在 `8080` 端口时，可以单独启动前端开发服务器：

```powershell
cd wx-dump-ui
npm run start
```

开发页面地址：[http://localhost:8000](http://localhost:8000)

## 生成发布包

先确保 `wx-dump-ui/dist` 已构建，然后执行：

```powershell
mvn clean package -Prelease
```

发布包生成位置：

```text
wx-dump-dist/target/wx-dump-4j-bin.tar.gz
```

解压后进入 `bin` 目录，双击 `start.bat`。启动脚本会优先使用 `JAVA_HOME`，并在检测到非 JDK 11 环境时停止启动。

## 使用流程

1. 打开并登录 Windows 桌面微信。
2. 保持微信处于登录状态。
3. 启动 `wx-tool` 并访问 [http://localhost:8080](http://localhost:8080)。
4. 进入“解密工具”页面。
5. 点击“解密”，等待数据库处理完成。
6. 刷新页面，进入会话、联系人、群聊、消息或朋友圈页面。

本项目不提供微信扫码登录功能。它读取的是桌面微信中已经登录的账号。

## 常见问题

### 页面显示“微信数据库未就绪”

先进入“解密工具”页面完成一次解密。若仍失败，请确认：

- 桌面微信已经登录。
- 微信账号与所选数据目录一致。
- 当前微信进程名为 `Weixin.exe`。
- 后端能够读取微信进程内存。

### 提示当前微信版本不支持

请记录 `Weixin.exe` 的文件版本号并提交 Issue。微信更新后可能需要重新适配：

- 进程名与 DLL 名称
- `Config.Cipher` 内存结构
- XOR 掩码
- 数据表结构
- 媒体文件格式

### Maven 构建出现 Lombok 或 getter/setter 错误

通常表示当前 Maven 没有使用 JDK 11。执行：

```powershell
mvn -version
```

确认输出中的 Java version 为 `11.x`。

### 端口被占用

默认端口为 `8080`。如果端口已被占用，可以指定其他端口：

```powershell
java -jar wx-dump-admin/target/wx-dump-admin.jar --server.port=8081
```

## 数据安全

- 解密仅在本机进行，不上传微信数据库。
- 解密结果位于 `data/db/<wxid>/`。
- 导出文件位于 `data/export/`。
- `data/` 已被 `.gitignore` 排除。
- 数据库密钥不会写入 Git 仓库。
- 不再使用程序时，请删除 `data/` 中的解密副本和导出文件。

## 已知限制

- 微信 4.x 使用 V2 媒体加密，部分图片、视频和语音可能无法直接显示或导出。
- 微信升级后，固定 XOR 掩码或内部内存结构可能失效。
- 群聊中部分已退出成员的显示名可能只能显示 wxid。
- 本项目不会绕过微信登录流程，也不支持远程登录他人账号。

## 项目结构

```text
wx-dump-admin/   Spring Boot 后端、微信进程读取和数据库解密
wx-dump-ui/      Umi + React + Ant Design Pro 前端
wx-dump-dist/    发布包组装配置
docs/            解密和展示说明
image/           README 图片与界面截图
```

## 主要技术版本

| 技术 | 版本 |
| --- | --- |
| JDK | 11 |
| Spring Boot | 2.7.18 |
| SQLite JDBC | 3.50.3.0 |
| Lombok | 1.18.38 |
| MyBatis-Plus | 3.5.7 |
| Dynamic Datasource | 4.3.1 |
| Druid | 1.2.25 |
| JNA | 5.17.0 |
| Hutool | 5.8.36 |
| MapStruct | 1.6.3 |
| Apache Commons Compress | 1.27.1 |
| Zstd JNI | 1.5.7-4 |
| React | 18.2 |
| Ant Design | 5.2 |
| Umi | 4.x |

## 界面截图

### 仪表盘

![仪表盘](image/screenshot/dashboard.png)

### 会话

![会话](image/screenshot/session.png)

### 联系人

![联系人](image/screenshot/contact.png)

### 聊天记录

![聊天记录](image/screenshot/chat.png)

### 群聊

![群聊](image/screenshot/chatroom.png)

### 朋友圈

![朋友圈](image/screenshot/feeds.png)

### 数据库解密

![数据库解密](image/screenshot/database.png)

## 参与贡献

欢迎提交 Issue 或 Pull Request：

- [提交 Issue](https://github.com/Ask-sc1/wx-tool/issues)
- [Fork 仓库](https://github.com/Ask-sc1/wx-tool/fork)
- [查看 Pull Requests](https://github.com/Ask-sc1/wx-tool/pulls)

提交问题时请不要附带真实聊天记录、数据库密钥、账号密码或其他敏感信息。

## 许可证与来源

本项目基于原有的 `wx-dump-4j` 开源项目继续开发和适配，遵循当前仓库中的 [LICENSE](LICENSE)。

本仓库是针对微信 4.x 和 JDK 11 维护的修改版本。上游项目的作者、站点、赞助二维码和统计配置不代表本仓库。

## 免责声明

本项目仅供个人数据备份、技术研究和学习使用。使用者应确保对相关账号和本地数据拥有合法访问权限，并遵守当地法律法规及微信软件许可协议。

严禁将本项目用于未经授权的数据访问、隐私侵犯、账号控制、数据贩卖或其他违法用途。因使用或滥用本项目造成的任何损失，由使用者自行承担。
