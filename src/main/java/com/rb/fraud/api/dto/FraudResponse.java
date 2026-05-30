cdpackage com.rb.fraud.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO de saída para POST /fraud-score
 * Conforme especificado em AGENTS.md seção 3
 */
public record FraudResponse(
    boolean approved,
    @JsonProperty("fraud_score") double fraudScore
) {
    /**
     * Factory method que aplica a regra de decisão:
     * approved = fraud_score < 0.6
     */
    public static FraudResponse fromScore(double fraudScore) {
        return new FraudResponse(fraudScore < 0.6, fraudScore);
    }

    /**
     * Resposta fallback para evitar HTTP 500 (peso 5 na pontuação)
     * Retorna approved=true com score 0.0 em caso de erro
     */
    public static FraudResponse fallback() {
        return new FraudResponse(true, 0.0);
    }
}

