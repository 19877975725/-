package org.example.voice_assistant.rag.evaluation;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 单条查询的评估明细。
 */
@Data
@Builder
public class QueryEvaluationDetail {
    private String queryId;
    private String query;
    /** 检索返回的文档来源标识（按排名）— 从 metadata 提取的 _file_name 或 _source */
    private List<String> retrievedDocIds;
    /** 检索返回的分数列表（按排名） */
    private List<Float> retrievedScores;
    /** 追溯到的相关文档在结果中的排名（1-based），找不到为 -1 */
    private int firstRelevantRank;
    /** 是否被 minScore 阈值过滤掉 */
    private boolean filteredByThreshold;
    /** IntentRouter 分类结果 */
    private String classifiedIntent;
    /** 期望的意图分类 */
    private String expectedIntent;
}
