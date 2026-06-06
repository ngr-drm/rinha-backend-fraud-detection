package com.rb.fraud.domain;

import com.rb.fraud.api.dto.FraudRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Normaliza payload JSON → float[14]
 * Constantes de normalization.json:
 * - max_amount: 10000
 * - max_installments: 12
 * - amount_vs_avg_ratio: 10
 * - max_minutes: 1440
 * - max_km: 1000
 * - max_tx_count_24h: 20
 * - max_merchant_avg_amount: 10000
 */
@Component
public class VectorNormalizer {

    // Constantes de normalização (hardcoded para performance - valores de normalization.json)
    private static final float MAX_AMOUNT = 10000f;
    private static final float MAX_INSTALLMENTS = 12f;
    private static final float AMOUNT_VS_AVG_RATIO = 10f;
    private static final float MAX_MINUTES = 1440f;
    private static final float MAX_KM = 1000f;
    private static final float MAX_TX_COUNT_24H = 20f;
    private static final float MAX_MERCHANT_AVG_AMOUNT = 10000f;

    // MCC Risk map (valores de mcc_risk.json)
    private static final Map<String, Float> MCC_RISK = Map.of(
        "5411", 0.15f,
        "5812", 0.30f,
        "5912", 0.20f,
        "5944", 0.45f,
        "7801", 0.80f,
        "7802", 0.75f,
        "7995", 0.85f,
        "4511", 0.35f,
        "5311", 0.25f,
        "5999", 0.50f
    );
    private static final float DEFAULT_MCC_RISK = 0.5f;

    /**
     * Transforma um FraudRequest em vetor de 14 dimensões
     */
    public float[] normalize(FraudRequest request) {
        float[] vector = new float[14];

        // Parse leve do timestamp (Instant.parse é otimizado p/ ISO_INSTANT)
        Instant requestedAt = Instant.parse(request.transaction().requestedAt());
        LocalDateTime requestedAtUtc = LocalDateTime.ofInstant(requestedAt, ZoneOffset.UTC);

        // Índice 0: amount
        vector[0] = clamp(request.transaction().amount() / MAX_AMOUNT);

        // Índice 1: installments
        vector[1] = clamp(request.transaction().installments() / MAX_INSTALLMENTS);

        // Índice 2: amount_vs_avg
        float avgAmount = (float) request.customer().avgAmount();
        vector[2] = avgAmount > 0
            ? clamp((float) (request.transaction().amount() / avgAmount) / AMOUNT_VS_AVG_RATIO)
            : 0f;

        // Índice 3: hour_of_day (0-23 UTC, dividido por 23)
        vector[3] = requestedAtUtc.getHour() / 23f;

        // Índice 4: day_of_week (Mon=0, Sun=6, dividido por 6)
        int dayOfWeek = requestedAtUtc.getDayOfWeek().getValue() - 1; // 0-6
        vector[4] = dayOfWeek / 6f;

        // Índices 5 e 6: dependem de last_transaction
        if (request.lastTransaction() != null) {
            // Índice 5: minutes_since_last_tx
            Instant lastTxTime = Instant.parse(request.lastTransaction().timestamp());
            long minutes = (requestedAt.getEpochSecond() - lastTxTime.getEpochSecond()) / 60L;
            vector[5] = clamp(minutes / MAX_MINUTES);

            // Índice 6: km_from_last_tx
            vector[6] = clamp((float) request.lastTransaction().kmFromCurrent() / MAX_KM);
        } else {
            // Sentinela -1 para ausência de transação anterior
            vector[5] = -1f;
            vector[6] = -1f;
        }

        // Índice 7: km_from_home
        vector[7] = clamp((float) request.terminal().kmFromHome() / MAX_KM);

        // Índice 8: tx_count_24h
        vector[8] = clamp(request.customer().txCount24h() / MAX_TX_COUNT_24H);

        // Índice 9: is_online
        vector[9] = request.terminal().isOnline() ? 1f : 0f;

        // Índice 10: card_present
        vector[10] = request.terminal().cardPresent() ? 1f : 0f;

        // Índice 11: unknown_merchant (1 se desconhecido, 0 se conhecido)
        boolean known = request.customer().knownMerchants() != null
            && request.customer().knownMerchants().contains(request.merchant().id());
        vector[11] = known ? 0f : 1f;

        // Índice 12: mcc_risk
        vector[12] = MCC_RISK.getOrDefault(request.merchant().mcc(), DEFAULT_MCC_RISK);

        // Índice 13: merchant_avg_amount
        vector[13] = clamp((float) request.merchant().avgAmount() / MAX_MERCHANT_AVG_AMOUNT);

        return vector;
    }

    /**
     * Clamp: max(0.0, min(1.0, x))
     */
    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float clamp(double value) {
        return clamp((float) value);
    }
}

