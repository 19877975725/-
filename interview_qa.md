# Voice 项目面试问答

## 1. 为什么选 WebSocket 而不是 HTTP？

答：这个项目是典型的实时双向语音链路，前端要持续收 token，PBX 要持续收 TTS 句子，服务端还要实时接 ASR 结果和控制信令。WebSocket 比 HTTP 更适合做一条长连接上的低延迟双向消息通道。HTTP/2 虽然有 streaming，但本质仍偏请求-响应，浏览器侧 Server Push 生态也不适合这类应用层信令编排；而且我们这里是“前端/ PBX 都会主动发消息”，WebSocket 心智模型更直接。

补充：项目里前端和 PBX 分别连 `/ws/frontend`、`/ws/pbx`，由 [WebSocketConfig](/Users/mikey/java_work/voice/src/main/java/org/example/voice_assistant/config/WebSocketConfig.java) 注册；连接建立后会进入 [SessionManager](/Users/mikey/java_work/voice/src/main/java/org/example/voice_assistant/websocket/SessionManager.java) 的两个表。断线重连代码目前服务端没有完整实现，更多依赖客户端重连；这是当前版本的短板，生产上应补心跳、重连退避和重配对。`TextWebSocketHandler` 本身是收到消息后在 I/O 线程回调，当前 handler 里又会继续做分发和部分业务编排，所以高并发下确实有阻塞风险，后续应把重逻辑异步化。

## 2. Command 信令处理是什么设计模式？

答：更贴近“策略模式 + 分发器”，不是传统 GoF 的命令模式。这里 `CommandHandler` 是一组可替换处理策略，[CommandDispatcher](/Users/mikey/java_work/voice/src/main/java/org/example/voice_assistant/handler/CommandDispatcher.java) 根据 `command` 选择具体 handler；而命令模式更强调把请求封装成对象，支持排队、撤销、日志等。`BaseCommandHandler` 主要是复用公共发送逻辑。

补充：`@PostConstruct` 自动扫描并注册所有 `CommandHandler`，优点是新增 handler 几乎零配置、扩展性好；缺点是命令名冲突只能运行时发现，启动时依赖 Spring 容器完整装配。新增一种信令，例如视频通话，通常只要扩 `WebSocketMessage.Command`、新增 handler、必要时补配对或 payload 字段，改动面不算大。

## 3. 两个 WebSocket Handler 共用一个 Dispatcher，消息怎么路由？

答：路由核心不在 Dispatcher，而在 [BaseCommandHandler](/Users/mikey/java_work/voice/src/main/java/org/example/voice_assistant/handler/BaseCommandHandler.java) 的 `sendToFrontend` / `sendToPbx`。如果当前 session 本身就是目标类型，就直接发；否则走 `sendToPairedSession`，再从 `SessionManager.sessionPairMap` 找对端。

补充：“配对”的语义就是一条 frontend 会话和一条 pbx 会话形成业务上的同一通话链路。当前代码支持显式 `pairSessions(frontendSessionId, pbxSessionId)`，另外 `getPairedSessionId()` 里还有 mock 逻辑，会给前端自动找第一条 PBX 会话，这说明现在更偏单 PBX/演示态。若双方没配对上，消息不会抛异常，而是记录 warn 后丢弃。

## 4. 描述一条用户语音从 ASR 到 TTS 的完整数据流

答：ASR 完成后发 `ASR_FINAL`，由 [AsrFinalCommandHandler](/Users/mikey/java_work/voice/src/main/java/org/example/voice_assistant/handler/AsrFinalCommandHandler.java) 接住。先看 RAG 总开关，再做意图路由；若走知识问答，则进入 [RAGQAService](/Users/mikey/java_work/voice/src/main/java/org/example/voice_assistant/rag/service/RAGQAService.java)：查询预处理、多查询向量检索、阈值过滤、Prompt 构建、再交给 `Agent.runStream()` 调 LLM。流式 token 一边通过 `ANSWER_STREAM` 发前端，一边喂给 `SentenceBoundaryDetector`；一旦形成完整句子，就通过 `TTS_SENTENCE` 发 PBX，最后补 `TTS_STREAM_DONE`。

补充：ASR 文本常见问题是口语化、重复词、模糊指代、上下文省略，所以不能直接裸查向量库，否则召回很容易漂。

## 5. QueryPreprocessor 的设计思路是什么？

答：它的目标不是“美化问题”，而是把口语输入改写成更适合 embedding 检索的检索式查询。项目里 [QueryPreprocessor](/Users/mikey/java_work/voice/src/main/java/org/example/voice_assistant/rag/service/QueryPreprocessor.java) 会让 LLM 输出 1 到 3 个查询变体，因为一个标准化表达未必覆盖所有同义说法，多路召回通常比单路更稳。

补充：JSON 解析做了三层容错。先处理 markdown 代码块，解决模型把 JSON 包在 ```json 里的情况；再抓首尾花括号，解决前后夹杂解释文本；最后按行分割，兜底非 JSON 输出。预处理失败直接降级为原始查询，而且原始查询会被插回 `queries` 列表首位，这样可以避免“优化过头”把用户原意改没了。多查询结果在 [VectorSearchService](/Users/mikey/java_work/voice/src/main/java/org/example/voice_assistant/rag/service/VectorSearchService.java) 里按文档 id 去重，并保留 L2 最小值，因为在 L2 度量下分数越小代表该文档至少被某个 query 命中过一次最好位置。

## 6. IntentRouter 的两层判断怎么设计？

答：先规则、后 LLM。规则层对“你好”“谢谢”“帮我查”“打开空调”这类高频强信号几乎零成本，能拦下大部分显性场景；命不中才调用 LLM 做极短 prompt 分类。这个设计本质上是用便宜路径覆盖高置信样本，用模型处理长尾。

补充：正则匹配的局限是语义泛化差，像“今天天气”在这里被归为闲聊，但如果系统后续真接天气工具就可能误分；“帮我打开合同文档并解释一下”这种混合意图也容易误判。LLM 分类 prompt 做得很短，是为了把额外延迟和 token 成本压到最低；若 LLM 返回异常内容或调用失败，代码会保守降级到 `CHAT`。当前 `COMMAND` 仍走普通对话，这是明显的预留扩展位，不是终态。再往后可升级成小分类模型、embedding 相似度分类，或规则+小模型+工具路由三层方案。

## 7. DocumentChunkService 的四层切分策略为什么这样设计？

答：顺序是 Markdown 标题、段落、句子、字符兜底，因为它对应“语义完整性”从强到弱。标题先切 section，保证主题不串；段落切分保留局部论述结构；超长段落再按句子拆，尽量不打断语义；最后才字符级保底，避免极端长句卡死。

补充：这里全部按 token 而不是字符判断，是因为模型上下文和 embedding 成本都按 token 计，字符数对中英文混排不稳定。`ChunkTokenizer` 本质是在做近似 token 估算。Section Isolation 是为了防止把不同章节拼进同一 chunk，造成召回命中了但上下文污染。旧版只有 `maxSize + overlap`，会出现“只有标题或很小碎片”的 chunk，所以新版加了 `minSize` 和 section 内合并。overlap 取完整句子而不是固定 token，是为了避免把半句截到下一块，导致 embedding 和后续生成都更别扭。若 recall@1 低，我会优先查分片质量、query 改写质量、embedding 模型、Milvus 检索参数和阈值。

## 8. Milvus 为什么用 L2，不用余弦相似度？

答：当前实现是索引和查询都统一用了 L2，[MilvusClientFactory](/Users/mikey/java_work/voice/src/main/java/org/example/voice_assistant/client/MilvusClientFactory.java) 和 [VectorSearchService](/Users/mikey/java_work/voice/src/main/java/org/example/voice_assistant/rag/service/VectorSearchService.java) 都是 `MetricType.L2`。如果向量做过归一化，L2 和余弦在排序上会比较接近；如果没归一化，L2 会受向量模长影响更大。

补充：这个项目最大的风险不是“能不能用 L2”，而是 `minScore` 的配置和校验逻辑明显不一致。配置里 `rag.min-score=20000.0`，而 [RAGConfig](/Users/mikey/java_work/voice/src/main/java/org/example/voice_assistant/config/RAGConfig.java) 的 `setMinScore` 却只接受 0 到 1；结果就是外部配置 20000 根本绑不进去，实际仍沿用字段默认值 20000。它暂时没炸，是因为默认值和配置值刚好一样，但这个 setter 语义是错的。`ensureCollectionLoaded()` 每次检索都调一次，能兜住“未加载”错误，但确实有冗余，生产上更适合初始化预热或状态缓存。

## 9. Embedding 是怎么生成的？用了什么模型？

答：项目用的是阿里云 DashScope 的 `text-embedding-v1`，实现见 [EmbeddingService](/Users/mikey/java_work/voice/src/main/java/org/example/voice_assistant/rag/service/EmbeddingService.java)。代码里通过 DashScope SDK 拉回 `List<Double>`，再转成 `List<Float>` 存给 Milvus。

补充：维度取决于模型本身，维度越高通常表达能力越强，但存储、索引和检索成本也更高。Double 转 Float 会有精度损失，但对大多数向量检索场景影响很有限，远小于文本改写和分片质量的影响。当前失败策略基本是抛异常，不带重试，这对生产不够稳；批量导入时应优先用批量 embedding，在线单 query 检索则更适合单条或小批量，以平衡延迟。

## 10. LLM 调用为什么从 HttpURLConnection/RestTemplate 演进到 WebClient？

答：因为项目既要同步调用，也要稳定做流式 SSE，WebClient 更统一。`RestTemplate` 偏阻塞式请求-响应，不适合长时间持续消费 token 流；旧版 `HttpURLConnection + RestTemplate` 其实是两套栈拼在一起。现在 [QwenClient](/Users/mikey/java_work/voice/src/main/java/org/example/voice_assistant/llm/QwenClient.java) 全部统一到 WebClient，维护成本更低。

补充：WebClient 底层是 Reactor Netty，走非阻塞 I/O；`HttpURLConnection` 是典型阻塞 I/O，线程会一直占着等网络数据。`bodyToFlux(String.class).blockLast()` 的意思是上游按流式逐 chunk 处理，但当前业务线程会阻塞到流结束，保证调用方语义还是“这次处理没结束别往下走”；去掉 `blockLast()`，如果外层不接管订阅和生命周期，流可能还没跑完方法就返回了。跨 chunk 的不完整 JSON 则由 `lineBuffer` 拼接处理。

## 11. 流式 token 怎么一步步传到前端？

答：链路是 `QwenClient.onToken` -> `Agent.runStream` / `RAGQAService.ragAnswerStream` -> `AsrFinalCommandHandler.sendStreamToken` -> WebSocket `ANSWER_STREAM`。当前实现基本是“来一个 token 发一个 WebSocket 消息”，不是等成完整句子才发前端。

补充：前端展示和 PBX 播放不同步到“字级”，而是前端按 token、PBX 按句子。这样前端体验更即时，PBX 语音又能拿到较完整语义单元。`SentenceBoundaryDetector` 目前只看标点 `[。！？,.!?]`，如果模型长时间不出标点，就会一直堆在 buffer 里，直到结束时 `flush()` 一次性补发。

## 12. Agent 的 Function Calling 是怎么实现的？

答：实现是标准 OpenAI 兼容流程。先由 [Agent](/Users/mikey/java_work/voice/src/main/java/org/example/voice_assistant/agent/Agent.java) 从 `ToolRegistry` 构建 `ToolDef`，调用 `chatWithTools` 让模型决定是否产生 `tool_calls`；若产生了，就解析参数、执行工具，把结果以 `role:"tool"` 追加到 messages，再调用 `chatWithMessages` 或 `chatWithMessagesStream` 生成最终回答。

补充：每次请求都要带上 tools，因为模型不保留你后端本地工具注册表的状态，新的请求必须重新给它能力边界。当前多个 `tool_calls` 是按返回顺序串行执行，不做依赖分析也不并行。流式 `runStream()` 里之所以先同步判断 tool call，再进入最终流式，是因为工具调用阶段本身更像一次“规划与执行”，而不是用户可见文本输出；这样实现简单，但代价是多一次 round-trip 和一次同步等待。

## 13. 为什么选 Qwen，而不是 GPT-4 或其他模型？

答：从代码和配置看，这个项目明显优先考虑的是国内可用性、成本和与 DashScope 生态的一体化，尤其 embedding 和 chat 都在一套平台上，工程接入更顺。`enable_thinking(false)` 的意思是关闭模型的思维链式扩展输出，减少额外 token 和延迟，让返回更贴近直接答复。

补充：`temperature=0.7` 用在普通对话还算折中，但如果拿同一个温度去做意图分类和知识问答，并不是最优。分类更适合更低温甚至接近 0，知识问答也常偏低温，稳定性会更好。

## 14. TTS 逐句发送方案是怎么设计的？

答：第一代是等 LLM 全部输出完，再把整段文本一次性发给 PBX 做 TTS；第二代是 token 流里实时做句边界检测，一旦成句就发 `TTS_SENTENCE`。核心收益就是首句播报明显提前，用户体感延迟更低。

补充：当前句边界正则把英文逗号也算结束符，这在中英文混排里能提早出声，但也可能把一个长英文从句切得过碎。`flush()` 负责处理模型最后没用句号结尾的残留文本。若模型持续输出超长无标点段落，当前实现确实会迟迟不发 TTS，因为没有基于长度或超时的强制切句机制，这也是可以继续优化的点。

## 15. RAG 评估体系为什么需要离线评估？

答：因为线上用户反馈太慢，也不利于定位问题。离线评估可以把“是检索差、路由错、还是阈值太严”拆开看。[RAGEvaluationService](/Users/mikey/java_work/voice/src/main/java/org/example/voice_assistant/rag/evaluation/RAGEvaluationService.java) 会读取带标注的数据集，评估 recall@1/3/5、MRR、intent accuracy 和 filter rate。

补充：每条 query 至少标 `id`、`query`、`relevantSources`、`expectedAnswerKeyPoints`、`expectedIntent`。Recall@K 看“搜没搜到”，MRR 看“排得靠不靠前”，两个指标缺一不可。当前通过 metadata 里的 `_file_name` 做包含匹配，简单但会有模糊性，比如文件名重名或路径截断。若 recall@1 低，优先回查 chunk、embedding、查询改写、topK/nprobe、阈值和索引参数。意图分错会直接让知识问题绕开 RAG，或者让闲聊白白去查库，最终影响质量和成本。`RAGMetricsService` 现在是内存计数器，生产上最好再桥接到 Prometheus/Grafana。

## 16. 异常处理和降级策略怎么看？

答：这是项目里比较成熟的一点，核心思想是“让链路尽量回答，而不是轻易失败”。RAG 失败降级普通对话，普通对话再失败才回固定兜底话术；查询预处理失败退原始 query；意图分类失败退 CHAT。这些都是在不同层级把错误局部化。

补充：但也要注意不能把所有异常都吃掉。像配置错误、连接池初始化失败、Milvus/Embedding 长期不可用这类系统性故障，不应该只在业务层静默降级，否则会掩盖生产问题。当前代码里 `try-catch` 确实偏多，更适合再细分可恢复异常和不可恢复异常。

## 17. 如果上生产，还需要补什么？

答：我会重点补五块：连接池和线程池参数、Milvus 高可用、WebSocket 负载均衡、观测体系、压测与安全。现在 WebClient 连接池已经有基础配置，但业务线程和 WebSocket 发送链路还需要更清晰的线程模型。

补充：Milvus 需要考虑集群化、备份和冷热数据；WebSocket 若做多实例部署，必须考虑 session 粘性或外部 session 路由，否则 frontend/pbx 配对会失效。监控上要补接口耗时、token 吞吐、降级率、过滤率、PBX 播放时延。日志里打印 API Key 前缀在开发期还行，生产上仍建议去掉或最少化。

## 18. `@ConfigurationProperties` 和 `@Value` 有什么区别？

答：`@ConfigurationProperties` 适合一组同类配置的结构化绑定，类型安全更好，也更适合集中校验；`@Value` 适合零散单项配置。这个项目里 [RAGConfig](/Users/mikey/java_work/voice/src/main/java/org/example/voice_assistant/config/RAGConfig.java) 用前者是合理的，[QwenClient](/Users/mikey/java_work/voice/src/main/java/org/example/voice_assistant/llm/QwenClient.java) 只取少数几个字段，用 `@Value` 也能接受。

补充：但 `RAGConfig.setMinScore()` 的校验条件和实际 L2 语义冲突，这是一个真实 bug。L2 阈值不是 0 到 1 的相似度分数，理论上应允许大于 1；如果后面有人把默认值改小，或真的想从配置里调 L2 阈值，这个 setter 会导致配置失效且不易发现。

## 19. 这个项目从 v1 演进到现在，最值得讲的一个技术决策是什么？

答：我会选“把整条回答链路做成真正流式，并把 TTS 从整段改成逐句”。因为它不是简单换个 API，而是把 LLM、SSE、WebSocket、句边界检测、PBX 播放这几层串成了一个低延迟链路，用户体感提升非常直接。

## 20. 如果重新设计，有什么会做得不一样？

答：我会更早把“连接路由”和“AI 编排”解耦。现在 session 配对、消息发送、RAG/Agent 编排耦合在 handler 里，迭代快，但扩到多实例、多 PBX、多租户时会比较吃力。另一个会提前做的是统一异步模型，把 WebSocket handler、LLM 流和工具调用的线程边界设计清楚。

## 21. 如果用户量从 100 增长到 10 万，瓶颈会在哪？

答：首先不是 Java Controller，而是长连接管理、LLM/Qwen 调用额度、Embedding 与 Milvus 检索、以及会话配对状态。当前 `SessionManager` 是单机内存 map，10 万级别多实例部署下肯定不够，需要外部状态层或路由层。其次 token 级 WebSocket 推送和同步阻塞的业务链路会把线程和网络放大。

补充：解决方向通常是 WebSocket 网关化、session 粘性或分布式会话管理、LLM/Embedding 限流与缓存、Milvus 分片扩容、以及把重逻辑从 I/O 线程摘出去。RAG 侧还可以通过意图路由、query cache、热门文档 cache 先把不必要的检索压下去。

## 建议重点准备的 3 个方向

1. RAG 全链路：分片、改写、embedding、Milvus、阈值、Prompt、生成，每一层都要能讲 trade-off。
2. 流式工程细节：SSE 跨 chunk 解析、WebSocket 双通道、token 到句子的转换、为什么选 WebClient。
3. 容错与降级：RAG 到普通对话、JSON 三层容错、规则加 LLM 路由，这些最能体现工程成熟度。
