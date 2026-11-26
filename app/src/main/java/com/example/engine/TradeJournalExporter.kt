package com.example.engine

import com.example.model.TradeRecord
import com.example.model.TradeStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TradeJournalExporter {

    const val FILTER_ALL = "ALL"
    const val FILTER_WON = "WON"
    const val FILTER_LOST = "LOST"
    const val FILTER_OPEN = "OPEN"

    fun filterTrades(
        trades: List<TradeRecord>,
        query: String,
        statusFilter: String
    ): List<TradeRecord> {
        val cleanQuery = query.trim().lowercase(Locale.US)
        val normalizedStatus = statusFilter.trim().uppercase(Locale.US)

        return trades.filter { trade ->
            val st = TradeStatus.safeValueOf(trade.status)
            val statusMatches = when (normalizedStatus) {
                "", FILTER_ALL -> true
                FILTER_WON -> st == TradeStatus.WON
                FILTER_LOST -> st == TradeStatus.LOST || st == TradeStatus.REJECTED || st == TradeStatus.FAILED
                FILTER_OPEN -> st == TradeStatus.OPEN || st == TradeStatus.PENDING || st == TradeStatus.UNKNOWN
                else -> st.name == normalizedStatus
            }
            if (!statusMatches) return@filter false

            if (cleanQuery.isEmpty()) return@filter true

            trade.instrument.lowercase(Locale.US).contains(cleanQuery) ||
                trade.strategyName.lowercase(Locale.US).contains(cleanQuery) ||
                trade.marketRegime.lowercase(Locale.US).contains(cleanQuery) ||
                trade.mode.lowercase(Locale.US).contains(cleanQuery) ||
                trade.direction.lowercase(Locale.US).contains(cleanQuery) ||
                trade.status.lowercase(Locale.US).contains(cleanQuery) ||
                trade.explanation.lowercase(Locale.US).contains(cleanQuery)
        }
    }

    fun toCsv(trades: List<TradeRecord>): String {
        val header = listOf(
            "id",
            "instrument",
            "timeframe",
            "direction",
            "mode",
            "status",
            "amount",
            "payout_rate",
            "entry_price",
            "exit_price",
            "pnl",
            "strategy_name",
            "market_regime",
            "confidence_score",
            "calibrated_probability",
            "created_at_iso",
            "explanation"
        ).joinToString(",")

        val sb = StringBuilder(header).append('\n')
        for (trade in trades) {
            val row = listOf(
                csvCell(trade.id),
                csvCell(trade.instrument),
                csvCell(trade.timeframe),
                csvCell(trade.direction),
                csvCell(trade.mode),
                csvCell(trade.status),
                String.format(Locale.US, "%.2f", trade.amount),
                String.format(Locale.US, "%.4f", trade.payoutRate),
                String.format(Locale.US, "%.6f", trade.entryPrice),
                String.format(Locale.US, "%.6f", trade.exitPrice),
                String.format(Locale.US, "%.2f", trade.pnl),
                csvCell(trade.strategyName),
                csvCell(trade.marketRegime),
                trade.confidenceScore.toString(),
                String.format(Locale.US, "%.4f", trade.calibratedProbability),
                csvCell(formatUtcIso(trade.createdAtMillis)),
                csvCell(trade.explanation)
            ).joinToString(",")
            sb.append(row).append('\n')
        }
        return sb.toString()
    }

    fun toJson(
        trades: List<TradeRecord>,
        exportedAtMillis: Long = System.currentTimeMillis()
    ): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schema_version\": \"1.0\",\n")
        sb.append("  \"exported_at_iso\": \"").append(jsonEscape(formatUtcIso(exportedAtMillis))).append("\",\n")
        sb.append("  \"record_count\": ").append(trades.size).append(",\n")
        sb.append("  \"trades\": [")
        if (trades.isEmpty()) {
            sb.append("]\n}")
            return sb.toString()
        }
        sb.append('\n')
        trades.forEachIndexed { idx, t ->
            sb.append("    {\n")
            sb.append("      \"id\": \"").append(jsonEscape(t.id)).append("\",\n")
            sb.append("      \"instrument\": \"").append(jsonEscape(t.instrument)).append("\",\n")
            sb.append("      \"timeframe\": \"").append(jsonEscape(t.timeframe)).append("\",\n")
            sb.append("      \"direction\": \"").append(jsonEscape(t.direction)).append("\",\n")
            sb.append("      \"mode\": \"").append(jsonEscape(t.mode)).append("\",\n")
            sb.append("      \"status\": \"").append(jsonEscape(t.status)).append("\",\n")
            sb.append("      \"amount\": ").append(String.format(Locale.US, "%.2f", t.amount)).append(",\n")
            sb.append("      \"payout_rate\": ").append(String.format(Locale.US, "%.4f", t.payoutRate)).append(",\n")
            sb.append("      \"entry_price\": ").append(String.format(Locale.US, "%.6f", t.entryPrice)).append(",\n")
            sb.append("      \"exit_price\": ").append(String.format(Locale.US, "%.6f", t.exitPrice)).append(",\n")
            sb.append("      \"pnl\": ").append(String.format(Locale.US, "%.2f", t.pnl)).append(",\n")
            sb.append("      \"strategy_name\": \"").append(jsonEscape(t.strategyName)).append("\",\n")
            sb.append("      \"market_regime\": \"").append(jsonEscape(t.marketRegime)).append("\",\n")
            sb.append("      \"confidence_score\": ").append(t.confidenceScore).append(",\n")
            sb.append("      \"calibrated_probability\": ").append(String.format(Locale.US, "%.4f", t.calibratedProbability)).append(",\n")
            sb.append("      \"created_at_iso\": \"").append(jsonEscape(formatUtcIso(t.createdAtMillis))).append("\",\n")
            sb.append("      \"explanation\": \"").append(jsonEscape(t.explanation)).append("\"\n")
            sb.append("    }")
            if (idx < trades.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ]\n}")
        return sb.toString()
    }

    private fun formatUtcIso(millis: Long): String {
        val safeMillis = if (millis > 0L) millis else 0L
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(safeMillis))
    }

    private fun csvCell(raw: String): String {
        val needsQuote = raw.contains(',') || raw.contains('"') || raw.contains('\n') || raw.contains('\r')
        return if (!needsQuote) raw else "\"${raw.replace("\"", "\"\"")}\""
    }

    private fun jsonEscape(raw: String): String {
        val out = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) {
                    out.append(String.format(Locale.US, "\\u%04x", ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        return out.toString()
    }
}
