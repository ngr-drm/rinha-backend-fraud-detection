package com.rb.fraud.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FraudResponse(
    boolean approved,
    @JsonProperty("fraud_score") double fraudScore
) {

    /**
     * Factory method que aplica a regra de decisão:
     * approved = fraud_score < threshold
     */
    public static FraudResponse fromScore(double threshold, double fraudScore) {
        return new FraudResponse(fraudScore < threshold, fraudScore);
    }

    /**
     * Resposta fallback para evitar HTTP 500 (peso 5 na pontuação)
     * Retorna approved=true com score 0.0 em caso de erro
     */
    public static FraudResponse fallback() {
        return new FraudResponse(true, 0.0);
    }
}

