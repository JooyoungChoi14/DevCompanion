package com.devcompanion.llm.budget

/**
 * Per-model weight for normalized token calculation.
 *
 * Input/output weights are separated because providers charge differently
 * for input vs output tokens. The blended weight approximates overall cost
 * based on a typical input:output ratio (default 70:30).
 *
 * Base reference: Claude 4 Sonnet ($3.00 input / $15.00 output per 1M tokens).
 */
data class ModelWeight(
    val inputWeight: Double,     // base input price / this model's input price
    val outputWeight: Double,    // base output price / this model's output price
    val blendedRatio: Double = 0.7  // assumed input:output token ratio
) {
    /** Blended weight for simplified single-value budget estimation. */
    fun blended(): Double = inputWeight * blendedRatio + outputWeight * (1 - blendedRatio)

    companion object {
        /** Sentinel weight for free (local) models — no budget limit applies. */
        val FREE = ModelWeight(Double.MAX_VALUE, Double.MAX_VALUE)

        /** Default fallback weight when model is unknown. */
        val UNKNOWN = ModelWeight(1.0, 1.0)
    }
}