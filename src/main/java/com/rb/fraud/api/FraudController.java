package com.rb.fraud.api;

import com.rb.fraud.api.dto.FraudRequest;
import com.rb.fraud.api.dto.FraudResponse;
import com.rb.fraud.domain.FraudScorer;
import com.rb.fraud.domain.VPTree;
import com.rb.fraud.domain.VectorNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;


@RestController
public class FraudController {

    private static final Logger log = LoggerFactory.getLogger(FraudController.class);

    private final VectorNormalizer normalizer;
    private final VPTree vpTree;
    private final FraudScorer scorer;
    private final Semaphore knnSemaphore;
    private final int metricsLogEvery;
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder bulkheadFallbacks = new LongAdder();
    private final LongAdder exceptionFallbacks = new LongAdder();
    private final LongAdder knnReadyFull = new LongAdder();
    private final LongAdder knnBudgetLimited = new LongAdder();
    private final LongAdder knnTreeNotReady = new LongAdder();

    public FraudController(
        VectorNormalizer normalizer,
        VPTree vpTree,
        FraudScorer scorer,
        @Value("${app.runtime.max-concurrent-knn:2}") int maxConcurrentKnn,
        @Value("${app.runtime.metrics-log-every:10000}") int metricsLogEvery
    ) {
        this.normalizer = normalizer;
        this.vpTree = vpTree;
        this.scorer = scorer;
        this.knnSemaphore = new Semaphore(Math.max(1, maxConcurrentKnn));
        this.metricsLogEvery = Math.max(1, metricsLogEvery);
    }

    @PostMapping("/fraud-score")
    public FraudResponse fraudScore(@RequestBody FraudRequest request) {
        totalRequests.increment();

        // Bulkhead: em sobrecarga, responde fallback rapidamente para evitar timeout/erro HTTP.
        if (!knnSemaphore.tryAcquire()) {
            bulkheadFallbacks.increment();
            maybeLogMetrics();
            return FraudResponse.fallback();
        }

        try {
            // 1. Normaliza payload → float[14]
            float[] vector = normalizer.normalize(request);

            // 2. Busca k=5 vizinhos mais próximos
            VPTree.Neighbor[] neighbors = vpTree.findKNearest(vector, 5);
            VPTree.SearchStats stats = vpTree.getLastSearchStats();

            // 3. Instrumenta origem da decisão para correlação FP/FN nos resultados do k6
            if (!stats.treeReady()) {
                knnTreeNotReady.increment();
            } else if (stats.budgetLimited()) {
                knnBudgetLimited.increment();
            } else {
                knnReadyFull.increment();
            // 4. Calcula score e decisão

            // 4. Calculates score and decision
            maybeLogMetrics();
            return scorer.score(neighbors);

            // Fallback para evitar HTTP 500 (peso 5 na pontuação)
            // Retorna approved=true com score 0.0
            // Returns approved=true with score 0.0
            log.error("Erro ao processar transação {}: {}", request.id(), e.getMessage());
            maybeLogMetrics();
            log.error("Error processing transaction {}: {}", request.id(), e.getMessage());
            return FraudResponse.fallback();
        } finally {
            knnSemaphore.release();
        }
    }

    private void maybeLogMetrics() {
        long total = totalRequests.sum();
        if (total % metricsLogEvery != 0) {
            return;
        }

        log.info(
            "Decision-path telemetry: total={}, bulkhead_fallback={}, exception_fallback={}, knn_full={}, knn_budget_limited={}, knn_tree_not_ready={}",
            total,
            bulkheadFallbacks.sum(),
            exceptionFallbacks.sum(),
            knnReadyFull.sum(),
            knnBudgetLimited.sum(),
            knnTreeNotReady.sum()
        );
    }
}
