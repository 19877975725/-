package org.example.voice_assistant.rag.service;

import org.example.voice_assistant.config.DocumentChunkConfig;
import org.example.voice_assistant.rag.entity.DocumentChunk;
import org.example.voice_assistant.rag.entity.Section;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义感知 + Token 控制的文档分片服务。
 *
 * 切分优先级（从高到低）：
 *   1. Markdown 标题（#, ##, ### ...）
 *   2. 段落边界（\\n\\n+）
 *   3. 句子边界（。！？!?）
 *   4. 兜底字符级切分
 *
 * 关键约束：
 *   - 所有尺寸判断基于 token，不是字符数
 *   - title 参与 chunk content，进入 embedding
 *   - 合并只发生在同一 section 内（section isolation）
 *   - overlap 以完整句子为单位
 */
@Service
public class DocumentChunkService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentChunkService.class);

    @Autowired
    private DocumentChunkConfig config;

    // ================================================================
    //  公开入口
    // ================================================================

    /**
     * 对文档进行语义分片。
     *
     * @param content  文档全文
     * @param filePath 文件路径（仅用于日志）
     * @return 有序分片列表
     */
    public List<DocumentChunk> chunkDocument(String content, String filePath) {
        if (content == null || content.trim().isEmpty()) {
            logger.warn("文档内容为空: {}", filePath);
            return Collections.emptyList();
        }

        // 1. 按 Markdown 标题拆分为 Section
        List<Section> sections = splitByHeadings(content);
        if (sections.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 对每个 Section 独立分片（section isolation）
        List<DocumentChunk> allChunks = new ArrayList<>();
        int globalIndex = 0;

        for (Section section : sections) {
            List<DocumentChunk> sectionChunks = chunkSection(section, globalIndex);
            allChunks.addAll(sectionChunks);
            globalIndex += sectionChunks.size();
        }

        logger.info("文档分片完成: {} → {} sections, {} chunks",
                filePath, sections.size(), allChunks.size());
        return allChunks;
    }

    // ================================================================
    //  第 1 层：Markdown 标题切分 → Section 列表
    // ==============================================================   ==

    /**
     * 按 Markdown 标题将文档切为 Section。
     * 每个 Section 包含标题文本和正文（不含标题标记），
     * 连续标题之间无正文的会被自动跳过。
     */
    private List<Section> splitByHeadings(String content) {
        List<Section> sections = new ArrayList<>();

        Pattern headingPattern = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
        Matcher matcher = headingPattern.matcher(content);

        int bodyStart = 0;          // 当前正文起始位置
        String currentTitle = null; // 最近一次出现的标题

        while (matcher.find()) {
            // matcher.start() 到 matcher.end() 是标题行
            // bodyStart 到 matcher.start() 是（上一标题的）正文
            if (bodyStart < matcher.start()) {
                String body = content.substring(bodyStart, matcher.start()).trim();
                if (!body.isEmpty()) {
                    sections.add(new Section(currentTitle, body, bodyStart));
                }
            }

            currentTitle = matcher.group(2).trim();
            bodyStart = matcher.end(); // 正文从标题行之后开始
        }

        // 最后一个标题之后的正文
        if (bodyStart < content.length()) {
            String body = content.substring(bodyStart).trim();
            if (!body.isEmpty()) {
                sections.add(new Section(currentTitle, body, bodyStart));
            }
        }

        // 全篇无标题时，整篇作为一个 Section
        if (sections.isEmpty()) {
            sections.add(new Section(null, content, 0));
        }

        return sections;
    }

    // ================================================================
    //  第 2 层：Section 内部分片
    // ================================================================

    /**
     * 对单个 Section 进行语义分片。
     * 所有 chunk 均在同一 section 内，不跨 section 合并。
     */
    private List<DocumentChunk> chunkSection(Section section, int startChunkIndex) {
        String body = section.getContent();
        String title = section.getTitle();
        int bodyTokens = ChunkTokenizer.countTokens(body);

        // 整个 section 不超过 targetSize → 单 chunk
        if (bodyTokens <= config.getTargetSize()) {
            DocumentChunk chunk = buildChunk(title, body, section.getStartIndex(), startChunkIndex);
            return Collections.singletonList(chunk);
        }

        // 按段落分组（第 2 层边界）
        List<String> cleanParagraphs = cleanParagraphs(body);

        // 将段落组装为 targetSize 级别的 chunk body
        List<String> chunkBodies = groupByTokenBudget(cleanParagraphs);

        // 应用 sentence-level overlap + title，构建 DocumentChunk
        List<DocumentChunk> chunks = materializeChunks(chunkBodies, title,
                section.getStartIndex(), startChunkIndex);

        // 同 section 内合并过小的 chunk
        chunks = mergeSmallChunksInSection(chunks);

        return chunks;
    }

    // ================================================================
    //  第 2a 层：段落 → token-budget 分组
    // ================================================================

    /**
     * 将段落列表按 token budget 分组，尽量逼近 targetSize。
     * 单个段落超过 maxSize 时降级到第 3 层（句子级切分）。
     */
    private List<String> groupByTokenBudget(List<String> paragraphs) {
        List<String> groups = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int bufTokens = 0;
        int target = config.getTargetSize();
        int max = config.getMaxSize();

        for (String para : paragraphs) {
            int paraTokens = ChunkTokenizer.countTokens(para);

            // 单个段落超过 maxSize → 句子级拆分
            if (paraTokens > max) {
                if (buf.length() > 0) {
                    groups.add(buf.toString().trim());
                    buf.setLength(0);
                    bufTokens = 0;
                }
                groups.addAll(splitOversizedParagraph(para));
                continue;
            }

            // 加上当前段落后会明显超过 targetSize → 先提交当前组
            if (bufTokens > 0 && bufTokens + paraTokens > target) {
                groups.add(buf.toString().trim());
                buf.setLength(0);
                bufTokens = 0;
            }

            if (buf.length() > 0) buf.append("\n\n");
            buf.append(para);
            bufTokens += paraTokens;
        }

        if (buf.length() > 0) {
            groups.add(buf.toString().trim());
        }

        return groups;
    }

    // ================================================================
    //  第 3 层：句子级切分（超大段落降级）
    // ================================================================

    /**
     * 将超过 maxSize 的单个段落按句子切分。
     * 单句仍超过 maxSize 时降级到第 4 层（字符级兜底）。
     */
    private List<String> splitOversizedParagraph(String paragraph) {
        String[] rawSentences = ChunkTokenizer.splitSentences(paragraph);
        List<String> pieces = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int bufTokens = 0;
        int target = config.getTargetSize();
        int max = config.getMaxSize();

        for (String raw : rawSentences) {
            String sentence = raw.trim();
            if (sentence.isEmpty()) continue;

            int sTokens = ChunkTokenizer.countTokens(sentence);

            // 单句超过 maxSize → 字符级兜底
            if (sTokens > max) {
                if (buf.length() > 0) {
                    pieces.add(buf.toString().trim());
                    buf.setLength(0);
                    bufTokens = 0;
                }
                pieces.addAll(forceCharSplit(sentence));
                continue;
            }

            if (bufTokens > 0 && bufTokens + sTokens > target) {
                pieces.add(buf.toString().trim());
                buf.setLength(0);
                bufTokens = 0;
            }

            buf.append(sentence);
            bufTokens += sTokens;
        }

        if (buf.length() > 0) {
            pieces.add(buf.toString().trim());
        }

        return pieces;
    }

    // ================================================================
    //  第 4 层：字符级兜底切分
    // ================================================================

    /**
     * 当单个句子超过 maxSize 时的兜底策略。
     * 按 token 近似切分，尽量在标点处断开。
     */
    private List<String> forceCharSplit(String text) {
        List<String> pieces = new ArrayList<>();
        int max = config.getMaxSize();
        int pos = 0;
        int len = text.length();

        while (pos < len) {
            int end = Math.min(pos + max, len);
            if (end < len) {
                // 回退到最近的标点
                for (int i = end; i > pos + max / 2; i--) {
                    char c = text.charAt(i);
                    if (c == '，' || c == ',' || c == ' ' || c == '；' || c == ';' || c == '、') {
                        end = i + 1;
                        break;
                    }
                }
            }
            pieces.add(text.substring(pos, end));
            pos = end;
        }

        return pieces;
    }

    // ================================================================
    //  Chunk 组装：title + overlap
    // ================================================================

    /**
     * 将 chunk body 列表组装为 DocumentChunk：
     *   - 每个 chunk 的 content = title + "\\n" + body
     *   - 相邻 chunk 之间有 sentence-level overlap
     */
    private List<DocumentChunk> materializeChunks(List<String> bodies, String title,
                                                   int sectionStart, int startChunkIndex) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int overlapTokens = config.getOverlapTokens();

        for (int i = 0; i < bodies.size(); i++) {
            String body = bodies.get(i);

            // 从上一个 chunk body 末尾提取完整句子作为 overlap
            if (i > 0 && overlapTokens > 0) {
                String prevBody = bodies.get(i - 1);
                String overlap = ChunkTokenizer.extractLastSentences(prevBody, overlapTokens);
                if (!overlap.isEmpty()) {
                    body = overlap + body;
                }
            }

            DocumentChunk chunk = buildChunk(title, body, sectionStart, startChunkIndex + i);
            chunks.add(chunk);
        }

        return chunks;
    }

    // ================================================================
    //  Section 内小 chunk 合并
    // ================================================================

    /**
     * 在同一 section 内合并 token 数低于 minSize 的小 chunk。
     * 合并时不允许超出 maxSize；实在合不进去则保留原样。
     *
     * 与旧版 mergeSmallChunks 的区别：
     *   - 只在当前 section 的 chunk 之间合并，不会跨 section 污染
     *   - 合并发生在 chunk 生成流程内，不是全局后置修补
     */
    private List<DocumentChunk> mergeSmallChunksInSection(List<DocumentChunk> chunks) {
        if (chunks.size() <= 1) return chunks;

        int minTokens = config.getMinSize();
        int maxTokens = config.getMaxSize();
        List<DocumentChunk> merged = new ArrayList<>();

        DocumentChunk pending = null; // 等待合并的小 chunk

        for (DocumentChunk chunk : chunks) {
            int chunkTokens = ChunkTokenizer.countTokens(chunk.getContent());

            if (chunkTokens < minTokens) {
                if (pending != null) {
                    pending = mergeTwo(pending, chunk);
                } else {
                    pending = chunk;
                }
            } else {
                if (pending != null) {
                    int pendingTokens = ChunkTokenizer.countTokens(pending.getContent());
                    if (chunkTokens + pendingTokens <= maxTokens) {
                        chunk = mergeTwo(pending, chunk);
                    } else {
                        merged.add(pending); // 合不进去，独立保留
                    }
                    pending = null;
                }
                merged.add(chunk);
            }
        }

        // 尾部 pending
        if (pending != null) {
            if (!merged.isEmpty()) {
                DocumentChunk last = merged.get(merged.size() - 1);
                int lastTokens = ChunkTokenizer.countTokens(last.getContent());
                int pendingTokens = ChunkTokenizer.countTokens(pending.getContent());
                if (lastTokens + pendingTokens <= maxTokens) {
                    merged.set(merged.size() - 1, mergeTwo(last, pending));
                } else {
                    merged.add(pending);
                }
            } else {
                merged.add(pending);
            }
        }

        // 重新编号
        for (int i = 0; i < merged.size(); i++) {
            merged.get(i).setChunkIndex(i);
        }

        return merged;
    }

    private DocumentChunk mergeTwo(DocumentChunk a, DocumentChunk b) {
        DocumentChunk mergedChunk = new DocumentChunk(
                a.getContent() + "\n\n" + b.getContent(),
                a.getStartIndex(),
                b.getEndIndex(),
                a.getChunkIndex()
        );
        // 同一 section 内 title 相同，保留 a 的即可
        mergedChunk.setTitle(a.getTitle());
        return mergedChunk;
    }

    // ================================================================
    //  工具方法
    // ================================================================

    /**
     * 构建单个 DocumentChunk。
     * content = title + "\\n" + body，确保 title 参与 embedding。
     */
    private DocumentChunk buildChunk(String title, String body, int startIndex, int chunkIndex) {
        String content = (title != null && !title.isEmpty())
                ? title + "\n" + body
                : body;

        DocumentChunk chunk = new DocumentChunk(
                content,
                startIndex,
                startIndex + content.length(),
                chunkIndex
        );
        chunk.setTitle(title);
        return chunk;
    }

    /**
     * 清洗段落：按双换行切分并去除空白项。
     */
    private List<String> cleanParagraphs(String body) {
        String[] raw = ChunkTokenizer.splitParagraphs(body);
        List<String> clean = new ArrayList<>();
        for (String p : raw) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) clean.add(trimmed);
        }
        return clean;
    }
}
