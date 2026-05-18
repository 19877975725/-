package org.example.voice_assistant.rag.evaluation;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * RAG 评估结果汇总。
 */
@Data
@Builder
public class EvaluationResult {

    // ========== 检索质量指标 ==========

    /** Recall@1：第一个结果就命中相关文档的查询占比 */
    private double recallAt1;
    /** Recall@3：前3个结果中命中相关文档的查询占比 */
    private double recallAt3;
    /** Recall@5：前5个结果中命中相关文档的查询占比 */
    private double recallAt5;
    /** MRR (Mean Reciprocal Rank)：首个相关文档排名倒数的均值 */
    private double mrr;

    // ========== 意图路由指标 ==========

    /** 意图分类准确率 */
    private double intentAccuracy;

    // ========== 管线效率指标 ==========

    /** 评估的总查询数 */
    private int totalQueries;
    /** 未被 minScore 阈值过滤的查询数（检索有效） */
    private int successfulRetrievals;
    /** minScore 过滤率（被阈值拦截的比例） */
    private double filterRate;

    // ========== 明细 ==========

    /** 每条查询的评估明细 */
    private List<QueryEvaluationDetail> details;
}
