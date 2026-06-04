package com.rb.fraud.domain;

import com.rb.fraud.api.dto.FraudResponse;
import org.springframework.stereotype.Component;

/**
 * Calcula fraud_score a partir dos k vizinhos mais próximos
 * Conforme especificado em AGENTS.md seção 3
 *
 * Regras:
 * - fraud_score = número_de_fraudes_entre_5_vizinhos / 5
 * - approved = fraud_score < 0.6
 */
@Component
public class FraudScorer {

    private static final double THRESHOLD = 0.6;

    /**
     * Calcula score e decisão a partir dos vizinhos
     *
     * @param neighbors Array de k vizinhos mais próximos
     * @return FraudResponse com approved e fraud_score
     */
    public FraudResponse score(VPTree.Neighbor[] neighbors) {
        if (neighbors == null || neighbors.length == 0) {
            // Sem vizinhos = assume legítimo (evita HTTP 500)
            return FraudResponse.fallback();
        }

        int fraudCount = 0;
        for (VPTree.Neighbor neighbor : neighbors) {
            if (neighbor.isFraud()) {
                fraudCount++;
            }
        }

        double fraudScore = (double) fraudCount / neighbors.length;
        return FraudResponse.fromScore(THRESHOLD, fraudScore);
    }
}

