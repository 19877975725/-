package org.example.voice_assistant.rag.evaluation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RAG 管线运行时指标收集器。
 *
 * 收集意图路由分布、检索过滤率等实时指标，通过日志和
 * {@link #getSnapshot()} 暴露给外部监控。
 */
@Slf4j
@Service
public class RAGMetricsService {

    /** 总请求数 */
    private final AtomicLong totalRequests = new AtomicLong(0);
    /** 意图路由分布 */
    private final ConcurrentHashMap<String, AtomicLong> intentDistribution = new ConcurrentHashMap<>();
    /** 进入 RAG 检索的请求数 */
    private final AtomicLong ragRetrievals = new AtomicLong(0);
    /** 被 minScore 阈值过滤掉的检索数 */
    private final AtomicLong thresholdFiltered = new AtomicLong(0);
    /** RAG 流程成功数 */
    private final AtomicLong ragSuccess = new AtomicLong(0);
    /** RAG 降级到普通对话数 */
    private final AtomicLong ragFallback = new AtomicLong(0);

    public void recordRequest() {
        totalRequests.incrementAndGet();
    }

    public void recordIntent(String intent) {
        intentDistribution.computeIfAbsent(intent, k -> new AtomicLong(0)).incrementAndGet();
    }

    public void recordRetrieval() {
        ragRetrievals.incrementAndGet();
    }

    public void recordFiltered() {
        thresholdFiltered.incrementAndGet();
    }

    public void recordRagSuccess() {
        ragSuccess.incrementAndGet();
    }

    public void recordRagFallback() {
        ragFallback.incrementAndGet();
    }

    /**
     * 获取当前指标快照并输出到日志。
     */
    public Map<String, Object> getSnapshot() {
        long total = totalRequests.get();
        long retrievals = ragRetrievals.get();
        long filtered = thresholdFiltered.get();
        long success = ragSuccess.get();
        long fallback = ragFallback.get();

        double filterRate = retrievals > 0 ? (double) filtered / retrievals : 0;
        double fallbackRate = (success + fallback) > 0 ? (double) fallback / (success + fallback) : 0;

        Map<String, Object> snapshot = Map.of(
                "totalRequests", total,
                "intentDistribution", Map.copyOf(intentDistribution),
                "ragRetrievals", retrievals,
                "thresholdFiltered", filtered,
                "filterRate", String.format("%.2f%%", filterRate * 100),
                "ragSuccess", success,
                "ragFallback", fallback,
                "fallbackRate", String.format("%.2f%%", fallbackRate * 100)
        );

        log.info("RAG 管线指标: total={}, intent={}, retrievals={}, filterRate={}%, fallbackRate={}%",
                total, intentDistribution, retrievals,
                String.format("%.1f", filterRate * 100),
                String.format("%.1f", fallbackRate * 100));

        return snapshot;
    }

    /** 重置所有计数器 */
    public void reset() {
        totalRequests.set(0);
        intentDistribution.clear();
        ragRetrievals.set(0);
        thresholdFiltered.set(0);
        ragSuccess.set(0);
        ragFallback.set(0);
        log.info("RAG 指标已重置");
    }
}
