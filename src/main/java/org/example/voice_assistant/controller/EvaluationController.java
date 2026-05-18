package org.example.voice_assistant.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.voice_assistant.rag.evaluation.EvaluationResult;
import org.example.voice_assistant.rag.evaluation.RAGEvaluationService;
import org.example.voice_assistant.rag.evaluation.RAGMetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RAG 评估控制器。
 *
 * <pre>
 *   POST /api/evaluation/run             执行离线评估（Recall@K、MRR、Intent 准确率）
 *   GET  /api/evaluation/metrics          获取运行时管线指标快照
 *   POST /api/evaluation/metrics/reset    重置运行时指标
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/evaluation")
@RequiredArgsConstructor
public class EvaluationController {

    private final RAGEvaluationService evaluationService;
    private final RAGMetricsService metricsService;

    /** 执行离线评估 */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runEvaluation(
            @RequestParam(value = "dataset", required = false) String datasetPath) {

        log.info("开始执行 RAG 评估, datasetPath={}", datasetPath);

        EvaluationResult result;
        if (datasetPath != null && !datasetPath.isBlank()) {
            result = evaluationService.evaluate(datasetPath);
        } else {
            result = evaluationService.evaluate();
        }

        return ResponseEntity.ok(Map.of("success", true, "data", result));
    }

    /** 获取运行时管线指标快照 */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        return ResponseEntity.ok(Map.of("success", true, "data", metricsService.getSnapshot()));
    }

    /** 重置运行时指标 */
    @PostMapping("/metrics/reset")
    public ResponseEntity<Map<String, Object>> resetMetrics() {
        metricsService.reset();
        return ResponseEntity.ok(Map.of("success", true, "message", "指标已重置"));
    }
}
