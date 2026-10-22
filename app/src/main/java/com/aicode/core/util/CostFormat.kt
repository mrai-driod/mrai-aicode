package com.aicode.core.util

import java.util.Locale

/** Cost display: small amounts keep 4 decimal places, tiny values collapse to <$0.0001. */
fun formatCostUsd(cost: Double): String = when {
    cost <= 0.0 -> "$0.00"
    cost < 0.0001 -> "<$0.0001"
    cost < 0.01 -> String.format(Locale.getDefault(), "$%.4f", cost)
    else -> String.format(Locale.getDefault(), "$%.2f", cost)
}
