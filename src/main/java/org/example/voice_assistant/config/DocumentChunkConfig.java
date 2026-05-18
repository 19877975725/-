package org.example.voice_assistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 文档分片配置 — 基于 Token 的语义分片控制。
 *
 * 命名对齐 LangChain / LlamaIndex 惯例：
 *   chunkSize  = 期望的分片大小（token）
 *   chunkOverlap = 相邻分片之间的重叠（token）
 *
 * 大小控制规则：
 *   - 低于 minSize   → 允许与同 section 内的相邻分片合并
 *   - 接近 targetSize → 尽量在语义边界切分
 *   - 超过 maxSize    → 强制拆分（降级到下一种边界策略）
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.chunk")
public class DocumentChunkConfig {

    /** 分片目标大小（token），切分时尽量趋近此值 */
    private int targetSize = 500;

    /** 分片硬上限（token），超出后强制拆分 */
    private int maxSize = 800;

    /** 分片软下限（token），低于此值尝试合并 */
    private int minSize = 100;

    /** 相邻分片重叠 token 数（以完整句子为单位） */
    private int overlapTokens = 80;
}
