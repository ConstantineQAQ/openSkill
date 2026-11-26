# openSkill

**把 Skill 变成可版本化、可组合、可执行、可追踪的工作流。**

[English](README.md) · [接口文档](docs/api.md) · [部署说明](docs/deployment.md) · [路线图](docs/roadmap.md)

openSkill 是本地优先、API 优先的 Java Skill 编排后端。支持导入 `SKILL.md` / ZIP、保存不可变版本、组合步骤、映射参数、执行和定时调度，并为确定性工作流导出独立 Java 运行包。

> 当前为早期开发预览版，不是已经验收的多租户生产系统；暂不包含可视化前端。采用 [MIT 许可证](LICENSE)，见[发布清单](docs/publishing.md)。

## 已具备的能力

- **资产管理**：Skill 制作、完整包导入、版本保留、归档、逻辑删除、依赖查询。
- **组合编排**：顺序步骤、输入输出契约、显式参数映射、组合 Skill 的有限嵌套复用。
- **执行追踪**：同步/异步执行、数据库 Outbox、执行租约、步骤记录、SSE 事件、取消和手动重试。
- **定时任务**：一次性、每日、间隔任务，支持显式时区及重叠处理。
- **独立导出**：定义文件与 Java CLI 打包，确定性工作流不依赖 Node.js。
- **可选智能体**：可信指令型 Skill 可接本机 Codex CLI；默认禁用，不自动读取个人模型配置。

应用、告警、日志示例均为模拟数据；通知操作仅生成预览，不发送消息。当前不是任意 DAG、并行分支、人工审批或通用脚本沙箱。

## 快速启动

安装 JDK 21；首次构建需要获取公开 Maven 依赖。Windows 将 `./mvnw` 替换为 `mvnw.cmd`。

```sh
./mvnw clean verify
java -jar target/openskill-0.1.0-SNAPSHOT.jar
```

默认仅监听 **127.0.0.1:4312**，数据位于当前项目 `data/`，日志位于 `logs/`。在项目根目录启动，以便导出功能找到运行时 JAR。不要把 local 模式通过反向代理开放给其他用户。

```sh
curl http://127.0.0.1:4312/api/bootstrap
curl -X POST http://127.0.0.1:4312/api/skills \
  -H 'Content-Type: application/json' \
  --data-binary @examples/greeting-skill.json
```

将返回的 `id` 填入下列请求，即可得到 `Hello, World!`，无需模型账号或外部服务：

```sh
curl -X POST http://127.0.0.1:4312/api/node-run \
  -H 'Content-Type: application/json' \
  -d '{"skillId":"<返回的-id>","version":1,"input":{"name":"World"}}'
```

## 验证

```sh
./mvnw clean verify
python3 scripts/smoke.py
python3 scripts/smoke-package.py
python3 scripts/smoke-agent.py
```

2026-11-26 本地验证：**75 项测试和三组冒烟测试全部通过**，边界见[验证记录](docs/verification.md)。

冒烟脚本启动临时端口、使用隔离数据库；智能体回归使用受控假 CLI，不会调用真实模型，也不会消费个人额度。

## 模型接入与安全边界

导入、导出指令包不要求模型；执行时才需要。仅在单用户本机运行可信 Skill 时，显式设置 `SKILL_AGENT_MODE=codex`，通过 `SKILL_MODEL_BASE_URL`、`SKILL_MODEL_NAME`、`SKILL_MODEL_API_KEY` 配置支持 Responses 协议的供应商。密钥仅通过进程环境或秘密管理系统注入，不提交代码、写进 Skill 或发送给前端。

本机 CLI 需要另行安装；版本相关参数、`local-codex` 可选配置来源、网络开关见[部署说明](docs/deployment.md)。模型服务可能接收说明、输入及工具读取的内容。容器身份模式拒绝本机 CLI 执行；多用户部署应先实现隔离 Runner，而不是关闭保护。

## 当前边界与后续方向

- 仅包含 Java 后端和独立 CLI，没有附带可视化编辑器。
- 编排为顺序模型，最多 12 个步骤、有限嵌套，不承诺外部副作用恰好执行一次。
- MySQL 部署、可信身份接入、凭据中心、隔离 Runner、限流与容量验证仍需完成。
- 后续以 openSkill 为独立维护对象，优先建设可视化工作台、通用适配器与公开示例，不依赖组织专属基础设施。

## 参与和许可证

贡献入口见 [CONTRIBUTING.md](CONTRIBUTING.md)，规划见[路线图](docs/roadmap.md)。不得提交真实业务数据、内网地址、账号或凭据。

**许可证：[MIT](LICENSE)。** Copyright (c) 2026 Yaning Wang。依赖和第三方组件保留各自的许可证与声明。
