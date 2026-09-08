package com.erp.erp_cloud.dto.reports.financial;

import com.erp.erp_cloud.dto.TrialBalanceLine;
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
 * Current Period Trial Balance
 * Trial Balance Report (Balance de Comprobación).
 *
 * Shows all account balances with their debit and credit totals.
 * Used to verify that the accounting system is in balance before generating financial statements.
 *
 * Key Principle: Total Debits MUST equal Total Credits
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrialBalanceReport {

    /**
     * Company name for the report header.
     */
    private String companyName;

    /**
     * The date as of which the trial balance is prepared.
     * Typically end of month or year.
     */
    private LocalDate asOfDate;

    /**
     * When this report was generated.
     */
    private LocalDateTime generatedAt;

    /**
     * List of all account lines with their balances.
     * Each line shows: account code, name, debit total, credit total, and net balance.
     */
    private List<TrialBalanceLine> lines;

    /**
     * Sum of all debit amounts across all accounts.
     */
    private BigDecimal totalDebit;

    /**
     * Sum of all credit amounts across all accounts.
     */
    private BigDecimal totalCredit;

    /**
     * Indicates whether total debits equal total credits.
     * TRUE = System is in balance (normal state)
     * FALSE = System is out of balance (ERROR - should never happen!)
     */
    private boolean balanced;

    // NOTE: field is "balanced", not "isBalanced" -- Lombok still
    // generates isBalanced() as the getter (its usual boolean prefix
    // rule), but Jackson serializes that getter by stripping "is", so
    // a field literally named "isBalanced" would silently serialize as
    // JSON key "balanced" anyway. Naming the field "balanced" makes the
    // Java source match what actually goes over the wire.

    /**
     * Summary of net balances grouped by account class.
     *
     * Key: Account class name (e.g., "1 - Assets", "4 - Revenue")
     * Value: Net balance for that class
     *
     * This provides a quick overview of the financial position by major category.
     */
    private Map<String, BigDecimal> summary;

    // ═══════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════

    /**
     * Returns the difference between total debits and total credits.
     * Should be zero if the system is balanced.
     *
     * @return The imbalance amount (should be 0.00)
     */
    public BigDecimal getBalanceDifference() {
        if (totalDebit == null || totalCredit == null) {
            return BigDecimal.ZERO;
        }
        return totalDebit.subtract(totalCredit).abs();
    }

    /**
     * Returns the number of accounts in the trial balance.
     */
    public int getAccountCount() {
        return lines != null ? lines.size() : 0;
    }

    /**
     * Returns formatted report title.
     * Example: "Trial Balance - As of December 31, 2026"
     */
    public String getReportTitle() {
        return String.format("Trial Balance - As of %s", asOfDate);
    }

    /**
     * Returns formatted report title in Spanish.
     * Example: "Balance de Comprobación - Al 31 de Diciembre de 2026"
     */
    public String getReportTitleEs() {
        return String.format("Balance de Comprobación - Al %s", asOfDate);
    }

    /**
     * Returns formatted total debits with currency.
     * Example: "$1,250,000.00"
     */
    public String getFormattedTotalDebit() {
        return String.format("$%,.2f", totalDebit != null ? totalDebit : BigDecimal.ZERO);
    }

    /**
     * Returns formatted total credits with currency.
     * Example: "$1,250,000.00"
     */
    public String getFormattedTotalCredit() {
        return String.format("$%,.2f", totalCredit != null ? totalCredit : BigDecimal.ZERO);
    }
}