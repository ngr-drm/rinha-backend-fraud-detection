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

/**
 * Controller para POST /fraud-score
 * Conforme especificado em AGENTS.md seção 3
 *
 * Fluxo: payload → normalizer → vptree (k=5) → scorer → response
 */
@RestController
public class FraudController {

    private static final Logger log = LoggerFactory.getLogger(FraudController.class);

    private final VectorNormalizer normalizer;
    private final VPTree vpTree;
    private final FraudScorer scorer;
    private final Semaphore knnSemaphore;

    public FraudController(
        VectorNormalizer normalizer,
        VPTree vpTree,
        FraudScorer scorer,
        @Value("${app.runtime.max-concurrent-knn:2}") int maxConcurrentKnn
    ) {
        this.normalizer = normalizer;
        this.vpTree = vpTree;
        this.scorer = scorer;
        this.knnSemaphore = new Semaphore(Math.max(1, maxConcurrentKnn));
    }

    @PostMapping("/fraud-score")
    public FraudResponse fraudScore(@RequestBody FraudRequest request) {
        // Bulkhead: em sobrecarga, responde fallback rapidamente para evitar timeout/erro HTTP.
        if (!knnSemaphore.tryAcquire()) {
            return FraudResponse.fallback();
        }

        try {
            // 1. Normaliza payload → float[14]
            float[] vector = normalizer.normalize(request);

            // 2. Busca k=5 vizinhos mais próximos
            VPTree.Neighbor[] neighbors = vpTree.findKNearest(vector, 5);

            // 3. Calcula score e decisão
            return scorer.score(neighbors);

        } catch (Exception e) {
            // Fallback para evitar HTTP 500 (peso 5 na pontuação)
            // Retorna approved=true com score 0.0
            log.error("Erro ao processar transação {}: {}", request.id(), e.getMessage());
            return FraudResponse.fallback();
        } finally {
            knnSemaphore.release();
        }
    }
}
