package com.erp.erp_cloud.dto.reports.financial;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Complete Balance Sheet (Estado de Situación Financiera) report.
 *
 * Shows the financial position of a company at a specific point in time.
 *
 * Structure:
 * - Assets (what the company owns)
 * - Liabilities (what the company owes)
 * - Equity (owner's stake)
 *
 * Fundamental Equation: Assets = Liabilities + Equity
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceSheetReport {

    /**
     * Company name for the report header
     */
    private String companyName;

    /**
     * The date as of which the balance sheet is prepared.
     * E.g., "As of December 31, 2026"
     */
    private LocalDate asOfDate;

    /**
     * When this report was generated.
     */
    private LocalDateTime generatedAt;

    // ═══════════════════════════════════════════════════════════
    // ASSETS SECTION
    // ═══════════════════════════════════════════════════════════

    /**
     * All asset sections grouped by category.
     * Examples: Current Assets, Fixed Assets, Intangible Assets
     */
    private List<BalanceSheetSection> assetSections;

    /**
     * Total of all assets.
     * Sum of all asset sections.
     */
    private BigDecimal totalAssets;

    // ═══════════════════════════════════════════════════════════
    // LIABILITIES SECTION
    // ═══════════════════════════════════════════════════════════

    /**
     * All liability sections grouped by category.
     * Examples: Current Liabilities, Long-term Liabilities
     */
    private List<BalanceSheetSection> liabilitySections;

    /**
     * Total of all liabilities.
     * Sum of all liability sections.
     */
    private BigDecimal totalLiabilities;

    // ═══════════════════════════════════════════════════════════
    // EQUITY SECTION
    // ═══════════════════════════════════════════════════════════

    /**
     * All equity sections grouped by category.
     * Examples: Share Capital, Retained Earnings, Current Year Profit
     */
    private List<BalanceSheetSection> equitySections;

    /**
     * Total equity.
     * Sum of all equity sections.
     */
    private BigDecimal totalEquity;

    // ═══════════════════════════════════════════════════════════
    // TOTALS AND VALIDATION
    // ═══════════════════════════════════════════════════════════

    /**
     * Total Liabilities + Total Equity.
     * This MUST equal Total Assets.
     */
    private BigDecimal totalLiabilitiesAndEquity;

    /**
     * Indicates whether the balance sheet balances.
     * TRUE if: Assets = Liabilities + Equity (within rounding tolerance)
     * FALSE if: The equation doesn't hold (indicates data error)
     */
    private boolean balanced;

    // ═══════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════

    /**
     * Returns the difference between assets and liabilities+equity.
     * Should be zero (or very close to zero) if balanced.
     */
    public BigDecimal getBalanceDifference() {
        if (totalAssets == null || totalLiabilitiesAndEquity == null) {
            return BigDecimal.ZERO;
        }
        return totalAssets.subtract(totalLiabilitiesAndEquity);
    }

    /**
     * Returns a formatted title for the report.
     * Example: "Balance Sheet - As of December 31, 2026"
     */
    public String getReportTitle() {
        return String.format("Balance Sheet - As of %s", asOfDate);
    }

    /**
     * Returns a formatted title in Spanish.
     * Example: "Estado de Situación Financiera - Al 31 de Diciembre de 2026"
     */
    public String getReportTitleEs() {
        return String.format("Estado de Situación Financiera - Al %s", asOfDate);
    }
}