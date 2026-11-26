# openSkill Java API：实际实现契约

日期：2026-11-26。本文件描述当前代码，而不是尚未兑现的接口草案。**50 个 HTTP 方法/路径组合 = 13 个原前端兼容路由 + 37 个扩展路由**（完整包导入复用原路径，按 Content-Type 分发后共 51 个处理器）；不计 Actuator 自动路由。

## 一、公共约定

- 新增或调整的业务接口优先使用 GET / POST：查询使用 GET，写操作使用 POST。存量兼容路由未在本次批量迁移；Skill 删除已改为 POST `/api/skills/{id}/delete`，GET 不执行删除。
- JSON camelCase，成功直接返回对象，无 `code/data/message` 外壳。写请求 `Content-Type: application/json`。
- JSON 错误：`{"error":"说明","code":"错误码","traceId":"..."}`；响应头 `X-Trace-Id` 可关联日志。框架容器层错误不保证全部经业务异常处理器转换。
- ID 为字符串，version/revision 为数字。version 是不可变 Skill 版本；revision 是可编辑记录的并发修订。
- 时间为 ISO 8601，调度使用显式 IANA timezone。日期筛选 `from/to` 传 Instant（建议 UTC `Z`）。
- 普通分页：`{items,total,page,pageSize}`，默认 page=1/pageSize=20。当前没有统一最大 pageSize，调用方遵循合理正整数，生产在网关/后续配额中处理容量边界。
- cursor 日志：`{items:[{id,type,data,createdAt}],nextCursor}`，cursor 是事件自增序号，不是数组下标。
- 所有资源查询按当前 workspace + owner 过滤；服务端决定主体，不能由请求体选择任意 owner。
- local profile 仅回环地址开发；container 身份模式必须接入可信 principal/roles/workspace。所有写接口需要会话 CSRF（local 除外）。
- 幂等键可选；仅表中注明的接口识别 `Idempotency-Key`。相同用户/空间/操作/键/请求返回第一次响应；同键不同请求 409。key/request 以 SHA-256 保存，JSON 对象属性排序后比较。
- 不自动清理幂等键，目前不要复用旧键。重放的 202 响应仍是最初 queued 对象，不是最新状态，须随后 GET run。

### 权限缩写

R=skill-read；E=skill-edit；P=skill-publish；X=skill-execute；S=skill-schedule；F=skill-export；C=skill-credentials；A=skill-admin。

表中角色为生产接口边界；skill-admin 可通过所有接口角色检查，但仍保留 owner 查询过滤。local 开发身份不执行企业角色检查。

## 二、原前端兼容接口（13）

| # | 方法与路径 | 请求 | 成功返回 | 角色 |
|---|---|---|---|---|
| C01 | GET `/api/bootstrap` | 无 | 200 `{skills,workflows,schedules,runs,runtime}` | R |
| C02 | POST `/api/skills` | AtomicSkill；可选 Idempotency-Key | 201 Skill 固定版本 | E |
| C03 | POST `/api/skills/import` | JSON `{markdown}` 或 multipart `file` ZIP | 201 instructions Skill | E |
| C04 | GET `/api/skills/{id}/{version}/export` | 固定版本路径参数 | 200 ZIP + Content-Disposition | F |
| C05 | POST `/api/workflows` | Workflow 本身；更新带 revision | 200 保存后的 Workflow | E |
| C06 | POST `/api/validate` | `{workflow}` | 200 `{valid:true,warnings,dependencies}`；不合法非 2xx | E |
| C07 | POST `/api/run` | `{workflow,input,upTo?}` | 200 最终 Run | X |
| C08 | POST `/api/node-run` | `{skillId,version,input}` | 200 `{status,output,durationMs}` | X |
| C09 | POST `/api/mapping-preview` | `{binding,context}` | 200 `{value,source,beforeCount,count}` | E |
| C10 | POST `/api/publish` | `{workflow}`；可选 Idempotency-Key | 201 composite Skill | P |
| C11 | POST `/api/schedules` | ScheduleBody；可选 Idempotency-Key | 201 Schedule | S |
| C12 | PATCH `/api/schedules/{id}` | `{enabled:true/false}` | 200 Schedule | S |
| C13 | DELETE `/api/schedules/{id}` | 无或旧客户端 `{}` | 200 `{ok:true}` | S |

- bootstrap 返回当前主体的**所有 Skill 版本**、所有草稿和任务、最近 100 个 run；没有悄悄截断 Skill 列表。runtime.mode 是 database，demo 反映真实配置。
- 原前端不支持分页；数据量增大后不能持续依赖 bootstrap 全量读取。
- 工作流按 steps 数组顺序执行，画布 position 仅用于布局。
- upTo 从头执行到指定步骤，不是从该步骤恢复执行。
- `/run` 中节点执行失败可返回 HTTP 200 + run.status=failed；请求契约/身份错误是非 2xx。HTTP 成功不表示业务运行成功。
- `/node-run` 单节点失败按错误类型返回非 2xx，空结果保留 empty 语义。
- 单独导入的 SKILL.md 是 instructions，不会被当作任意脚本执行，也不能直接发布成无人值守任务。
- `/publish` 需要 workflow.id 对应已保存草稿；发布收到的定义快照，并不自动先保存草稿。

### 完整 Skill 包导入与导出

- `POST /api/skills/import` 使用 `multipart/form-data`，字段名 `file`，浏览器自动生成 boundary；JSON `{markdown}` 入口继续兼容。
- ZIP 根目录或单层包目录下包含唯一 `SKILL.md`，例如 `SKILL.md` 或 `my-skill/SKILL.md`。说明文本沿用 100000 字符限制；上传包默认 50MB，可通过 Spring multipart 配置调整。
- `skill_packages` 存储原始 ZIP 二进制，以 workspace / skillId / version 关联，并按 owner 隔离。包内所有文件、目录、属性及 ZIP 注释原样保留；导入阶段不解压执行，试运行时另建临时目录解压，不将包体装入列表响应。
- 同一 owner/workspace 下同名导入创建下一版本，不覆盖历史版本；已导入过 MD 的技能可以再导入 ZIP 补齐资源，旧版本仍保持原样。并发争抢首次同名创建时仍使用数据库唯一约束返回 409。
- 元数据、原包、审计在同一数据库事务内提交，失败整体回滚。
- `GET /api/skills/{id}/{version}/export` 对 ZIP 导入版本直接返回原始字节，下载文件名仍为 `<name>-v<version>.zip`。仅 MD 或平台制作版本继续使用现有生成包逻辑；旧 MD 导入不会凭空恢复未上传文件。
- 文件缺失、不可解析 ZIP、无法定位说明返回 400；超出服务器上传限制返回 413；暂存故障返回 500。保留原有身份/权限、同源与企业 CSRF 约束。

## 三、Skill/草稿管理（8）

| # | 方法与路径 | 参数/请求 | 返回 | 角色 |
|---|---|---|---|---|
| E01 | GET `/api/skills` | keyword/status/page/pageSize；status=active/archived | 200 Page<SkillAsset>，每逻辑 Skill 一条 | R |
| E02 | GET `/api/skills/{id}/versions` | id | 200 `{items:[Skill...]}`，版本倒序、当前不分页 | R |
| E03 | GET `/api/skills/{id}/versions/{version}` | 固定版本 | 200 Skill | R |
| E04 | GET `/api/skills/{id}/versions/{version}/dependencies` | direction=forward（默认）/reverse | 200 `{items:[...]}` | R |
| E05 | PATCH `/api/skills/{id}` | `{archived,revision}` | 200 SkillAsset 新 revision | E |
| E06 | GET `/api/workflows` | keyword/page/pageSize | 200 Page<Workflow> | R |
| E07 | GET `/api/workflows/{id}` | id | 200 Workflow + revision | R |
| E08 | DELETE `/api/workflows/{id}` | query `revision` | 200 `{ok:true}`，不删除已发布版本 | E |

Skill 删除：`POST /api/skills/{id}/delete`（E37，角色 E），请求体可省略或传 `{}`，返回 `{id,deleted:true}`。逻辑删除整项技能：bootstrap 与默认技能列表隐藏所有版本；历史版本、完整 ZIP、既有编排/任务引用保留，不停止已有任务。同名重新导入会恢复可见并产生下一版本。不是物理清除或撤销执行资格。

归档为逻辑资产标记，不修改历史版本、不级联删除依赖，也不撤销已经发布版本的执行资格。反向依赖覆盖可见的组合 Skill、调度引用（当前不包含未发布草稿）；正向遍历固定版本依赖。当前查询以内存遍历为主，尚未做大规模依赖索引。

## 四、异步执行（8）

| # | 方法与路径 | 参数/请求 | 返回 | 角色 |
|---|---|---|---|---|
| E09 | POST `/api/runs` | `{target,input,timeoutSeconds?}`；可选 Idempotency-Key | 202 Run + Location `/api/runs/{id}` | X |
| E10 | GET `/api/runs` | status/skillId/workflowId/scheduleId/from/to/page/pageSize | 200 Page<Run> | R |
| E11 | GET `/api/runs/{id}` | id | 200 Run | R |
| E12 | GET `/api/runs/{id}/steps` | page/pageSize | 200 Page<StepRun> | R |
| E13 | GET `/api/runs/{id}/logs` | cursor=0/limit=100 | 200 事件增量 + nextCursor | R |
| E14 | POST `/api/runs/{id}/cancel` | `{}` | 200 当前 Run | X |
| E15 | POST `/api/runs/{id}/retry` | `{}`；可选 Idempotency-Key | 202 新 Run，含 parentRunId | X |
| E16 | GET `/api/runs/{id}/events` | Last-Event-ID header 或 cursor query | 200 text/event-stream | R |

异步目标二选一：

```json
{"target":{"type":"skill","skillId":"skill-id","version":1},"input":{"appName":"orders"},"timeoutSeconds":300}
```

```json
{"target":{"type":"workflow","workflowId":"draft-id","revision":3},"input":{"appName":"orders"}}
```

不接受异步 inline 目标；revision 必须匹配当前草稿。timeoutSeconds 默认 300，当前范围 1–3600。同步试跑固定 300 秒、调度固定 300 秒；取消/超时为协作检查点，不保证硬中断在途 RPC。

状态：`queued`、`running`、`cancelling`、`succeeded`、`empty`、`failed`、`cancelled`、`timed_out`、`interrupted`。运行详情包括脱敏的 input/steps/output、时间、source、可选 skillId/version/scheduleId/parentRunId 等字段。失败/空结果不保证有完整输出。

StepRun 保留 stepId/title/version/status/input/output/error/durationMs，并为嵌套节点增加 executionId、nodePath、parentExecutionId。步骤实际结果及对应事件存储在数据库中，不是静态模拟日志。

SSE 示例：

```text
id: 42
event: step
data: {"stepId":"render", "status":"succeeded", ...}
```

事件类型包括 queued/running/step 和实际终态。服务每秒查询事件，发送 heartbeat 注释，约 55 秒主动结束连接；客户端重连并带 Last-Event-ID，终态处理后主动关闭 EventSource。`/logs` 是相同事件的 JSON 查询接口，**不是原始服务日志下载**。

重试仅允许 failed/interrupted/timed_out/cancelled 状态，复用原输入和定义快照、创建新实例；不覆盖历史、不自动从失败节点续跑。执行过的外部副作用不能撤销，应先核对再重试。

## 五、调度扩展（5）

| # | 方法与路径 | 参数/请求 | 返回 | 角色 |
|---|---|---|---|---|
| E17 | GET `/api/schedules` | keyword/status/page/pageSize；status=enabled/paused/completed | 200 Page<Schedule> | R |
| E18 | GET `/api/schedules/{id}` | id | 200 Schedule | R |
| E19 | PUT `/api/schedules/{id}` | 完整 ScheduleBody + revision | 200 Schedule，重算 nextAt | S |
| E20 | POST `/api/schedules/{id}/trigger` | `{}`；可选 Idempotency-Key | 202 新 Run；不改 nextAt | S |
| E21 | POST `/api/schedules/preview` | 调度规则及可选 input | 200 `{occurrences,timezone,resolvedInput}` | S |

ScheduleBody 示例：

```json
{
  "name":"每日应用查询",
  "skillId":"skill-id",
  "version":1,
  "input":{"appName":"orders","date":{"$relative":"yesterday"}},
  "frequency":"daily",
  "timezone":"Asia/Shanghai",
  "time":"09:00"
}
```

- daily 使用 HH:mm；interval 使用 intervalMinutes（1–10080）；once 使用 at（ISO Instant）。
- once 示例时间应由调用方选择未来的绝对时刻，不能照搬文档日期。
- preview 固定返回最多 5 个触发点，once 仅 1 个；当前不读取 count。
- daily 夏令时缺失时间跳过，重复本地时间允许对应的两个时刻；有测试覆盖。
- `$relative` 仅解析输入顶层字段对象中的 today/yesterday，按任务时区和实际触发时间计算，不是任意表达式语言。
- 绑定固定 Skill 版本；inputs 按契约处理；instructions 和 notification-preview 不作为无人值守任务。
- enabled/identity/overlap/missed 等由服务端维护；创建任务默认启用。PUT 保留原启用状态，PATCH 控制启用/暂停。
- manual trigger 允许对暂停任务运行一次，遵守当前是否有活跃 run 的重叠约束。
- once 到期后关闭；overlap=skip；missed=coalesce。重叠跳过保存在 lastOutcome/审计，不创建 skipped run。

## 六、身份、适配器、凭据和运维（15）

| # | 方法与路径 | 参数/请求 | 返回 | 角色 |
|---|---|---|---|---|
| E22 | GET `/api/me` | 无 | 200 `{id,workspaceId,authMode}` | R |
| E23 | GET `/api/me/permissions` | 无 | 200 `{permissions:[...]}` | R |
| E24 | GET `/api/auth/csrf` | 同一登录会话 | 200 `{token,headerName:"X-CSRF-Token"}` | R |
| E25 | POST `/api/auth/logout` | `{}` + CSRF | 200 `{ok:true}` | R |
| E26 | GET `/api/adapters` | 无 | 200 `{items:[Adapter...]}` | R |
| E27 | GET `/api/adapters/{id}` | id | 200 Adapter | R |
| E28 | GET `/api/credentials` | page/pageSize | 200 Page<CredentialBinding> | C |
| E29 | POST `/api/credentials` | `{name,secretRef}` | 201 CredentialBinding | C |
| E30 | PATCH `/api/credentials/{id}` | `{name,secretRef,revision}` | 200 CredentialBinding | C |
| E31 | DELETE `/api/credentials/{id}` | `{}` | 200 `{ok:true}` | C |
| E32 | POST `/api/credentials/{id}/test` | `{}` | 已接 verifier 时 200；当前未接为 503 | C |
| E33 | GET `/api/audit-events` | page/pageSize | 200 Page<AuditEvent> | A |
| E34 | GET `/internal/health/liveness` | 无 | 200 `{status:"UP"}` | A |
| E35 | GET `/internal/health/readiness` | 无 | 200/503，数据库可用性 | A |
| E36 | GET `/internal/metrics` | 无 | 200 Prometheus 文本 | A |

credentials 不接受真实 secret 值，仅保存引用元数据。测试未绑定后端时返回 `CREDENTIAL_BACKEND_UNCONFIGURED`，不伪造成功。删除引用暂未和外部凭据系统做吊销动作。

Adapter 当前返回 id/operation/available 等基础能力信息；instructions 由智能体适配器执行，available 仅代表当前模式启用，不代表供应商鉴权/网络健康；instructions 额外返回不含敏感信息的 `providerSource`（`local-codex` 或 `server`）。未提供完整动态 JSON Schema/自动表单，不应将 `available=true` 当作下游健康探测。

审计字段包括 id/actor/action/resourceId/traceId/createdAt，管理员查看当前 workspace 中其自身 actor 的审计记录，尚无全空间/全企业审计查询。探针和 metrics 正式环境也需要服务身份，具体网关/采集设置见部署文档。

## 七、主要数据模型

### Field

```json
{"key":"appName","label":"应用名称","type":"string","required":true}
```

type 为 string/number/boolean/array/object；不是任意 JSON Schema。

### AtomicSkill 制作请求

```json
{
  "name":"render-summary",
  "title":"生成摘要",
  "description":"将输入格式化为摘要文本",
  "kind":"atomic",
  "operation":"template",
  "inputs":[{"key":"appName","label":"应用名称","type":"string","required":true}],
  "outputs":[{"key":"text","label":"摘要","type":"string","required":true}],
  "config":{"template":"应用：{{appName}}"},
  "category":"自制技能"
}
```

同一资产创建下一版本时增加 id；服务端分配 version，已发布版本不原地修改。原子操作包括 template、filter、select、notification-preview。demo-* 仅在本地示例运行启用。组合 Skill 通过 publish 创建，而不是上传任意执行实现。


### Workflow / Step / Binding

```json
{
  "name":"compose-summary",
  "title":"组合摘要",
  "description":"复用已发布的摘要技能",
  "inputs":[{"key":"appName","label":"应用名称","type":"string","required":true}],
  "sampleInput":{"appName":"orders"},
  "steps":[{
    "id":"render",
    "skill":{"id":"已创建的-skill-id","version":1},
    "bindings":{"appName":{"mode":"path","path":"input.appName","transform":"identity","confirmed":true}},
    "onEmpty":"stop",
    "position":{"x":100,"y":120}
  }]
}
```

保存后返回 id/revision，更新带回它们。服务端使用 Skill id/version 获取真实定义，忽略步骤内伪造的 operation/config。保存允许未完成映射；执行/发布按原编排契约处理。

Binding：mode=path/constant；path 来自 `input.xxx` 或 `steps.前序步骤.xxx`；constant 使用 JSON value。转换为 identity/first/pluck/join/json；argument 为提取字段或连接符，可选 filter=`{field,value}`。confirmed=false 不允许执行/发布，避免建议映射被悄悄当成确认。

onEmpty=stop/continue；前向引用、重复步骤、递归组合、超过现有步数/嵌套限制按契约拒绝。模型尚无条件边、并行 fan-out/fan-in、循环和人工审批。

## 八、常见错误与兼容差异

- 400：请求/原有工作流契约不合法。
- 401/403：缺少认证、权限、可信空间或 CSRF；local 的远程访问也被拒绝。
- 404：当前主体看不到资源，或资源不存在。
- 409：修订冲突、同键不同请求、不能重试当前状态、任务已有活跃实例等。
- 503：未配置凭据验证后端、导出 runtime jar 缺失、readiness 数据库异常等。
- 未识别异常对外不返回堆栈；SDK 自身日志仍需企业侧脱敏和密级控制。

### 与最初接口草案的差异 / 未完成细节

1. Skill 列表实际支持 keyword/status，不支持草案 kind/category 筛选；keyword 匹配 name，不是全文搜索。
2. versions 是 `{items}` 全量版本倒序，不是版本分页。
3. 调度列表是 keyword/status，不支持草案 enabled/skillId 筛选；preview 固定最多 5 次，不读 count。
4. 运行详情/分页仍返回 JSON 结果，不提供大产物对象存储和产物下载 API。
5. `/adapters/{id}` 只有基础目录信息，完整配置 schema/幂等风险能力声明尚未完成。
6. 草稿更新为兼容旧前端允许省略 revision；这时使用当前版本更新，不能保证阻止所有旧客户端覆盖。带 revision 的新客户端才具备显式冲突检测。publish 也仅在提交 revision 时比较。
7. 归档标记不等同于禁用执行；任务/快照不做 SSO 角色实时撤权复核，仅在请求边界处处理权限。
8. 既有安全提示仅保留说明型/预览等可执行边界，不等于完整风险评估或密钥内容扫描；明文存储风险见部署文档。
9. 凭据接口、企业身份接口已有契约和拒绝未配置行为，但真实 SSO/凭据中心不是本次可以凭空填入的环境实现。
10. 定时器为数据库轮询，未集成外部调度平台。审计和 metrics 已有入口，但未交付企业监控告警配置。

这些差异需在下一阶段按实际业务和上线标准补齐；本轮不通过多造一套路由或假成功掩盖未接入能力。

## 九、导入 Skill 与单节点试运行

`POST /api/node-run` 同时用于技能库试运行和工作台“仅运行此节点”：

```json
{"skillId":"固定版本的技能ID","version":1,"input":{"prompt":"总结输入内容","context":{"content":"测试内容"}}}
```

- 只执行该 Skill，不解析或执行工作流前置节点；调用方直接提供本节点输入。`/api/run` 的 `upTo` 仍是“试跑至此”，语义不同。
- 导入 instructions 自动提供可选 `prompt:string` / `context:object`，输出 `text:string`。旧空契约读取时投影，不回写历史原包；组合输出取最后一步。
- ZIP 执行从数据库读取固定版本原包，在一次性工作目录解压；MD 只有说明，不会凭空生成其引用的脚本/资源。
- 个人 local 模式可启用 Java 调度的 Codex CLI；企业身份模式拒绝本机 CLI。配置见 README 和 deployment。
- 成功返回 `{status:"succeeded",output:{text,executionId,executor:"codex"},durationMs}`。智能体按结构化 `success/text` 报告完成或失败；该标记不是外部业务数据的真实性证明。
- 未配置 503 `AGENT_NOT_CONFIGURED`；可执行文件/目录不可用 503 `AGENT_START_FAILED`；模型配置缺失/不可读 503 `AGENT_PROVIDER_NOT_CONFIGURED`；供应商鉴权等 CLI 失败 502 `AGENT_EXECUTION_FAILED`；模型报告任务未完成 422 `AGENT_TASK_FAILED`；超时 408 `AGENT_TIMEOUT`；容量超限 413；越界包路径 400 `PACKAGE_PATH_INVALID`。
- 单次智能体默认 240 秒，单节点总计 300 秒协作截止。同步 node-run 不保存运行历史、没有前端取消接口；进程退出后清理临时目录。
- 本地模式可能使用远端模型，不能宣称“数据不出本机”。配置关闭工具网络不代表关闭模型连接。messages/包正文/模型结果不记入业务日志，调用方仍应避免提交机密资料。
- 当前智能体（含其组合）不开放无人值守调度。导出的独立 Java CLI 不含智能体适配器及原依赖资源；原始 ZIP 导出依旧逐字节保持。
