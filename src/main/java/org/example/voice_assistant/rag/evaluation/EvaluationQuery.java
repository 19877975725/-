package org.example.voice_assistant.rag.evaluation;

import lombok.Data;
import org.example.voice_assistant.rag.service.QueryIntent;

import java.util.List;

/**
 * 标注查询 — RAG 评估数据集中的一条记录。
 *
 * <p>relevantSources 匹配的是 Milvus metadata 中的 {@code _source}（文件路径）
 * 或 {@code _file_name}（文件名），而非 Milvus 内部 UUID。
 * 例如：{@code "阿里云百炼手机产品介绍.md"} 或 {@code "./uploads/合同.pdf"}。
 */
@Data
public class EvaluationQuery {
    /** 查询唯一标识 */
    private String id;
    /** 用户输入的问题 */
    private String query;
    /**
     * 标注的相关文档标识列表。
     * 支持前缀匹配：可以是完整路径（_source）、文件名（_file_name），
     * 或者是 _source 路径中以给定字符串结尾的任意子串。
     */
    private List<String> relevantSources;
    /** 期望答案中的关键信息点 */
    private String expectedAnswerKeyPoints;
    /** 期望的意图分类（用于评估 IntentRouter 准确率） */
    private QueryIntent expectedIntent;
}
