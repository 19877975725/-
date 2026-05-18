package org.example.voice_assistant.rag.evaluation;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.example.voice_assistant.config.RAGConfig;
import org.example.voice_assistant.rag.service.IntentRouter;
import org.example.voice_assistant.rag.service.QueryIntent;
import org.example.voice_assistant.rag.service.VectorSearchService;
import org.example.voice_assistant.rag.service.VectorSearchService.SearchResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG 评估服务 — 离线评估检索质量和意图路由准确率。
 *
 * <h3>评估指标</h3>
 * <ul>
 *   <li><b>Recall@K</b>：Top-K 检索结果中命中标注文档的比例，衡量"搜得全不全"</li>
 *   <li><b>MRR</b> (Mean Reciprocal Rank)：首个相关文档排名倒数的均值，衡量"排得准不准"</li>
 *   <li><b>Intent Accuracy</b>：IntentRouter 分类与人工标注的一致性</li>
 *   <li><b>Filter Rate</b>：被 minScore 阈值拦截的查询占比</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>
 *   POST /api/evaluation/run
 *   POST /api/evaluation/run?datasetPath=/path/to/dataset.json
 * </pre>
 */
@Slf4j
@Service
public class RAGEvaluationService {

    private static final String DEFAULT_DATASET = "rag-evaluation/sample-dataset.json";

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private IntentRouter intentRouter;

    @Autowired
    private RAGConfig ragConfig;

    /**
     * 运行完整评估：检索质量 + 意图路由 + 管线效率。
     *
     * @return 评估结果汇总
     */
    public EvaluationResult evaluate() {
        return evaluate(DEFAULT_DATASET);
    }

    /**
     * 运行完整评估，指定数据集路径。
     *
     * @param datasetPath classpath 相对路径或文件系统绝对路径
     */
    public EvaluationResult evaluate(String datasetPath) {
        List<EvaluationQuery> dataset = loadDataset(datasetPath);
        if (dataset.isEmpty()) {
            log.warn("评估数据集为空，返回空结果");
            return EvaluationResult.builder()
                    .totalQueries(0)
                    .details(List.of())
                    .build();
        }

        log.info("开始 RAG 评估，共 {} 条查询，minScore 阈值={}", dataset.size(), ragConfig.getMinScore());

        List<QueryEvaluationDetail> details = new ArrayList<>();
        int intentCorrect = 0;
        int intentTotal = 0;
        int filteredCount = 0;
        double mrrSum = 0.0;
        int mrrCount = 0;
        int recall1Hits = 0;
        int recall3Hits = 0;
        int recall5Hits = 0;
        int recallTotal = 0;

        for (EvaluationQuery eq : dataset) {
            QueryEvaluationDetail detail = evaluateSingleQuery(eq);
            details.add(detail);

            // 统计检索指标
            if (eq.getRelevantSources() != null && !eq.getRelevantSources().isEmpty()) {
                recallTotal++;
                if (detail.getFirstRelevantRank() >= 1 && detail.getFirstRelevantRank() <= 1) recall1Hits++;
                if (detail.getFirstRelevantRank() >= 1 && detail.getFirstRelevantRank() <= 3) recall3Hits++;
                if (detail.getFirstRelevantRank() >= 1 && detail.getFirstRelevantRank() <= 5) recall5Hits++;
                if (detail.getFirstRelevantRank() >= 1) {
                    mrrSum += 1.0 / detail.getFirstRelevantRank();
                    mrrCount++;
                }
            }

            // 统计过滤率
            if (detail.isFilteredByThreshold()) filteredCount++;

            // 统计意图准确率
            if (eq.getExpectedIntent() != null) {
                intentTotal++;
                if (eq.getExpectedIntent().name().equals(detail.getClassifiedIntent())) {
                    intentCorrect++;
                }
            }
        }

        EvaluationResult result = EvaluationResult.builder()
                .totalQueries(dataset.size())
                .recallAt1(recallTotal > 0 ? (double) recall1Hits / recallTotal : 0)
                .recallAt3(recallTotal > 0 ? (double) recall3Hits / recallTotal : 0)
                .recallAt5(recallTotal > 0 ? (double) recall5Hits / recallTotal : 0)
                .mrr(mrrCount > 0 ? mrrSum / mrrCount : 0)
                .intentAccuracy(intentTotal > 0 ? (double) intentCorrect / intentTotal : 0)
                .successfulRetrievals(dataset.size() - filteredCount)
                .filterRate(dataset.size() > 0 ? (double) filteredCount / dataset.size() : 0)
                .details(details)
                .build();

        log.info("RAG 评估完成: Recall@1={:.2%}, Recall@3={:.2%}, Recall@5={:.2%}, MRR={:.4f}, "
                        + "IntentAcc={:.2%}, FilterRate={:.2%}",
                result.getRecallAt1(), result.getRecallAt3(), result.getRecallAt5(),
                result.getMrr(), result.getIntentAccuracy(), result.getFilterRate());

        return result;
    }

    /**
     * 从 SearchResult.metadata 中提取文档来源标识。
     * 优先返回 {@code _file_name}，其次返回 {@code _source}。
     */
    private static String extractSource(SearchResult sr) {
        String metadataStr = sr.getMetadata();
        if (metadataStr == null || metadataStr.isBlank()) {
            return null;
        }
        try {
            JSONObject meta = JSON.parseObject(metadataStr);
            if (meta == null) return null;
            String fileName = meta.getString("_file_name");
            if (fileName != null && !fileName.isBlank()) return fileName;
            return meta.getString("_source");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断检索到的文档 source 是否匹配标注列表中的任一项。
     * 匹配规则：标注值是否包含在 source 中（支持仅写文件名匹配完整路径）。
     */
    private static boolean matchesAnySource(String retrievedSource, List<String> relevantSources) {
        if (retrievedSource == null || relevantSources == null) return false;
        for (String expected : relevantSources) {
            if (retrievedSource.contains(expected)) return true;
        }
        return false;
    }

    /**
     * 对单条查询执行检索 + 意图分类，计算指标明细。
     */
    private QueryEvaluationDetail evaluateSingleQuery(EvaluationQuery eq) {
        // 1. 意图路由评估
        String classifiedIntent;
        try {
            QueryIntent intent = intentRouter.classify(eq.getQuery());
            classifiedIntent = intent.name();
        } catch (Exception e) {
            log.warn("意图分类失败，query={}: {}", eq.getQuery(), e.getMessage());
            classifiedIntent = "ERROR";
        }

        // 2. 向量检索
        List<SearchResult> searchResults;
        try {
            searchResults = vectorSearchService.searchSimilarDocuments(
                    eq.getQuery(), ragConfig.getTopK());
        } catch (Exception e) {
            log.warn("向量检索失败，query={}: {}", eq.getQuery(), e.getMessage());
            searchResults = List.of();
        }

        // 3. 提取检索文档的来源标识和分数
        List<String> retrievedSources = new ArrayList<>();
        List<Float> retrievedScores = new ArrayList<>();
        for (SearchResult sr : searchResults) {
            String source = extractSource(sr);
            retrievedSources.add(source != null ? source : sr.getId());
            retrievedScores.add(sr.getScore());
        }

        // 4. 判断是否被阈值过滤（L2距离：越小越相似，最佳距离 <= 阈值才算通过）
        double threshold = ragConfig.getMinScore();
        float bestScore = Float.MAX_VALUE;
        for (SearchResult sr : searchResults) {
            if (sr.getScore() < bestScore) bestScore = sr.getScore();
        }
        boolean filtered = searchResults.isEmpty() || bestScore > threshold;

        // 5. 按 metadata 中的来源标识计算首个相关文档排名
        int firstRelevantRank = -1;
        List<String> relevantSources = eq.getRelevantSources();
        if (relevantSources != null && !relevantSources.isEmpty()) {
            for (int i = 0; i < retrievedSources.size(); i++) {
                if (matchesAnySource(retrievedSources.get(i), relevantSources)) {
                    firstRelevantRank = i + 1; // 1-based
                    break;
                }
            }
        }

        return QueryEvaluationDetail.builder()
                .queryId(eq.getId())
                .query(eq.getQuery())
                .retrievedDocIds(retrievedSources)
                .retrievedScores(retrievedScores)
                .firstRelevantRank(firstRelevantRank)
                .filteredByThreshold(filtered)
                .classifiedIntent(classifiedIntent)
                .expectedIntent(eq.getExpectedIntent() != null ? eq.getExpectedIntent().name() : null)
                .build();
    }

    /**
     * 从 classpath 或文件系统加载评估数据集。
     */
    List<EvaluationQuery> loadDataset(String datasetPath) {
        // 先尝试 classpath
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(datasetPath)) {
            if (is != null) {
                String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return JSON.parseObject(json, new TypeReference<List<EvaluationQuery>>() {});
            }
        } catch (IOException e) {
            log.debug("无法从 classpath 加载数据集: {}", datasetPath);
        }

        // 再尝试文件系统
        Path path = Paths.get(datasetPath);
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                return JSON.parseObject(json, new TypeReference<List<EvaluationQuery>>() {});
            } catch (IOException e) {
                log.error("无法从文件系统加载数据集: {}", datasetPath, e);
            }
        }

        log.error("数据集未找到: {} (尝试了 classpath 和文件系统)", datasetPath);
        return List.of();
    }
}
