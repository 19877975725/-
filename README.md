# Voice Assistant

AI-powered voice assistant platform with LLM + Agent + RAG + TTS.

## Quick Start

```powershell
.\start.ps1
```

[Click here to start →](http://localhost:5173)

| Service | Port |
|----------|------|
| Milvus | 19530 |
| Backend | 8080 |
| Frontend | 5173 |

Requirements: Java 17+, Maven 3.9+, MySQL 8.0+, Node.js 24+, Python 3.12+

---

# 5.10.1

###  新建SentenceBoundaryDetector.java

```java
private static final Pattern SENTENCE_END = Pattern.compile("[。！？,.!?]");
```

- `feed(token)`: 将 token 追加到内部 `StringBuilder` buffer，用正则匹配 buffer 中最后一个句末标点位置。若找到且不在开头，截取 `[0, lastEnd)` 作为完整句子返回，剩余部分留在 buffer。若未找到返回 `null`。
- `flush()`: 返回 buffer 中剩余文本并清空，buffer 为空时返回 `null`。

###  修改 WebSocketMessage.java

在 `Command` 枚举中，`TTS` 之后新增两项：

```java
TTS_SENTENCE,      // 分句TTS（逐句发送）
TTS_STREAM_DONE,   // 分句TTS结束
```

### 修改 AsrFinalCommandHandler.java

**import 新增：** 第 7 行加入 `SentenceBoundaryDetector`

**`processWithRAG` 方法（行 125-155）：** 删除了原来 `sendTtsToPbx(session, originalMessage, fullAnswer)` 的整段发送逻辑，改为：

- 方法入口创建 `detector` + `seq` 计数器
- `onToken` 回调中：`sendStreamToken` 之后调用 `detector.feed(token)`，若返回句子则 `sendTtsSentence`
- `onComplete` 回调中：`sendStreamEnd` + 保存历史 之后调用 `detector.flush()`，若有剩余文本则 `sendTtsSentence`，最后 `sendTtsStreamDone`

**`processWithoutRAG` 方法（行 184–207）：** 同上，在 `agent.runStream` 的 `onToken` / `onComplete` 中嵌入相同的分句检测逻辑。

**删除方法：** 原来的 `sendTtsToPbx`（一次发送全文给 PBX，走 `TTS` 命令）

**新增方法：**

- `sendTtsSentence(session, originalMessage, sentence, sequence)` — 构建 `TTS_SENTENCE` 消息，payload 含 `text` 和 `sequence`
- `sendTtsStreamDone(session, originalMessage)` — 构建 `TTS_STREAM_DONE` 消息，payload 含空 `text`

# 5.10.2

### 新建

**WebClientConfig.java** — 替代 RestTemplateConfig

- Reactor Netty `ConnectionProvider`，连接池 200 连接、空闲 60s 回收、生命周期 300s
- `readTimeout` 120s（适配 LLM 长响应）、`writeTimeout` 30s、`connectTimeout` 10s

### 删除

**RestTemplateConfig.java** — 移除

### 重写

**QwenClient.java**

- 构造函数注入 `WebClient` 替代 `@RequiredArgsConstructor` + `RestTemplate`
- 3 个同步方法 (`chat` / `chatWithTools` / `chatWithMessages`) 统一走 `postForResponse()` — `webClient.post()...bodyToMono().block()`
- 流式方法 `executeStreamRequest()` — `webClient.post().accept(TEXT_EVENT_STREAM)...bodyToFlux(String.class).blockLast()`，用 `doOnNext` 逐 chunk 回调
- 新增 `processSseChunk()` 处理跨 chunk 不完整 SSE 行（`lineBuffer` 累加 → 找到 `\n` → 按行解析 `data:` 前缀 → fastjson2 解析 delta.content）
- 异常类型：`HttpClientErrorException` / `ResourceAccessException` → `WebClientResponseException` / `WebClientRequestException`

**WeatherTool.java**

- `RestTemplate restTemplate` → `WebClient webClient`
- `restTemplate.exchange(uri, GET, entity, String.class).getBody()` → `webClient.get().uri(uri).header(...).retrieve().bodyToMono(String.class).block()`
- 移除 `HttpEntity`、`HttpHeaders`、`ResponseEntity` 等模板式代码

### 依赖

**pom.xml** — 新增 `spring-boot-starter-webflux`（仅用 WebClient，不影响现有 Tomcat servlet 容器）

# 5.11.1

## 修改清单

| 文件                        | 操作     | 说明                                          |
| --------------------------- | -------- | --------------------------------------------- |
| QueryIntent.java            | **新建** | 三分类枚举：KNOWLEDGE / CHAT / COMMAND        |
| IntentRouter.java           | **新建** | 意图路由器，规则+LLM 两级分类                 |
| RAGConfig.java              | **修改** | 新增 `autoRoute`、`minScore` 两个策略配置项   |
| application.properties      | **修改** | 新增 `rag.auto-route`、`rag.min-score` 默认值 |
| RAGQAService.java           | **修改** | 新增 `hasRelevantResults()` 相关性阈值过滤    |
| AsrFinalCommandHandler.java | **修改** | 注入 IntentRouter，三层漏斗决策取代一刀切     |

------

## 完整流程：用户一句话从输入到回答的全路径



```
用户说："帮我查一下合同条款"
  │
  ▼
┌─────────────────────────────────────────────────┐
│  AsrFinalCommandHandler.processWithRAGFramework │
│                                                  │
│  ① 第一层：RAG 总开关                             │
│     ragConfig.isEnabled() == false → 普通对话     │
│                                                  │
│  ② 第二层：意图路由 (IntentRouter.classify)        │
│     ├─ 规则匹配：先走关键词/正则，命中 → 直接返回   │
│     │   "帮我查" → KNOWLEDGE_PATTERN 命中          │
│     │   返回 QueryIntent.KNOWLEDGE                 │
│     │                                             │
│     └─ 规则未命中 → LLM 轻量分类（~150 token）     │
│         prompt："判断意图，只回复一个字母 A/B/C"    │
│                                                  │
│  ③ 按意图分流                                     │
│     KNOWLEDGE → processWithRAG()                  │
│     CHAT      → processWithoutRAG()               │
│     COMMAND   → processWithoutRAG()（可扩展）       │
└──────────────────┬──────────────────────────────┘
                   ▼
┌─────────────────────────────────────────────────┐
│  RAGQAService.ragAnswerStream()                  │
│                                                  │
│  ④ 查询预处理 (QueryPreprocessor)                 │
│     用户输入 → LLM 返回 JSON → 解析为查询列表      │
│     "帮我查一下合同条款"                            │
│     → {"queries": ["合同条款","合同内容","合同规定"]}│
│                                                  │
│  ⑤ 多查询向量检索                                  │
│     searchWithMultipleQueries(queries, topK)      │
│     → 返回相似文档列表（含相似度分数）              │
│                                                  │
│  ⑥ 第三层：相关性阈值过滤 (hasRelevantResults)      │
│     最高相似度 < minScore(0.55) → "无相关文档"降级  │
│     最高相似度 ≥ 0.55 → 文档有效，进入 Prompt 构建  │
│                                                  │
│  ⑦ Prompt 构建 → LLM 流式生成 → TTS 逐句播放       │
└─────────────────────────────────────────────────┘
```

------

## 各方法的实现讲解

### 1. `IntentRouter.classify(String query)` — 意图分类入口



```java
public QueryIntent classify(String query) {
    // 第一步：规则快速匹配（0 token，0 延迟）
    QueryIntent ruleResult = ruleMatch(q);
    if (ruleResult != null) return ruleResult;

    // 第二步：LLM 轻量分类（只在规则无法判断时触发）
    return llmClassify(q);
}
```

**设计要点**：规则优先、LLM 兜底。为什么这样做？

- 规则匹配对着"你好""谢谢""帮我查"这类明确信号，准确率接近 100%，且不消耗 Token
- LLM 分类用极短 prompt（约 150 tokens），比一次完整的 RAG 检索便宜得多
- 闲聊被规则拦截后，完全不会触发后续的查询预处理和向量检索，节省大量开销

### 2. `IntentRouter.ruleMatch(String query)` — 规则匹配

三个维度：

- **闲聊白名单**：精确匹配问候语、感谢语、常见闲聊
- **命令正则**：匹配"打开/关闭/设置"等操作动词
- **知识查询正则**：匹配"查/搜索/合同/会议/规定/怎么/如何"等信号词

返回 `null` 表示"我判断不了"，交给 LLM。

### 3. `IntentRouter.llmClassify(String query)` — LLM 分类



```java
private QueryIntent llmClassify(String query) {
    String result = llmClient.chat(query, INTENT_CLASSIFY_PROMPT);
    // 解析 LLM 回复的第一个字母 A/B/C
    if (result.contains("A")) return KNOWLEDGE;
    if (result.contains("C")) return COMMAND;
    return CHAT;  // 默认保守降级
}
```

**容错策略**：LLM 调用失败 → 降级为 `CHAT`。这比降级为 `KNOWLEDGE` 更安全——因为闲聊走 RAG 只会浪费资源，但知识查询不走 RAG 会导致回答质量差。不过 LLM 分类失败的几率很低，且规则已覆盖了高置信度场景。

### 4. `RAGQAService.hasRelevantResults(List<SearchResult>)` — 相关性阈值



```java
private boolean hasRelevantResults(List<SearchResult> results) {
    if (results == null || results.isEmpty()) return false;
    float maxScore = 0f;
    for (SearchResult r : results) maxScore = Math.max(maxScore, r.getScore());
    return maxScore >= ragConfig.getMinScore();  // 默认 0.55
}
```

**为什么需要这个**：向量数据库会为任何查询返回"最相似"的文档，哪怕内容完全不相关。比如用户问"今天天气怎么样"，向量库中最近的文档可能是某个合同条款（相似度 0.32），如果不过滤直接喂给 LLM，反而产生幻觉。阈值 0.55 是一个保守的起点，可以根据你实际数据的分数分布调优。

### 5. `AsrFinalCommandHandler.processWithRAGFramework()` — 决策中心

现在是三层漏斗：



```
总开关(false) → 普通对话（快速退出）
    ↓
autoRoute(true) → IntentRouter 判断意图
    ├─ CHAT    → 普通对话（跳过所有 RAG 开销）
    ├─ COMMAND → 普通对话（预留给工具调用扩展）
    └─ KNOWLEDGE → 进 RAG 管道
autoRoute(false) → 兼容模式，全部走 RAG
```

------

## 关键设计决策

| 决策点               | 选择         | 原因                                   |
| -------------------- | ------------ | -------------------------------------- |
| 意图分类先规则后 LLM | 规则优先     | 80% 场景规则可覆盖，0 Token 开销       |
| LLM 分类失败降级     | 降级为 CHAT  | 保守策略，避免无效 RAG 开销            |
| 相关性阈值默认值     | 0.55         | 经验值，需要根据实际数据调参           |
| autoRoute 开关       | 可关闭       | 保留向后兼容，关闭后行为与原来完全一致 |
| COMMAND 意图处理     | 暂走普通对话 | 预留扩展点，以后可接入工具调用         |

# 5.12.1

### 新增 6 个文件

| 文件                                       | 作用                                                         |
| ------------------------------------------ | ------------------------------------------------------------ |
| rag/evaluation/EvaluationQuery.java        | 标注查询数据模型：query、期望检索到的文档 ID、期望意图       |
| rag/evaluation/QueryEvaluationDetail.java] | 单条查询的评估明细（检索排名、被过滤状态、意图分类结果）     |
| rag/evaluation/EvaluationResult.java       | 评估结果汇总：Recall@1/3/5、MRR、Intent Accuracy、Filter Rate |
| rag/evaluation/RAGEvaluationService.java   | **核心**：离线评估引擎，对每条标注查询执行检索+意图路由，计算四项指标 |
| [rag/evaluation/RAGMetricsService.java     | **核心**：运行时指标收集器，用 `AtomicLong` + `ConcurrentHashMap` 实时统计请求量、意图分布、过滤率、降级率 |
| controller/EvaluationController.java       | REST 接口：`POST /api/evaluation/run` 离线评估、`GET /api/evaluation/metrics` 实时指标快照 |

### 新增 1 个资源文件

| 文件                                         | 作用                                                         |
| -------------------------------------------- | ------------------------------------------------------------ |
| resources/rag-evaluation/sample-dataset.json | 10 条标注样本，覆盖 KNOWLEDGE/CHAT 两类意图，用占位ID标注期望文档 |

### 修改 2 个文件

| 文件                                                         | 变更                                                         |
| ------------------------------------------------------------ | ------------------------------------------------------------ |
| [rag/service/RAGQAService.java](vscode-webview://1cbj3sfrkenps9d6d4evrlfcalqlthn6bukobdcop779sruqghro/src/main/java/org/example/voice_assistant/rag/service/RAGQAService.java) | 注入 `RAGMetricsService`，在检索、阈值过滤、成功/降级节点记录指标 |
| [handler/AsrFinalCommandHandler.java](vscode-webview://1cbj3sfrkenps9d6d4evrlfcalqlthn6bukobdcop779sruqghro/src/main/java/org/example/voice_assistant/handler/AsrFinalCommandHandler.java) | 注入 `RAGMetricsService`，在意图路由分发处记录请求数和意图分布 |

------

## 评估体系说明

### 功能作用

评估体系包含 **离线评估** 和 **在线指标** 两部分：

**离线评估**（`POST /api/evaluation/run`）—— 回答"我的 RAG 系统整体好不好"：

- 用人工标注的数据集对检索质量进行量化打分
- 对比不同参数/模型/切分策略下的效果差异

**在线指标**（`GET /api/evaluation/metrics`）—— 回答"我的 RAG 系统现在运行得怎么样"：

- 实时统计三层漏斗各环节的流量分布
- 发现异常波动（如过滤率突然飙升 = 知识库可能有问题）

### 实现方式

1. **标注数据集**（JSON 文件）：每条记录含 `query`、`relevantDocIds`（人工标的相关文档ID）、`expectedIntent`（期望意图分类）
2. **Recall@K**：对每条 query 执行真实 Milvus 检索，检查 Top-K 结果中是否包含标注的文档 ID
3. **MRR**：找到第一个相关文档的排名 `rank`，计算 `1/rank` 的均值
4. **Intent Accuracy**：`IntentRouter.classify()` 结果与 `expectedIntent` 对比
5. **运行时指标**：用 `AtomicLong` 做无锁计数，在 `RAGQAService` 和 `AsrFinalCommandHandler` 的关键路径节点埋点

### 好处

1. **告别盲调参数** — `minScore=0.55` 不是拍脑袋的，可以跑评估看不同阈值下的 Recall/Filter Rate 权衡
2. **换模型可对比** — 换 embedding 模型、换切分策略后重跑评估，有数据支撑决策
3. **意图路由可量化** — 之前只能靠感觉说"规则覆盖 80%"，现在可以精确算 Intent Accuracy
4. **线上可观测** — 实时指标告诉你什么比例的请求真正受益于 RAG，什么比例被过滤或降级
5. **持续改进闭环** — 定期更新标注数据集 → 重跑评估 → 观察指标趋势 → 调优 → 再评估

# 5.12.2

### 修改了 3 个文件

| 文件                                                         | 变更                                                         |
| ------------------------------------------------------------ | ------------------------------------------------------------ |
| [EvaluationQuery.java](vscode-webview://1cbj3sfrkenps9d6d4evrlfcalqlthn6bukobdcop779sruqghro/src/main/java/org/example/voice_assistant/rag/evaluation/EvaluationQuery.java) | `relevantDocIds` → `relevantSources`，注释改为匹配 metadata 中的 `_source`/`_file_name` |
| [RAGEvaluationService.java](vscode-webview://1cbj3sfrkenps9d6d4evrlfcalqlthn6bukobdcop779sruqghro/src/main/java/org/example/voice_assistant/rag/evaluation/RAGEvaluationService.java) | 新增 `extractSource()` 从 metadata JSON 解析 `_file_name`/`_source`；新增 `matchesAnySource()` 做模糊匹配（文件名可匹配完整路径）；重写 `evaluateSingleQuery()` 用 source 而非 UUID 做排位计算 |
| [sample-dataset.json](vscode-webview://1cbj3sfrkenps9d6d4evrlfcalqlthn6bukobdcop779sruqghro/src/main/resources/rag-evaluation/sample-dataset.json) | 字段名改为 `relevantSources`，占位注释改为"文件名或路径"，q1 已用真实文件名"阿里云百炼手机产品介绍.md" |

### 匹配逻辑

```
标注数据集:  relevantSources = ["阿里云百炼手机产品介绍.md", "合同.pdf"]
                          ↓
Milvus检索结果 → 解析 metadata → _file_name = "阿里云百炼手机产品介绍.md"
                                            ↓
                               contains("阿里云百炼手机产品介绍介绍.md") = true → 命中
```

用 `contains` 而非 `equals` 是为了兼容：你可以标注完整路径 `./uploads/合同.pdf`，也可以只写文件名 `合同.pdf`，都能匹配上。

# 5.13.1

总结一下三处改动：

### DocumentChunkConfig.java — 新增 `minSize` 配置项

- 添加了 `minSize = 150`，分片内容少于 150 字符时自动与相邻分片合并
- 添加了 `@ConfigurationProperties(prefix = "rag.chunk")`，支持在 application.properties 中通过 `rag.chunk.min-size` 等配置覆盖

### DocumentChunkService.java — 新增 `mergeSmallChunks` 方法

核心逻辑：

- 遍历所有分片，遇到低于 `minSize` 的就暂存到 buffer
- 碰到正常大小的分片时，将之前积攒的小分片**前置合并**进去
- 尾部剩余的小分片合并到最后一个正常分片末尾
- 合并后重新编号 `chunkIndex`

这样 "## 四、会员相关" 这种只有标题没正文的片段，会自动合并到下一个有内容的段落中，不会再作为独立分片被检索到。

### RAGQAService.java — 修复误导标签

- `相似度: 4734.45` → `L2距离: 4734.45, 越小越匹配`

**注意**：你需要重建索引才能看到分片合并的效果。已经入库的旧分片不会自动更新，需要重新上传文档触发重新分片。

# 5.13.2

## 修改清单

### 1. 新增ChunkTokenizer.java— Token 计数 + 文本切分工具

| 方法                                    | 作用                                                         |
| --------------------------------------- | ------------------------------------------------------------ |
| `countTokens(text)`                     | 启发式估算 token 数，区分 CJK 字符（1 字符≈1 token）和英文单词（1 单词≈1.3 token）。不引入外部依赖 |
| `splitSentences(text)`                  | 按句末标点（。！？!?）切分句子                               |
| `splitParagraphs(text)`                 | 按双换行+切分段落                                            |
| `extractLastSentences(text, maxTokens)` | 从末尾提取完整的 N 个句子作为 overlap，**绝不截断句子**      |

**为什么这样做**：之前用 `text.substring(text.length() - overlapSize)` 直接截字符，可能从句子中间砍断，导致 overlap 语义破碎。新方法保证 overlap 始终是完整句子。

------

### 2. 重写DocumentChunkConfig.java— Token-based 配置



```
旧: maxSize=800(char), overlap=100(char), minSize=150(char)
新: targetSize=500(token), maxSize=800(token), minSize=100(token), overlapTokens=80(token)
```

新增 `@ConfigurationProperties(prefix = "rag.chunk")`，可在 `application.properties` 中覆盖：



```properties
rag.chunk.target-size=500
rag.chunk.max-size=800
rag.chunk.min-size=100
rag.chunk.overlap-tokens=80
```

**为什么引入 `targetSize`**：旧版只有一个 `maxSize`，chunk 尺寸不可控。新版三级预算模型：

- `targetSize` — 理想尺寸，切分时尽量逼近
- `maxSize` — 硬上限，超出强制降级到更细粒度的切分策略
- `minSize` — 软下限，低于此值尝试与同 section 邻居合并

------

### 3. 重写DocumentChunkService.java— 核心 Pipeline

这是改动最大的文件，从 ~220 行重写为 ~440 行。以下是每个设计点：

#### 3a. 四层语义切分优先级 (Chunking Cascade)



```
splitByHeadings → groupByTokenBudget → splitOversizedParagraph → forceCharSplit
    (第1层)           (第2层)              (第3层)                   (第4层)
```

每一层在超出 `maxSize` 时自动降级到下一层，避免产生超大 chunk：

| 层级 | 边界          | 触发条件       | 降级           |
| ---- | ------------- | -------------- | -------------- |
| 1    | Markdown 标题 | 始终           | → 生成 Section |
| 2    | 段落 (`\n\n`) | 段落 > maxSize | → 第3层        |
| 3    | 句子 (。！？) | 单句 > maxSize | → 第4层        |
| 4    | 标点/字符     | 兜底           | —              |

#### 3b. Title 参与 Embedding

旧版：title 只存在 metadata (`chunk.setTitle(title)`)，不进入 content，所以不参与向量检索。

新版 `buildChunk`：



```java
String content = (title != null) ? title + "\n" + body : body;
```

chunk content 变为 `"会员等级与权益体系\n## 三、核心权益对照表\n| 权益项 |..."`，title 被 embedding 编码进向量，检索时查询词命中 title 的概率大幅提升。

#### 3c. Section Isolation（禁止跨主题合并）

旧版 `mergeSmallChunks` 是对全局所有 chunk 做后置修补，不同 Section 的小 chunk 可能被合并到一起（如 "Redis配置" 和 "Kafka配置" 被合并成一个 chunk → 语义污染）。

新版 `mergeSmallChunksInSection` 只在 `chunkSection()` 内部调用，合并范围严格限定在当前 Section 的 chunk 列表内。

#### 3d. Sentence-level Overlap

旧版：



```java
String overlap = text.substring(text.length() - overlapSize);
// 可能截断："...会员等级分为 Lv.1 到 Lv" （句子被砍断）
```

新版：



```java
String overlap = ChunkTokenizer.extractLastSentences(prevBody, overlapTokens);
// 完整句子："...退款优先级为极速（1-2天）。"
```

#### 3e. 合并时机：流程内 vs 后置修补

旧版流程：



```
chunkSection → chunkSection → ... → mergeSmallChunks(all)  ← 全局后置
```

新版流程：



```
chunkSection → groupByTokenBudget → materializeChunks → mergeSmallChunksInSection
                                                           ↑ 只在 Section 内
```

每个 Section 独立完成"分组→装配→合并"全流程，合并是流程内的一个环节而非事后补救。

------

### 4. 之前修复 RAGQAService.java — 相似度标签



```
旧: "📄 文档 1 (相似度: 4734.45)"
新: "📄 文档 1 (L2距离: 4734.45, 越小越匹配)"
```

------

## 总结对比

| 维度     | 旧版                            | 新版                                            |
| -------- | ------------------------------- | ----------------------------------------------- |
| 尺寸度量 | 字符数                          | Token 估算                                      |
| 切分策略 | 2 层（标题+段落）               | 4 层级联（标题→段落→句子→字符）                 |
| Title    | 仅存 metadata，不参与 embedding | 拼入 content，进入向量检索                      |
| Overlap  | `substring` 截字符              | `extractLastSentences` 取完整句子               |
| 合并范围 | 全局后置修补                    | Section 内流程中合并                            |
| 配置粒度 | 3 档 (maxSize/overlap/minSize)  | 4 档 (targetSize/maxSize/minSize/overlapTokens) |

**需要重建索引才能生效** — 旧分片已经在 Milvus 中，需要重新上传文档触发新版分片逻辑。

# 5.14.1

## 问题根因

`collection not loaded` — Milvus 中 collection 在创建后或重启后需要显式调用 `loadCollection` 加载到内存才能搜索。之前只在 insert/delete 时有加载，搜索路径没有。

## 修改了两处

**1. VectorSearchService.java** — 搜索前加载 collection

新增 `ensureCollectionLoaded()` 方法，每次搜索前确保 collection 在内存中。幂等操作，已加载的不会重复加载。

**2.MilvusClientFactory.java** — 启动时加载 collection

在 `createClient()` 中，创建/确认 collection 后立即 `loadCollection`，确保服务一启动 collection 就在内存中。