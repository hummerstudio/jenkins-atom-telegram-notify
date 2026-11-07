# jenkins-atom-telegram-notify

用 bot 发一条消息。

Telegram 不是 webhook：token 在路径里、chat 在报文里，而且 token 或 chat 写错时它回 **HTTP 200 + `ok:false`**。所以判据是响应体里的 `ok`，不是状态码。日志里的路径把 token 打成 `bot***`。

`apiBase` 可改，集成测试用它指向本地假服务器。

```bash
mvn verify    # 编译、打包到 dist/telegram-notify/，再跑 src/test/java/atom/TelegramNotifyIT.java
```
