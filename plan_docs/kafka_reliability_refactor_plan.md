# Kafka 可靠投递与幂等改造方案

## 1. 背景与目标

当前项目已经把慢 I/O 从主线程中解耦出来，核心链路如下：

1. 用户上传文档，Java 服务写 MinIO 与 `document_metadata`，然后投递 `doc-parse-request`。
2. Python MinerU Worker 消费 `doc-parse-request`，产出 Markdown 后再投递 `doc-vectorize-request`。
3. Java 服务消费 `doc-vectorize-request`，执行切块、embedding、写 ES，并更新文档状态。
4. 删除文档时，Java 服务直接删除元数据和 MinIO，再投递 `doc-delete-request` 清理 ES。

当前设计已经实现了“异步解耦”，但还没有把“可靠投递、可靠消费、重复消息防护、失败可追踪”做成闭环。  
这意味着系统更像“能跑起来的异步链路”，还不是“可恢复、可追踪、可重试的企业级消息系统”。

本方案目标：

1. 补齐生产端的投递确认，避免“数据库已提交但消息没真正发出去”。
2. 补齐消费端的消费确认，避免“业务没成功却提前提交 offset”。
3. 补齐幂等与去重，允许 Kafka 的 at-least-once 语义下安全重放。
4. 把异步任务变成可追踪状态机，而不是只看 `document_metadata.status` 猜当前阶段。
5. 让 Python Worker 与 Java 两端拥有一致的消息契约和失败处理约定。

---

## 2. 现状调查结论

### 2.1 发送端现状

#### 上传链路

`DocumentService.uploadAndDispatch()` 在数据库事务里直接调用 `DocumentMessageProducer.sendDocParseRequest()`。

现状问题：

1. `document_metadata` 插入与 Kafka 发送不在同一个可靠事务闭环内。
2. `KafkaTemplate.send(...).whenComplete(...)` 只是异步日志回调，不会阻塞当前事务等待 broker 确认。
3. 如果数据库提交成功但 Kafka 发送随后失败，系统会留下一个长期停在 `UPLOADED` 的文档。
4. 如果 Kafka 消息先被下游消费，而数据库事务尚未提交，下游按 `documentId` 回查元数据时可能读不到记录。

#### 删除链路

`DocumentService.deleteDocumentById()` 先删 MySQL 元数据、再删 MinIO、最后投递 `doc-delete-request`。

现状问题：

1. 这是“先删业务事实，再异步通知下游”的顺序。
2. 一旦删除消息发送失败，ES 中会残留孤儿向量，但 MySQL 元数据已经不在了。
3. 之后很难做补偿，因为追踪主记录已被物理删除。

### 2.2 消费端现状

`DocumentMessageListener` 目前直接用 `@KafkaListener` 消费字符串消息，没有显式 `Acknowledgment`，也没有手动控制 offset 提交。

现状问题：

1. 当前代码未显式声明手动 ack 策略。
2. 当前工程未显式配置 `DefaultErrorHandler`、退避重试、死信转发、DLT 主题路由。
3. 因为没有把 ack 作为业务成功后的最后一步，消费确认语义并不清晰。
4. `doc-dlq` 的监听器是假设死信消息能直接反序列化成简单 `Map` 并拿到 `documentId`，但这依赖消息格式刚好匹配，鲁棒性很弱。

### 2.3 幂等与重复消费现状

#### 已有的有利条件

`MinerUMarkdownStrategy` 与 `StandardTxtStrategy` 生成的 `chunkId` 是稳定的，基于 `fileUrl + fileName + chunkIndex` 生成。  
`KnowledgeChunkIndexService.indexChunks()` 也是按 `chunkId` 做 `PUT` 写 ES。

这意味着：

1. 同一批 chunk 被重复写入时，不一定会产生“无限新增的重复文档”。
2. 对于完全一致的输入，ES 文档大概率会被覆盖而不是膨胀。

#### 仍然存在的问题

1. Java 消费 `doc-vectorize-request` 时没有事件去重表，也没有消费日志。
2. 同一条消息重复投递时，仍会重复执行 MinIO 读取、解析、embedding 和 ES 写入，成本很高。
3. `DocumentVectorizationService.vectorize()` 没有检查“当前文档是否已经 `VECTORIZED` / 正在处理中”，重复消息会无条件重跑。
4. 如果解析结果内容发生变化且 chunk 数变少，旧 chunk 可能残留在 ES 中，因为当前没有“重建前清理旧索引”的步骤。

### 2.4 状态机现状

当前 `document_metadata.status` 只有：

- `UPLOADED`
- `PARSING`
- `VECTORIZED`
- `FAILED`

问题在于：

1. 它既承载“面向用户的业务状态”，又想承载“异步任务内部状态”，粒度不够。
2. 无法表达“消息已入本地事务，但尚未发往 Kafka”。
3. 无法表达“已发往 Kafka，但尚未被消费”。
4. 无法表达“解析成功，等待向量化”。
5. 无法表达“删除进行中，但还不能物理清除”。

### 2.5 一个很关键的事务问题

`DocumentVectorizationService.vectorize()` 被 `@Transactional` 包裹。  
方法内部一旦失败，会先把状态改成 `FAILED`，然后继续抛异常。

这会带来一个高风险现象：

1. `PARSING -> FAILED` 状态更新和异常属于同一个事务。
2. 抛异常后，当前事务很可能整体回滚。
3. 结果是库里既没有成功结果，也不一定真的留下 `FAILED`。
4. 如果 DLT 又没正确落地，前端看到的文档状态可能永远还是旧值。

### 2.6 外部 Worker 契约现状

当前仓库内没有 Python Worker 实现，因此“投递确认、消费确认、去重、重试、回传成功/失败”的规则并没有落成一份统一契约。  
现在更多是“Java 发出 parse 请求，Python 未来自己处理，再发回 vectorize 请求”的口头约定。

这意味着：

1. parse 阶段的可靠性无法从仓库内验证。
2. Java 与 Python 两端对消息头、事件 ID、重试次数、失败语义没有统一定义。
3. 真正跨服务的幂等闭环还不存在。

---

## 3. 当前系统的主要风险清单

### P0 级

1. 上传成功后，MySQL 已有记录，但 `doc-parse-request` 实际未送达 broker，文档永久卡在 `UPLOADED`。
2. 删除时先删元数据再发消息，若消息未送达，ES 中残留脏数据且无法追踪补偿。
3. `vectorize()` 失败时 `FAILED` 状态可能跟随事务一起回滚，失败不可见。

### P1 级

1. `doc-vectorize-request` 重复消费时会重复做 embedding 和 ES 写入，浪费大量资源。
2. 并发重复消费时没有状态 CAS 或任务锁，可能两个消费者同时处理同一文档。
3. DLT 处理逻辑过于乐观，无法保证一定能从死信里恢复出文档上下文。

### P2 级

1. Kafka 可靠性参数没有显式声明，运行时行为取决于默认值，不利于生产稳定性。
2. 任务重试次数、最后错误、下一次重试时间都没有落库，排障困难。
3. 前端只能看粗粒度状态，无法知道卡在哪个异步步骤。

---

## 4. 推荐总体方案

推荐采用：

1. **事务型 Outbox** 解决“投递确认”。
2. **手动 ack + 统一错误处理器 + DLT** 解决“消费确认”。
3. **任务表 + 事件 ID + 状态 CAS** 解决“重复消费与幂等”。
4. **业务状态与任务状态分离** 解决“状态不够表达”的问题。

这是本项目最稳妥、也最适合后续继续扩展 Python Worker / ES / OCR 等多订阅者的方案。

---

## 5. 建议的数据模型改造

### 5.1 保留 `document_metadata`，但职责回归业务聚合

建议让 `document_metadata` 只表达“文档对外可见的生命周期”，而不是直接承载每一个 MQ 中间状态。

建议扩展后的业务状态：

- `UPLOADED`
- `PARSING`
- `PARSED`
- `VECTORIZING`
- `VECTORIZED`
- `FAILED`
- `DELETE_PENDING`
- `DELETED`

建议增加字段：

- `current_stage`：`PARSE` / `VECTORIZE` / `DELETE`
- `last_error_code`
- `last_error_message`
- `last_task_id`
- `version`
- `deleted_at`

说明：

1. `status` 面向前端与业务查询。
2. `version` 用于乐观锁与状态 CAS。
3. 删除操作改为软删除或延迟物理删除，不再立即删行。

### 5.2 新增 `document_async_task`

建议新增异步任务表，作为文档异步链路的主账本。

建议字段：

- `task_id`：业务任务唯一 ID
- `document_id`
- `tenant_id`
- `task_type`：`PARSE` / `VECTORIZE` / `DELETE`
- `status`：`INIT` / `OUTBOX_PENDING` / `OUTBOX_SENT` / `CONSUMING` / `SUCCESS` / `RETRY_WAIT` / `DEAD`
- `event_id`：当前消息事件 ID
- `idempotency_key`
- `payload_json`
- `retry_count`
- `max_retry`
- `last_error`
- `kafka_topic`
- `kafka_partition`
- `kafka_offset`
- `started_at`
- `finished_at`
- `next_retry_at`
- `version`
- `created_at`
- `updated_at`

职责：

1. 记录每个异步阶段真实运行情况。
2. 保存最后一次 broker ack 元信息。
3. 提供重试与补偿依据。
4. 允许前端或运维明确知道“卡在 parse 还是 vectorize”。

### 5.3 新增 `mq_outbox_event`

建议新增本地 outbox 表。

建议字段：

- `event_id`
- `aggregate_type`
- `aggregate_id`
- `task_id`
- `topic`
- `message_key`
- `payload_json`
- `headers_json`
- `status`：`NEW` / `SENT` / `FAILED`
- `retry_count`
- `last_error`
- `partition`
- `offset`
- `created_at`
- `sent_at`
- `updated_at`

职责：

1. 与业务事务同提交，保证“业务事实已经存在，则事件一定最终可发”。
2. 由 outbox dispatcher 异步扫描发送。
3. 只有收到 broker ack 后才把 outbox 标记为 `SENT`。

### 5.4 新增 `mq_consume_log`

建议新增消费幂等表。

建议字段：

- `consumer_group`
- `topic`
- `event_id`
- `message_key`
- `status`：`RECEIVED` / `DONE` / `FAILED`
- `document_id`
- `task_id`
- `error_message`
- `created_at`
- `updated_at`

并建立唯一约束：

- `uk_consumer_event (consumer_group, topic, event_id)`

职责：

1. 保证同一消费者组对同一 `event_id` 最多只做一次业务处理。
2. 重复消息到来时可快速判定为“已完成，直接 ack”。

---

## 6. 建议的消息契约升级

当前 DTO 只有 `documentId、tenantId、kbId、fileName、fileUrl、timestamp` 这类业务字段，还不够支撑可靠链路。

建议所有异步消息统一增加以下通用字段：

- `eventId`：消息唯一 ID，用于消费幂等
- `taskId`：当前阶段任务 ID
- `traceId`：全链路追踪 ID
- `sourceService`
- `eventType`
- `occurredAt`
- `attempt`
- `schemaVersion`

各阶段建议如下：

### 6.1 `DocumentParseRequestDTO`

新增：

- `eventId`
- `taskId`
- `traceId`
- `attempt`
- `schemaVersion`

### 6.2 `DocumentVectorizeRequestDTO`

新增：

- `eventId`
- `taskId`
- `traceId`
- `sourceParseTaskId`
- `attempt`
- `schemaVersion`

说明：

1. `sourceParseTaskId` 用来把“parse 成功”与“vectorize 开始”串起来。
2. Java 消费 `doc-vectorize-request` 后，可以据此把 parse 任务标记成功。

### 6.3 `DocumentDeleteRequestDTO`

新增：

- `eventId`
- `taskId`
- `traceId`
- `attempt`
- `schemaVersion`

---

## 7. 发送端改造方案

### 7.1 上传链路改造

当前问题是：业务事务提交与 Kafka 发送没有可靠绑定。

建议流程：

1. 上传文件到 MinIO。
2. 在同一个数据库事务中：
   - 插入 `document_metadata`
   - 插入 `document_async_task(PARSE, OUTBOX_PENDING)`
   - 插入 `mq_outbox_event(doc-parse-request)`
3. 事务提交成功后，由 outbox dispatcher 扫描 `NEW` 事件。
4. dispatcher 调用 `KafkaTemplate.send(...).get(timeout)` 或等价同步确认方式等待 broker ack。
5. 发送成功后：
   - 更新 `mq_outbox_event.status=SENT`
   - 更新 `document_async_task.status=OUTBOX_SENT`
   - 记录 `partition / offset`
6. 如果发送失败：
   - `mq_outbox_event.status=FAILED`
   - `retry_count + 1`
   - 后台继续补偿重试

这样可以保证：

1. 只要数据库事务成功，事件就不会无声丢失。
2. broker ack 成功与否能被落账。
3. 即使应用在事务提交后瞬间宕机，重启后仍能继续发送未发出的 outbox 事件。

### 7.2 删除链路改造

当前删除顺序必须调整。

建议新流程：

1. 用户请求删除文档。
2. 在同一个事务中：
   - 把 `document_metadata.status` 改成 `DELETE_PENDING`
   - 插入 `document_async_task(DELETE, OUTBOX_PENDING)`
   - 插入 `mq_outbox_event(doc-delete-request)`
3. outbox 发送成功后，等待删除消费者清理 ES。
4. 删除消费者成功后，再把文档标记为 `DELETED`。
5. MinIO 物理删除建议放到删除成功后的后置步骤，或由独立清理任务完成。

这样做的好处：

1. 任何时刻都还有主记录可追踪。
2. 即使消息发送失败，也能看到该文档处于 `DELETE_PENDING` 并继续补偿。
3. 不会出现“DB 已删、ES 还在、且没有账本”的孤儿状态。

---

## 8. 消费端改造方案

### 8.1 Kafka Listener 模式

建议统一改成：

1. `AckMode.MANUAL_IMMEDIATE`
2. listener 方法签名接收：
   - `ConsumerRecord<String, String>`
   - `Acknowledgment`
3. 业务处理成功并完成本地事务提交后，再执行 `ack.acknowledge()`

原则：

1. **先处理成功，再确认消费。**
2. **只要没确认，就允许 Kafka 重投。**

### 8.2 统一错误处理器

建议配置 `DefaultErrorHandler`：

- 固定退避或指数退避
- 最大重试次数，例如 3 或 5 次
- 最终进入 DLT

建议不要继续依赖“抛异常 + 期待默认行为正确”。

### 8.3 DLT 策略

推荐两种方式二选一：

1. 每个主题独立 DLT：
   - `doc-parse-request.dlt`
   - `doc-vectorize-request.dlt`
   - `doc-delete-request.dlt`
2. 保留单一 `doc-dlq`，但消息头里必须带原始 topic、eventId、taskId、异常摘要

我更推荐第一种，因为定位更清晰。

DLT 消费后的动作：

1. 更新 `document_async_task.status=DEAD`
2. 更新 `document_metadata.status=FAILED`
3. 落最后错误信息
4. 发告警或进入人工处理列表

---

## 9. 幂等与去重方案

### 9.1 消息级幂等

每条消息必须带 `eventId`。

消费流程：

1. listener 收到消息后，先尝试插入 `mq_consume_log(RECEIVED)`。
2. 如果唯一键冲突，说明此 `eventId` 已处理过：
   - 若状态是 `DONE`，直接 ack 返回
   - 若状态是 `FAILED/RECEIVED`，根据策略决定是否重试
3. 只有首次插入成功的消费者，才继续执行业务逻辑

### 9.2 业务级幂等

仅靠 `eventId` 还不够，还需要保证“重复执行不会破坏文档状态机”。

建议加入状态前置条件：

1. `PARSE` 任务只能从 `UPLOADED / PARSING` 进入处理。
2. `VECTORIZE` 任务只能从 `PARSED / VECTORIZING` 进入处理。
3. 已经 `VECTORIZED` 的文档再次收到旧 `doc-vectorize-request` 时，应直接判定为重复消息并 ack。

建议实现方式：

1. Mapper 增加条件更新 SQL，例如：
   - `update ... where document_id=? and status in (...)`
2. 返回影响行数为 0 时，说明状态已被其他线程推进，可视为重复或并发冲突。

### 9.3 索引级幂等

虽然当前 `chunkId` 稳定，但仍建议在向量化重跑前执行以下二选一策略：

1. 先按 `documentId + tenantId` 删除旧 chunk，再写新 chunk
2. 为 chunk 增加 `documentVersion`，检索只查最新版本

现阶段更推荐第一种，改动更小。

---

## 10. 对 `DocumentVectorizationService` 的专项改造建议

### 10.1 失败状态单独事务提交

不要在主事务里“改成 FAILED 然后继续抛异常”。

建议改为：

1. 主业务事务只负责“尝试处理”。
2. 失败后通过 `REQUIRES_NEW` 的失败回写方法单独提交：
   - 更新任务为 `FAILED/RETRY_WAIT/DEAD`
   - 更新文档为 `FAILED`
   - 保存错误信息
3. 然后再把异常抛给 Kafka 错误处理器

这样可以确保：

1. 即便 Kafka 选择重试，本地也看得到失败痕迹。
2. 文档状态不会因为事务回滚而失真。

### 10.2 不再信任消息里的安全字段覆盖数据库

当前 `vectorize()` 会优先使用消息中的 `tenantId / kbId / allowedRoles`。  
这不适合作为最终安全来源。

建议改成：

1. `tenantId / kbId / allowedRoles` 一律以 `document_metadata` 为准。
2. 消息体只允许提供“本阶段新增信息”，例如解析产物的 `fileUrl`。
3. 对不一致情况直接记为异常并进入重试或失败。

### 10.3 增加任务锁或状态 CAS

同一个 `documentId` 的 `VECTORIZE` 任务不允许并发执行。

建议：

1. 通过 `document_async_task.status` 条件更新抢占执行权
2. 或增加 Redis/DB 锁，但优先推荐 DB 状态 CAS，便于审计

---

## 11. Python Worker 的契约要求

虽然 Python Worker 不在当前仓库，但要让整个系统真正可靠，它必须遵守同一套消息规则。

建议强制要求：

1. 消费 `doc-parse-request` 时使用手动 commit/ack。
2. 先完成以下业务，再提交消费确认：
   - OCR / MinerU 解析
   - Markdown 上传 MinIO
   - 发送 `doc-vectorize-request`
3. `doc-vectorize-request` 的发送也必须等待 broker ack。
4. 如果发送 `doc-vectorize-request` 失败，不得提前提交 `doc-parse-request` 的消费确认。
5. Python 侧也要基于 `eventId` 做消费幂等。

推荐语义：

1. `doc-parse-request` 的“消费成功”，不是“开始解析”，而是“解析完成且下游 vectorize 事件已可靠发出”。
2. 这样 parse 阶段和 vectorize 阶段才真正首尾相接。

---

## 12. 改造后的建议时序

### 12.1 上传 -> 解析

1. 用户上传文件。
2. Java 写 MinIO。
3. Java 事务提交：
   - `document_metadata = UPLOADED`
   - 新建 `PARSE` 任务
   - 写 outbox `doc-parse-request`
4. outbox dispatcher 发送成功，记录 partition/offset。
5. Python Worker 收到消息，先做消费幂等检查。
6. Python 完成 OCR、Markdown 入 MinIO、发送 `doc-vectorize-request`。
7. 只有 `doc-vectorize-request` 发送成功后，Python 才确认消费 `doc-parse-request`。

### 12.2 向量化

1. Java 收到 `doc-vectorize-request`。
2. 幂等表判断是否重复事件。
3. 条件更新抢到 `VECTORIZE` 执行权。
4. 把 parse 任务标记成功，把文档状态推进到 `VECTORIZING`。
5. 读取 MinIO、解析、embedding、清理旧 ES chunk、写新 chunk。
6. 成功后：
   - `VECTORIZE` 任务标记 `SUCCESS`
   - 文档标记 `VECTORIZED`
   - `mq_consume_log` 标记 `DONE`
   - 手动 ack
7. 失败后：
   - 新事务回写失败信息
   - 抛异常给 Kafka 错误处理器
   - 到达最大重试后进入 DLT

### 12.3 删除

1. 用户点击删除。
2. Java 事务内将文档标记为 `DELETE_PENDING`，新建删除任务与 outbox。
3. outbox 发出 `doc-delete-request`。
4. 删除消费者清理 ES 成功后：
   - 删除任务标记 `SUCCESS`
   - 文档标记 `DELETED`
5. MinIO 物理删除可在此后同步执行，或交由独立清理任务。

---

## 13. 建议的实现顺序

### 第一阶段：先补最危险的闭环

1. 引入 `document_async_task`
2. 引入 `mq_outbox_event`
3. 上传与删除链路改为 outbox 模式
4. Kafka producer 显式配置可靠性参数
5. 删除链路改为软删除 / `DELETE_PENDING`

### 第二阶段：补消费确认

1. 配置手动 ack
2. 配置 `DefaultErrorHandler`
3. 配置 DLT
4. 改造 listener 签名与异常处理

### 第三阶段：补幂等

1. 引入 `mq_consume_log`
2. 所有消息补 `eventId / taskId / traceId`
3. `vectorize()` 加状态 CAS
4. 重跑前清理旧 ES chunk

### 第四阶段：补可观测性

1. 状态字段与错误信息补齐
2. 管理端增加“任务详情 / 最后错误 / 重试次数”查看接口
3. 重要事件打统一结构化日志

---

## 14. 需要你确认的几个关键决策

### 决策 1：是否接受新增表

推荐：接受。  
如果不新增表，只靠给 `document_metadata` 打补丁，会把业务状态、任务状态、投递状态、消费状态混在一起，后期很难维护。

### 决策 2：删除是否改为软删除

推荐：改。  
这是解决“删主记录后补偿无从下手”的关键。

### 决策 3：是否接受 Outbox

推荐：接受。  
如果只是在现有 `KafkaTemplate.send()` 上阻塞等待 `get()`，能解决一部分投递确认问题，但仍解决不了“DB 提交后应用宕机，消息还没发出去”的经典丢消息窗口。

### 决策 4：是否新增 parse 结果中间主题

当前推荐先不强制新增。  
可以先继续使用 `doc-vectorize-request` 作为“parse 成功 + vectorize 开始”的桥接事件，降低改造面。

如果后续你希望 parse 结果也可独立展示或审计，再引入 `doc-parse-result`。

---

## 15. 本次改造后的预期收益

1. 文档不会再因为一次发送失败而无声卡死。
2. 删除链路不会再留下无法补偿的 ES 孤儿数据。
3. 重复消息不再重复消耗大额 embedding 资源。
4. 失败会真实落账，不再出现“明明失败了但库里还是旧状态”。
5. Python 与 Java 两端的消息语义会真正对齐。
6. 后续如果要加更多订阅者，例如审计、预览、摘要生成，也能沿用同一套可靠异步基建。

---

## 16. 建议的编码落地顺序

如果确认按本方案推进，我建议下一轮按下面顺序开始落代码：

1. 新增表结构与状态枚举
2. 新增 outbox dispatcher
3. 改造 `DocumentService` 的上传/删除事务
4. 新增 Kafka 配置类与手动 ack
5. 改造 `DocumentMessageListener`
6. 改造 `DocumentVectorizationService` 的失败事务与状态 CAS
7. 最后再补 Python Worker 契约文档与接口字段升级

