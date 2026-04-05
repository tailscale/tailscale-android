# 经验教训记录

> 每次遇到问题或完成重要改动后在此记录，必须附上 git commitID。
> 仅记录重要 bug 修复或重大变更；不要记录初始化、脚手架生成、纯文档补全等噪音内容。

---

## 记录模板

## [YYYY-MM-DD] 问题标题
- **问题**: 描述问题现象、影响范围和触发原因
- **解决**: 描述修复方式和关键改动
- **避免**: 描述以后如何避免再次出现
- **commitID**: `待填写：实际 commit hash`

## [2026-04-05] Android 侧 SOCKS MVP 自动化验证闭环
- **问题**: 初版 Android 侧 SOCKS5 MVP 虽然已能通过 adb 触发测试，但存在测试入口暴露面过大、多个场景共用同一 WorkManager unique work 导致结果互相覆盖、以及脚本无法通过退出码做机器判定的问题，影响自动化联调的稳定性与安全边界。
- **解决**: 新增 `AdbTcpHttpTestContract` 与 `AdbTcpHttpTestWorker`，通过 `IPNReceiver` 提供 debug-only 的 `RUN_NETWORK_TEST` 入口，按 `requestId` 隔离 unique work，限制 `timeoutMs <= 10_000`，补齐 `tsocks-test-build/install/trigger/logs/pass-fail/run-all.sh` 脚本链路，追加中文开发说明，并完成 `DIRECT`、`TAILSCALE_NORMAL`、`TAILNET_SOCKS` 三类路径的真机 adb 验证。
- **避免**: 后续新增 adb/debug harness 时，应同步设计入口收口、并发隔离、稳定日志字段与非 0 退出码，先把“可自动判定”和“不会误暴露到 release”作为基础约束，而不是事后补救。
- **commitID**: `fe770e031305534946c1ebc1f7516db66b5dadbc`
