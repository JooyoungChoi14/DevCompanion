package com.devcompanion.llm.budget

import com.devcompanion.llm.LlmProvider
import com.devcompanion.llm.TokenUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Three-layer token budget manager.
 *
 * Layer 1: Dollar budget (user-facing) — session and monthly limits
 * Layer 2: Normalized Tokens (internal) — model-agnostic accounting
 * Layer 3: Slot allocation (realtime) — system/context/history/response
 *
 * Budget tracking is OFF by default. Users opt in via settings.
 * When OFF, no limits are enforced and no cost is tracked.
 */
class TokenBudget(
    private val pricing: DefaultPricing = DefaultPricing,
    private val slots: TokenSlots = TokenSlots()
) {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    private val _sessionLimit = MutableStateFlow(0.50)  // $0.50 default
    val sessionLimit: StateFlow<Double> = _sessionLimit

    private val _monthlyLimit = MutableStateFlow<Double?>(null)
    val monthlyLimit: StateFlow<Double?> = _monthlyLimit

    private val _exceedBehavior = MutableStateFlow(ExceedBehavior.WARN)
    val exceedBehavior: StateFlow<ExceedBehavior> = _exceedBehavior

    private val _sessionDollarSpent = MutableStateFlow(0.0)
    val sessionDollarSpent: StateFlow<Double> = _sessionDollarSpent

    private val _monthlyDollarSpent = MutableStateFlow(0.0)
    val monthlyDollarSpent: StateFlow<Double> = _monthlyDollarSpent

    private var currentProvider: LlmProvider? = null

    // Soft limit = 80% of hard limit
    val softLimit: Double get() = _sessionLimit.value * 0.80
    val hardLimit: Double get() = _sessionLimit.value

    /** Enable or disable budget tracking. No limits when disabled. */
    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (!enabled) {
            // Reset tracking when disabled
            _sessionDollarSpent.value = 0.0
        }
    }

    /** Update session dollar limit. */
    fun setSessionLimit(dollars: Double) {
        _sessionLimit.value = dollars
    }

    /** Update monthly dollar limit. null = unlimited. */
    fun setMonthlyLimit(dollars: Double?) {
        _monthlyLimit.value = dollars
    }

    /** Set what happens when budget is exceeded. */
    fun setExceedBehavior(behavior: ExceedBehavior) {
        _exceedBehavior.value = behavior
    }

    /** Update the current provider (affects pricing calculations). */
    fun setProvider(provider: LlmProvider) {
        currentProvider = provider
    }

    /** Reset session tracking (call at session start). */
    fun resetSession() {
        _sessionDollarSpent.value = 0.0
    }

    /** Reset monthly tracking (call at month start). */
    fun resetMonth() {
        _monthlyDollarSpent.value = 0.0
    }

    /**
     * Check whether a request can proceed given estimated token usage.
     * Returns a [BudgetCheck] result indicating the budget state.
     */
    fun canProceed(
        estimatedInputTokens: Int,
        estimatedOutputTokens: Int = 0,
        provider: LlmProvider = currentProvider ?: return BudgetCheck.Ok(0.0)
    ): BudgetCheck {
        if (!_enabled.value) return BudgetCheck.Ok(0.0)

        val estimatedCost = pricing.estimateCost(provider, estimatedInputTokens, estimatedOutputTokens)
        val projectedTotal = _sessionDollarSpent.value + estimatedCost

        return when {
            projectedTotal > hardLimit -> BudgetCheck.ExceedsHard
            projectedTotal > softLimit -> BudgetCheck.ExceedsSoft(estimatedCost)
            else -> BudgetCheck.Ok(estimatedCost)
        }
    }

    /**
     * Record actual token usage from an API response.
     * Updates both session and monthly tracking.
     */
    fun recordUsage(usage: TokenUsage, provider: LlmProvider = currentProvider ?: return) {
        if (!_enabled.value) return

        val cost = pricing.estimateCost(provider, usage.inputTokens, usage.outputTokens)
        _sessionDollarSpent.value += cost
        _monthlyDollarSpent.value += cost
    }

    /**
     * Record a streaming token estimate (character-level approximation).
     * Used during streaming before the final usage report arrives.
     * Will be corrected when recordUsage() is called with actual counts.
     */
    fun recordStreamingEstimate(charCount: Int, isOutput: Boolean, provider: LlmProvider = currentProvider ?: return) {
        if (!_enabled.value) return

        val tokensPerChar = if (isOutput) 0.25 else 0.5  // rough: 4 chars/token output, 2 chars/token input
        val estimatedTokens = (charCount * tokensPerChar).toInt()
        val cost = pricing.estimateCost(provider, if (!isOutput) estimatedTokens else 0, if (isOutput) estimatedTokens else 0)
        _sessionDollarSpent.value += cost
        _monthlyDollarSpent.value += cost
    }

    /**
     * Check if there's enough budget headroom to inject repo context.
     * Returns true if the remaining budget can absorb at least 20% for context.
     */
    fun shouldInjectRepoContext(): Boolean {
        if (!_enabled.value) return true  // No budget = no constraint
        val remaining = hardLimit - _sessionDollarSpent.value
        return remaining > hardLimit * 0.20  // At least 20% headroom
    }

    /** Remaining dollar budget for the current session. */
    val remainingSessionBudget: Double get() = (hardLimit - _sessionDollarSpent.value).coerceAtLeast(0.0)

    /** Percentage of session budget used. */
    val sessionPercentUsed: Double get() = if (hardLimit > 0) (_sessionDollarSpent.value / hardLimit * 100.0) else 0.0

    /**
     * Calculate token slot allocation for a given context window size.
     */
    fun allocateSlots(contextWindow: Int, provider: LlmProvider = currentProvider ?: return emptySlots()): SlotAllocation {
        val totalTokens = contextWindow
        return SlotAllocation(
            systemPrompt = (totalTokens * slots.systemPrompt).toInt(),
            webContext = (totalTokens * slots.webContext).toInt(),
            chatHistory = (totalTokens * slots.chatHistory).toInt(),
            responseBuffer = (totalTokens * slots.responseBuffer).toInt()
        )
    }

    private fun emptySlots() = SlotAllocation(0, 0, 0, 0)
}

/** Slot percentage configuration. Must sum to 1.0. */
data class TokenSlots(
    val systemPrompt: Double = 0.25,
    val webContext: Double = 0.35,
    val chatHistory: Double = 0.25,
    val responseBuffer: Double = 0.15
) {
    init {
        val sum = systemPrompt + webContext + chatHistory + responseBuffer
        require(kotlin.math.abs(sum - 1.0) < 0.001) {
            "Slot percentages must sum to 1.0, got $sum"
        }
    }
}

/** Concrete token allocation per slot. */
data class SlotAllocation(
    val systemPrompt: Int,
    val webContext: Int,
    val chatHistory: Int,
    val responseBuffer: Int
)

/** What happens when the budget is exceeded. */
enum class ExceedBehavior {
    WARN,       // Show warning, continue
    PAUSE,      // Pause and ask user
    HARD_STOP   // Block the request
}

/** Result of a budget check before sending a request. */
sealed class BudgetCheck {
    data class Ok(val projectedCost: Double) : BudgetCheck()
    data class ExceedsSoft(val projectedCost: Double) : BudgetCheck()
    object ExceedsHard : BudgetCheck()
}