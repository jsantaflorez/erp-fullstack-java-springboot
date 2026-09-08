package com.erp.erp_cloud.dto.reports.financial;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Detailed Trial Balance Report with opening balances and period movements.
 *
 * Shows:
 * - Opening balance (start of period)
 * - Period activity (debits and credits during period)
 * - Closing balance (end of period)
 *
 * Formula: Opening Balance + (Period Debits - Period Credits) = Closing Balance
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrialBalanceReportDetailed {

    /**
     * Company name for the report header.
     */
    private String companyName;

    /**
     * Start date of the reporting period.
     */
    private LocalDate startDate;

    /**
     * End date of the reporting period.
     */
    private LocalDate endDate;

    /**
     * When this report was generated.
     */
    private LocalDateTime generatedAt;

    /**
     * List of all account lines with opening balances and movements.
     */
    private List<TrialBalanceLineDetailed> lines;

    /**
     * Sum of all opening balances.
     * Note: This will NOT be zero because Balance Sheet accounts carry forward.
     */
    private BigDecimal totalOpeningBalance;

    /**
     * Sum of all period debits.
     */
    private BigDecimal totalPeriodDebit;

    /**
     * Sum of all period credits.
     */
    private BigDecimal totalPeriodCredit;

    /**
     * Sum of all net movements (periodDebit - periodCredit).
     */
    private BigDecimal totalNetMovement;

    /**
     * Sum of all closing balances.
     */
    private BigDecimal totalClosingBalance;

    /**
     * Indicates whether period debits equal period credits.
     * TRUE = System is in balance (normal state)
     * FALSE = System is out of balance (ERROR)
     */
    private boolean balanced;

    /**
     * Summary of closing balances grouped by account class.
     * Key: Account class name (e.g., "1 - Assets", "4 - Revenue")
     * Value: Total closing balance for that class
     */
    private Map<String, BigDecimal> summaryByClass;

    // ═══════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════

    /**
     * Returns the difference between period debits and period credits.
     * Should be zero if the system is balanced.
     */
    public BigDecimal getBalanceDifference() {
        if (totalPeriodDebit == null || totalPeriodCredit == null) {
            return BigDecimal.ZERO;
        }
        return totalPeriodDebit.subtract(totalPeriodCredit).abs();
    }

    /**
     * Returns the number of accounts in the trial balance.
     */
    public int getAccountCount() {
        return lines != null ? lines.size() : 0;
    }

    /**
     * Returns formatted report title.
     * Example: "Trial Balance - January 1, 2026 to December 31, 2026"
     */
    public String getReportTitle() {
        return String.format("Trial Balance - %s to %s", startDate, endDate);
    }

    /**
     * Returns formatted report title in Spanish.
     * Example: "Balance de Comprobación - 1 de Enero de 2026 al 31 de Diciembre de 2026"
     */
    public String getReportTitleEs() {
        return String.format("Balance de Comprobación - %s al %s", startDate, endDate);
    }

    /**
     * Returns formatted total opening balance.
     */
    public String getFormattedTotalOpeningBalance() {
        return String.format("$%,.2f", totalOpeningBalance != null ? totalOpeningBalance : BigDecimal.ZERO);
    }

    /**
     * Returns formatted total period debit.
     */
    public String getFormattedTotalPeriodDebit() {
        return String.format("$%,.2f", totalPeriodDebit != null ? totalPeriodDebit : BigDecimal.ZERO);
    }

    /**
     * Returns formatted total period credit.
     */
    public String getFormattedTotalPeriodCredit() {
        return String.format("$%,.2f", totalPeriodCredit != null ? totalPeriodCredit : BigDecimal.ZERO);
    }

    /**
     * Returns formatted total closing balance.
     */
    public String getFormattedTotalClosingBalance() {
        return String.format("$%,.2f", totalClosingBalance != null ? totalClosingBalance : BigDecimal.ZERO);
    }
}