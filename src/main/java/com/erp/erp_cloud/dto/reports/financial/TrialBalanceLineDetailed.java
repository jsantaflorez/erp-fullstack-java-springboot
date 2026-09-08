package com.erp.erp_cloud.dto.reports.financial;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Trial Balance line with opening balances and period movements.
 *
 * Shows the complete movement of an account during a period:
 * Opening Balance + Period Activity = Closing Balance
 *
 * Business Rule:
 * - Balance Sheet accounts (1,2,3): Have actual opening balances
 * - Income Statement accounts (4,5,6,7): Always start with zero (temporary accounts)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrialBalanceLineDetailed {

    /**
     * Account code (e.g., "110505")
     */
    private String accountCode;

    /**
     * Account name (e.g., "Caja General")
     */
    private String accountName;

    /**
     * Account class display name (e.g., "1 - Assets", "4 - Revenue")
     */
    private String accountClass;

    /**
     * Indicates if this is a Balance Sheet account.
     * TRUE = Classes 1, 2, 3 (Asset, Liability, Equity) - permanent accounts
     * FALSE = Classes 4, 5, 6, 7 (Revenue, Expense, Cost) - temporary accounts
     */
    private boolean balanceSheetAccount;

    /**
     * Opening balance at the start of the period.
     *
     * Balance Sheet accounts (1,2,3): Carries balance from prior period
     * Income Statement accounts (4,5,6,7): Always ZERO (resets each year)
     */
    private BigDecimal openingBalance;

    /**
     * Total debits posted during the period.
     */
    private BigDecimal periodDebit;

    /**
     * Total credits posted during the period.
     */
    private BigDecimal periodCredit;

    /**
     * Net movement during the period.
     * Calculated as: periodDebit - periodCredit
     */
    private BigDecimal netMovement;

    /**
     * Closing balance at the end of the period.
     * Calculated as: openingBalance + netMovement
     */
    private BigDecimal closingBalance;

    // ═══════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════

    /**
     * Returns formatted account description.
     * Example: "110505 - Caja General"
     */
    public String getFullDescription() {
        return accountCode + " - " + accountName;
    }

    /**
     * Returns formatted opening balance with currency.
     */
    public String getFormattedOpeningBalance() {
        if (openingBalance == null) return "$0.00";
        if (openingBalance.compareTo(BigDecimal.ZERO) < 0) {
            return String.format("($%,.2f)", openingBalance.abs());
        }
        return String.format("$%,.2f", openingBalance);
    }

    /**
     * Returns formatted period debit with currency.
     */
    public String getFormattedPeriodDebit() {
        return String.format("$%,.2f", periodDebit != null ? periodDebit : BigDecimal.ZERO);
    }

    /**
     * Returns formatted period credit with currency.
     */
    public String getFormattedPeriodCredit() {
        return String.format("$%,.2f", periodCredit != null ? periodCredit : BigDecimal.ZERO);
    }

    /**
     * Returns formatted net movement with currency.
     */
    public String getFormattedNetMovement() {
        if (netMovement == null) return "$0.00";
        if (netMovement.compareTo(BigDecimal.ZERO) < 0) {
            return String.format("($%,.2f)", netMovement.abs());
        }
        return String.format("$%,.2f", netMovement);
    }

    /**
     * Returns formatted closing balance with currency.
     */
    public String getFormattedClosingBalance() {
        if (closingBalance == null) return "$0.00";
        if (closingBalance.compareTo(BigDecimal.ZERO) < 0) {
            return String.format("($%,.2f)", closingBalance.abs());
        }
        return String.format("$%,.2f", closingBalance);
    }

    /**
     * Checks if this account had any activity during the period.
     */
    public boolean hasActivity() {
        return (periodDebit != null && periodDebit.compareTo(BigDecimal.ZERO) != 0) ||
                (periodCredit != null && periodCredit.compareTo(BigDecimal.ZERO) != 0);
    }
}