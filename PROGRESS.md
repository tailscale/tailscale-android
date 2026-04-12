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

## [2026-04-07] phase-3.1a 最小规则化 TUN 内 TCP 分流原型
- **问题**: phase-3 的真实数据面虽然已经能接管单个 `104.18.26.120:80` 出站 TCP flow，但规则匹配、`/32` route 注入和 gVisor proof-stack 拦截分别散落在 `tsocks.go`、`net.go`、`step0_tun.go`，导致“逻辑 allowlist”与“真实接管目标”割裂，无法稳定扩到多公网目标，也容易把 baseline 环境未就绪误判成回归。
- **解决**: 新增集中式 `tsocks_rules.go`，用最小 `IP:port` / `IP:*` 规则表统一驱动 route 选择、`TAILNET_SOCKS` 的 `/32` 注入和 step0 多目标拦截；补充 `hostHeader` 与 `previewOnly` 调试字段，扩展 `phase3-public-http-a/b`、`phase3-public-no-match`、`phase3-wrong-port-entered-tun`、`phase3-recursion-guard` 场景，并让日志稳定输出 `matchedRule`、`selectedRoute`、`injectedRoute`、`offloadDecision`、`recursionGuard` 等机判字段；同时在 `run-all` 中为 phase-1 baseline 增加就绪探测，避免把联调服务未准备好误报成代码失败。
- **避免**: 后续继续演进 tun 边界实验时，必须始终保持“规则源唯一、route 注入派生、数据面日志可机判”这三件事同步推进；同时要把 `/32` 注入只能精确到 IP 的语义边界写清楚，不要把 phase-3.1a 描述成真正的系统级 `IP:port` 透明分流。
- **commitID**: `待填写：实际 commit hash`

## [2026-04-12] phase-3.2 数据面可验证、可压测、可诊断工程原型
- **问题**: phase-3.1a 虽然功能可用，但缺少稳定 `flow_id`、并发压测、TCP 生命周期观测、资源回收观测和可重复 baseline 测试服务，导致“能跑通”与“能验证/能诊断”之间仍有明显断层。
- **解决**: 为 datapath 引入稳定 `flow_id`、`terminator_attach`/`socks_connect`/`relay_start`/`relay_end`/`conn_close` 等统一日志事件，补齐 `SYN/SYN-ACK/ACK/FIN/RST` 生命周期观测与 `activeRelays`/`goroutines`/`openFDs` 资源快照；新增动态 baseline 环境解析、host 侧 HTTP/TCP 测试服务、自启动与健康检查脚本，并补充 `phase32` 并发/错端口/lifecycle 验证脚本，完成真机 `PHASE32_PASS` 验证。
- **避免**: 后续继续演进 datapath 时，任何“规则或 relay 行为改动”都必须同步维护三件事：稳定 flow 关联字段、可重复 baseline 环境、以及并发与 lifecycle 的自动机判脚本；不要再依赖单流人工观察来判断稳定性。
- **commitID**: `待填写：实际 commit hash`
