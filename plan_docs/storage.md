# 存储中心分工与规范 (Storage Architecture)

为了实现系统的高性能、极高吞吐量与弹性扩展，本项目严格划分了持久化存储层的职责，这三大利器各司其职，**数据禁止交叉冗余存放**。

## 1. MinIO (物理文件存储底座)
**定位**：海量非结构化异构文件的物理容器。
**存储内容**：
- 用户上传的原始企业文档（如 PDF, Word, PPT, TXT, 图片）。
- MinerU 解析后产出的巨型中间态文件（高精度的全量 Markdown、版面切割出的表格与图像切片）。
**特点**：极低成本、支持流式传输、支持 Presigned URL 等，完全卸载数据库与后端的 IO 压力。

## 2. MySQL (关系与元数据控制面)
**定位**：系统核心指针与长期认知。**绝对不存储文件具体文本内容。**
**存储内容**：
- **`document_metadata` (文档元数据指针)**：存储文档状态流转（UPLOADED -> PARSING -> VECTORIZED -> FAILED）、MinIO 文件坐标 URL、租户 ID（tenant_id）、知识库归属（kb_id）与安全权限访问列表（allowed_roles）。
- **`user_global_memory` (用户长期记忆)**：Agent 观察提炼后的用户习惯、长期客观事实。当新会话开启时，由系统读取并转换为 System Prompt 注入对话。
- **配置与安全账户**：用户表、RBAC角色关联表等（配合 Spring Security）。

## 3. Redis (高频态与短期记忆工作台)
**定位**：流转极快的缓存与 Session 状态流。
**存储内容**：
- ** Session Memory (上下文 L1 缓存)**：存储对话上下游中的最新 5 轮原文对话 (`CustomChatMemoryStore`)，以供给大语言模型的记忆。 
- ** L2 / L3 缓存**：当对话到达中长期阶段时，将大模型压缩后的总结摘要和核心 Entity 也会短期存在与此，随 Session TTL 周期释放。
- 频控记录、分布式锁与热点元数据（如租户在线状态）。

## 4. ElasticSearch (8.x+) (语义检索心智)
**定位**：混合语义检索引擎（不属于本 Storage 的三大存储但至关重要）。
**存储内容**：仅用于存放解析、脱敏、打标完成的知识库 Chunk (包括全文 BM25 倒排索引和 BGE Dense Vector 高维稠密向量)。
