# MinerU API 文档

> 基于 OpenAPI 3.1.0 规范  
> 服务器地址：`http://10.60.23.4:8000`

---

## 系统信息

| 属性 | 值 |
|------|-----|
| API 版本 | 0.1.0 |
| OpenAPI 版本 | 3.1.0 |
| 基础 URL | http://10.60.23.4:8000 |

---

## 接口列表

### 1. POST /file_parse - 同步解析文件

**描述**: 提交解析任务到异步任务管理器，等待完成后返回最终的解析结果。

#### 请求

**Content-Type**: `multipart/form-data`

| 参数 | 类型 | 必填 | 默认值 | 描述 |
|------|------|------|--------|------|
| files | file[] | **是** | - | 上传 PDF 或图片文件进行解析 |
| lang_list | string[] | 否 | `["ch"]` | (仅适用于 pipeline 和 hybrid backend) 输入 PDF 中的语言以提高 OCR 准确率<br>可选值：<br>- `ch`: 中文、英文、繁体中文<br>- `ch_lite`: 中文、英文、繁体中文、日文<br>- `ch_server`: 中文、英文、繁体中文、日文<br>- `en`: 英文<br>- `korean`: 韩文、英文<br>- `japan`: 中文、英文、繁体中文、日文<br>- `chinese_cht`: 中文、英文、繁体中文、日文<br>- `ta`: 泰米尔文、英文<br>- `te`: 泰卢固文、英文<br>- `ka`: 卡纳达文<br>- `th`: 泰语、英文<br>- `el`: 希腊文、英文<br>- `latin`: 法文、德文、南非荷兰语、意大利语、西班牙语、波斯尼亚语、葡萄牙语、捷克语、威尔士语、丹麦语、爱沙尼亚语、爱尔兰语、克罗地亚语、乌兹别克语、匈牙利语、塞尔维亚语 (拉丁)、印度尼西亚语、奥克语、冰岛语、立陶宛语、毛利语、马来语、荷兰语、挪威语、波兰语、斯洛伐克语、斯洛文尼亚语、阿尔巴尼亚语、瑞典语、斯瓦希里语、他加禄语、土耳其语、拉丁语、阿塞拜疆语、库尔德语、拉脱维亚语、马耳他语、巴利语、罗马尼亚语、越南语、芬兰语、巴斯克语、加利西亚语、卢森堡语、罗曼什语、加泰罗尼亚语、克丘亚语<br>- `arabic`: 阿拉伯语、波斯语、维吾尔语、乌尔都语、普什图语、库尔德语、信德语、俾路支语、英文<br>- `east_slavic`: 俄语、白俄罗斯语、乌克兰语、英文<br>- `cyrillic`: 俄语、白俄罗斯语、乌克兰语、塞尔维亚语 (西里尔)、保加利亚语、蒙古语、阿布哈兹语、阿迪格语、卡巴尔达语、阿瓦尔语、达尔金语、因格什语、车臣语、拉克语、列兹金语、塔巴萨兰语、哈萨克语、吉尔吉斯语、塔吉克语、马其顿语、鞑靼语、楚瓦什语、巴什基尔语、马里语、摩尔多瓦语、乌德穆尔特语、科米语、奥塞梯语、布里亚特语、卡尔梅克语、图瓦语、萨哈语、卡拉卡尔帕克语、英文<br>- `devanagari`: 印地语、马拉地语、尼泊尔语、比哈尔语、迈蒂利语、安加语、博杰普尔语、马加赫语、桑塔利语、新阿拉语、孔卡尼语、梵语、哈里亚纳语、英文 |
| backend | string | 否 | `hybrid-auto-engine` | 解析后端：<br>- `pipeline`: 更通用，支持多种语言，无幻觉<br>- `vlm-auto-engine`: 通过本地计算资源实现高精度，仅支持中英文文档<br>- `vlm-http-client`: 通过远程计算资源实现高精度 (适用于 openai 兼容服务器)，仅支持中英文文档<br>- `hybrid-auto-engine`: 通过本地计算资源实现下一代高精度方案，支持多种语言<br>- `hybrid-http-client`: 通过远程计算资源实现高精度但需要少量本地计算资源 (适用于 openai 兼容服务器)，支持多种语言 |
| parse_method | string | 否 | `auto` | (仅适用于 pipeline 和 hybrid backend) PDF 解析方法：<br>- `auto`: 根据文件类型自动选择方法<br>- `txt`: 使用文本提取方法<br>- `ocr`: 对图像型 PDF 使用 OCR 方法 |
| formula_enable | boolean | 否 | `true` | 启用公式解析 |
| table_enable | boolean | 否 | `true` | 启用表格解析 |
| server_url | string \| null | 否 | - | (仅适用于 <vlm/hybrid>-http-client backend) openai 兼容服务器地址，如 `http://127.0.0.1:30000` |
| return_md | boolean | 否 | `true` | 在响应中返回 Markdown 内容 |
| return_middle_json | boolean | 否 | `false` | 在响应中返回中间 JSON |
| return_model_output | boolean | 否 | `false` | 在响应中返回模型输出 JSON |
| return_content_list | boolean | 否 | `false` | 在响应中返回内容列表 JSON |
| return_images | boolean | 否 | `false` | 在响应中返回提取的图片 |
| response_format_zip | boolean | 否 | `false` | 以 ZIP 文件形式返回结果而不是 JSON |
| return_original_file | boolean | 否 | `false` | 在 ZIP 结果中包含处理的原始输入文件；除非 `response_format_zip=true`，否则忽略 |
| start_page_id | integer | 否 | `0` | PDF 解析的起始页码，从 0 开始 |
| end_page_id | integer | 否 | `99999` | PDF 解析的结束页码，从 0 开始 |

#### 响应

**成功响应 (200)**

```json
{
  "status": "success",
  "data": {
    "markdown": "# 解析后的 Markdown 内容...",
    "middle_json": {...},
    "model_output": {...},
    "content_list": [...],
    "images": {...}
  }
}
```

**验证错误 (422)**

```json
{
  "detail": [
    {
      "loc": ["body", "files"],
      "msg": "field required",
      "type": "value_error.missing"
    }
  ]
}
```

#### cURL 示例

```bash
# 基本用法 - 上传单个 PDF 文件
curl -X POST "http://10.60.23.4:8000/file_parse" \
  -F "files=@/path/to/document.pdf"

# 上传多个文件并指定语言
curl -X POST "http://10.60.23.4:8000/file_parse" \
  -F "files=@/path/to/doc1.pdf" \
  -F "files=@/path/to/doc2.pdf" \
  -F "lang_list=en" \
  -F "backend=pipeline"

# 启用公式和表格解析，返回 Markdown
curl -X POST "http://10.60.23.4:8000/file_parse" \
  -F "files=@/path/to/document.pdf" \
  -F "formula_enable=true" \
  -F "table_enable=true" \
  -F "return_md=true"

# 指定解析页面范围 (第 1-10 页)
curl -X POST "http://10.60.23.4:8000/file_parse" \
  -F "files=@/path/to/document.pdf" \
  -F "start_page_id=0" \
  -F "end_page_id=9"

# 使用 vlm-http-client 后端
curl -X POST "http://10.60.23.4:8000/file_parse" \
  -F "files=@/path/to/document.pdf" \
  -F "backend=vlm-http-client" \
  -F "server_url=http://127.0.0.1:30000"

# 返回 ZIP 格式结果
curl -X POST "http://10.60.23.4:8000/file_parse" \
  -F "files=@/path/to/document.pdf" \
  -F "response_format_zip=true" \
  -F "return_original_file=true" \
  -o result.zip
```

---

### 2. POST /tasks - 提交异步解析任务

**描述**: 提交文件进行解析并立即返回，通过任务 ID 检查任务状态和结果。

#### 请求

**Content-Type**: `multipart/form-data`

| 参数 | 类型 | 必填 | 默认值 | 描述 |
|------|------|------|--------|------|
| files | file[] | **是** | - | 上传 PDF 或图片文件进行解析 |
| lang_list | string[] | 否 | `["ch"]` | (仅适用于 pipeline 和 hybrid backend) 输入 PDF 中的语言以提高 OCR 准确率 |
| backend | string | 否 | `hybrid-auto-engine` | 解析后端选项 |
| parse_method | string | 否 | `auto` | PDF 解析方法 |
| formula_enable | boolean | 否 | `true` | 启用公式解析 |
| table_enable | boolean | 否 | `true` | 启用表格解析 |
| server_url | string \| null | 否 | - | openai 兼容服务器地址 |
| return_md | boolean | 否 | `true` | 在响应中返回 Markdown 内容 |
| return_middle_json | boolean | 否 | `false` | 在响应中返回中间 JSON |
| return_model_output | boolean | 否 | `false` | 在响应中返回模型输出 JSON |
| return_content_list | boolean | 否 | `false` | 在响应中返回内容列表 JSON |
| return_images | boolean | 否 | `false` | 在响应中返回提取的图片 |
| response_format_zip | boolean | 否 | `false` | 以 ZIP 文件形式返回结果 |
| return_original_file | boolean | 否 | `false` | 在 ZIP 结果中包含原始输入文件 |
| start_page_id | integer | 否 | `0` | 起始页码，从 0 开始 |
| end_page_id | integer | 否 | `99999` | 结束页码，从 0 开始 |

#### 响应

**成功响应 (202 Accepted)**

```json
{
  "task_id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "pending"
}
```

**验证错误 (422)**

```json
{
  "detail": [
    {
      "loc": ["body", "files"],
      "msg": "field required",
      "type": "value_error.missing"
    }
  ]
}
```

#### cURL 示例

```bash
# 提交异步解析任务
curl -X POST "http://10.60.23.4:8000/tasks" \
  -F "files=@/path/to/document.pdf"

# 提交任务并指定参数
curl -X POST "http://10.60.23.4:8000/tasks" \
  -F "files=@/path/to/document.pdf" \
  -F "lang_list=en" \
  -F "backend=hybrid-auto-engine" \
  -F "formula_enable=true" \
  -F "table_enable=true"

# 提交多个文件
curl -X POST "http://10.60.23.4:8000/tasks" \
  -F "files=@/path/to/doc1.pdf" \
  -F "files=@/path/to/doc2.png" \
  -F "files=@/path/to/doc3.jpg"
```

---

### 3. GET /tasks/{task_id} - 获取任务状态

**描述**: 查询异步任务的当前状态。

#### 参数

| 参数 | 位置 | 类型 | 必填 | 描述 |
|------|------|------|------|------|
| task_id | path | string | **是** | 任务 ID |

#### 响应

**成功响应 (200)**

```json
{
  "task_id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "processing",
  "progress": 50,
  "message": "Parsing page 5 of 10..."
}
```

**状态说明**:
- `pending`: 任务已提交，等待处理
- `processing`: 任务正在处理中
- `completed`: 任务已完成
- `failed`: 任务失败

**验证错误 (422)**

```json
{
  "detail": [
    {
      "loc": ["path", "task_id"],
      "msg": "field required",
      "type": "value_error.missing"
    }
  ]
}
```

#### cURL 示例

```bash
# 查询任务状态
curl -X GET "http://10.60.23.4:8000/tasks/550e8400-e29b-41d4-a716-446655440000"

# 使用 jq 格式化输出
curl -s "http://10.60.23.4:8000/tasks/550e8400-e29b-41d4-a716-446655440000" | jq .
```

---

### 4. GET /tasks/{task_id}/result - 获取任务结果

**描述**: 获取已完成异步任务的解析结果。

#### 参数

| 参数 | 位置 | 类型 | 必填 | 描述 |
|------|------|------|------|------|
| task_id | path | string | **是** | 任务 ID |

#### 响应

**成功响应 (200)**

```json
{
  "task_id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "completed",
  "data": {
    "markdown": "# 解析后的 Markdown 内容...\n\n## 章节 1\n\n这是解析后的文本内容...",
    "middle_json": {
      "pages": [...],
      "layout": [...]
    },
    "model_output": {...},
    "content_list": [...],
    "images": {
      "image_1.png": "base64_encoded_data",
      "image_2.png": "base64_encoded_data"
    }
  }
}
```

**任务未完成 (可能返回 404 或 400)**

```json
{
  "detail": "Task not completed yet"
}
```

**验证错误 (422)**

```json
{
  "detail": [
    {
      "loc": ["path", "task_id"],
      "msg": "field required",
      "type": "value_error.missing"
    }
  ]
}
```

#### cURL 示例

```bash
# 获取任务结果
curl -X GET "http://10.60.23.4:8000/tasks/550e8400-e29b-41d4-a716-446655440000/result"

# 获取结果并保存为文件
curl -s "http://10.60.23.4:8000/tasks/550e8400-e29b-41d4-a716-446655440000/result" \
  | jq -r '.data.markdown' > output.md

# 获取完整 JSON 结果
curl -s "http://10.60.23.4:8000/tasks/550e8400-e29b-41d4-a716-446655440000/result" \
  > result.json
```

---

### 5. GET /health - 健康检查

**描述**: 检查 API 服务是否正常运行。

#### 参数

无

#### 响应

**成功响应 (200)**

```json
{
  "status": "healthy",
  "timestamp": "2026-04-09T02:35:00Z",
  "version": "0.1.0"
}
```

#### cURL 示例

```bash
# 基本健康检查
curl -X GET "http://10.60.23.4:8000/health"

# 格式化输出
curl -s "http://10.60.23.4:8000/health" | jq .
```

---

## 错误响应格式

所有接口在验证失败时返回统一的错误格式：

### HTTPValidationError (422)

```json
{
  "detail": [
    {
      "loc": ["body", "files"],
      "msg": "field required",
      "type": "value_error.missing"
    },
    {
      "loc": ["body", "lang_list", 0],
      "msg": "string type expected",
      "type": "type_error.str"
    }
  ]
}
```

**字段说明**:
- `loc`: 错误位置数组，可以是字符串 (字段名) 或整数 (数组索引)
- `msg`: 错误消息
- `type`: 错误类型

---

## 使用流程示例

### 同步解析流程

```bash
# 1. 直接上传文件并等待结果
RESPONSE=$(curl -s -X POST "http://10.60.23.4:8000/file_parse" \
  -F "files=@document.pdf" \
  -F "backend=hybrid-auto-engine" \
  -F "return_md=true")

# 2. 提取 Markdown 内容
echo "$RESPONSE" | jq -r '.data.markdown' > output.md
```

### 异步解析流程

```bash
# 1. 提交任务
TASK_RESPONSE=$(curl -s -X POST "http://10.60.23.4:8000/tasks" \
  -F "files=@document.pdf" \
  -F "backend=hybrid-auto-engine")

# 2. 提取任务 ID
TASK_ID=$(echo "$TASK_RESPONSE" | jq -r '.task_id')
echo "Task ID: $TASK_ID"

# 3. 轮询任务状态
while true; do
  STATUS=$(curl -s "http://10.60.23.4:8000/tasks/$TASK_ID")
  TASK_STATUS=$(echo "$STATUS" | jq -r '.status')
  
  echo "Status: $TASK_STATUS"
  
  if [ "$TASK_STATUS" = "completed" ]; then
    break
  elif [ "$TASK_STATUS" = "failed" ]; then
    echo "Task failed!"
    exit 1
  fi
  
  sleep 5
done

# 4. 获取结果
RESULT=$(curl -s "http://10.60.23.4:8000/tasks/$TASK_ID/result")
echo "$RESULT" | jq -r '.data.markdown' > output.md
```

### 轮询任务状态脚本

```bash
#!/bin/bash

TASK_ID=$1
BASE_URL="http://10.60.23.4:8000"

if [ -z "$TASK_ID" ]; then
  echo "Usage: $0 <task_id>"
  exit 1
fi

echo "Waiting for task $TASK_ID to complete..."

while true; do
  RESPONSE=$(curl -s "$BASE_URL/tasks/$TASK_ID")
  STATUS=$(echo "$RESPONSE" | jq -r '.status')
  PROGRESS=$(echo "$RESPONSE" | jq -r '.progress // "N/A"')
  MESSAGE=$(echo "$RESPONSE" | jq -r '.message // ""')
  
  printf "\rStatus: %-12s Progress: %s%% %s" "$STATUS" "$PROGRESS" "$MESSAGE"
  
  if [ "$STATUS" = "completed" ]; then
    echo -e "\n✓ Task completed!"
    curl -s "$BASE_URL/tasks/$TASK_ID/result" | jq -r '.data.markdown' > output.md
    echo "Result saved to output.md"
    break
  elif [ "$STATUS" = "failed" ]; then
    echo -e "\n✗ Task failed!"
    echo "$RESPONSE" | jq .
    exit 1
  fi
  
  sleep 3
done
```

---

## 参数详解

### backend 参数选择指南

| 后端类型 | 适用场景 | 语言支持 | 精度 | 资源需求 |
|----------|----------|----------|------|----------|
| `pipeline` | 通用场景，多语言文档 | 多语言 | 中等 | 低 |
| `vlm-auto-engine` | 本地高精度需求 | 中英文 | 高 | 高 (本地 GPU) |
| `vlm-http-client` | 远程高精度需求 | 中英文 | 高 | 低 (本地) + 远程服务 |
| `hybrid-auto-engine` | 本地多语言高精度 | 多语言 | 高 | 中等 (本地 GPU) |
| `hybrid-http-client` | 远程多语言高精度 | 多语言 | 高 | 低 (本地) + 远程服务 |

### lang_list 参数语言代码

| 代码 | 支持语言 |
|------|----------|
| `ch` | 中文、英文、繁体中文 |
| `en` | 英文 |
| `korean` | 韩文、英文 |
| `japan` | 日文、中文、英文、繁体中文 |
| `arabic` | 阿拉伯语、波斯语、维吾尔语等 |
| `cyrillic` | 俄语、乌克兰语、白俄罗斯语等 |
| `devanagari` | 印地语、马拉地语、尼泊尔语等 |
| `latin` | 西欧语言 (法、德、西、意等) |

---

## 响应数据格式

### Markdown 返回格式

当 `return_md=true` 时，返回的 Markdown 包含：

```markdown
# 文档标题

## 章节 1

正文内容...

### 子章节

- 列表项 1
- 列表项 2

## 表格

| 列 1 | 列 2 |
|-----|-----|
| 数据 | 数据 |

## 公式

$$ E = mc^2 $$
```

### ZIP 返回格式

当 `response_format_zip=true` 时，ZIP 文件包含：

```
result.zip
├── document.pdf (原始文件，如果 return_original_file=true)
├── document.md (Markdown 结果)
├── document_middle.json (中间 JSON)
├── document_model_output.json (模型输出)
├── document_content_list.json (内容列表)
└── images/
    ├── page_1_image_1.png
    ├── page_1_image_2.png
    └── page_2_image_1.png
```

---

## 注意事项

1. **文件大小限制**: 服务器可能对上传文件有大小限制
2. **并发限制**: 异步任务可能有并发数量限制
3. **超时设置**: 同步接口可能有超时限制，大文件建议使用异步接口
4. **页面范围**: `start_page_id` 和 `end_page_id` 从 0 开始计数
5. **后端选择**: 根据文档语言和可用资源选择合适的 backend

---

*文档生成时间：2026-04-09*
