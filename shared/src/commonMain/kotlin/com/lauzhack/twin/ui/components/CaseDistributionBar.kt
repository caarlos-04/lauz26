package com.lauzhack.twin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lauzhack.twin.model.CaseType

/**
 * Horizontal bar split in 3 segments whose widths are the case probabilities.
 * 2dp gaps between segments; case names are always shown next to it (color is not the only cue).
 */
@Composable
fun CaseDistributionBar(probs: Map<CaseType, Double>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        CaseType.entries.forEach { case ->
            val p = probs[case] ?: 0.0
            if (p > 0) {
                Box(
                    Modifier.weight(p.toFloat()).fillMaxHeight()
                        .clip(RoundedCornerShape(1.dp))
                        .background(caseColor(case)),
                )
            }
        }
    }
}
