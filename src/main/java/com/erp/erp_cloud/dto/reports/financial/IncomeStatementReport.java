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
 *$$TotalRevenue - TotalCosts = GrossProfit$$$$
 * GrossProfit - OperatingExpenses = OperatingIncome (EBIT)$$$$
 * OperatingIncome - NonOperating - Taxes = NetIncome$$
 **
 *
 * */



/**
 * Income Statement (Estado de Resultados) Report.
 *
 * Shows the financial performance of a company over a period of time.
 *
 * Structure:
 * - Revenue (what the company earned)
 * - Costs (direct costs of producing goods/services)
 * - Expenses (operating and non-operating expenses)
 * - Net Income (profit or loss)
 *
 * Formula: Revenue - Costs - Expenses - Taxes = Net Income
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncomeStatementReport {

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

    // ═══════════════════════════════════════════════════════════
    // REVENUE SECTION
    // ═══════════════════════════════════════════════════════════

    /**
     * All revenue sections grouped by category.
     * Examples: Operating Revenue, Non-Operating Revenue
     */
    private List<IncomeStatementSection> revenueSections;

    /**
     * Total of all revenue.
     * Sum of all revenue sections.
     */
    private BigDecimal totalRevenue;

    // ═══════════════════════════════════════════════════════════
    // COSTS SECTION
    // ═══════════════════════════════════════════════════════════

    /**
     * All cost sections grouped by category.
     * Examples: Cost of Goods Sold, Cost of Services
     */
    private List<IncomeStatementSection> costSections;

    /**
     * Total of all costs.
     * Sum of all cost sections.
     */
    private BigDecimal totalCosts;

    /**
     * Gross Profit = Total Revenue - Total Costs
     *
     * This is the profit before operating expenses.
     * Key metric for understanding product/service profitability.
     */
    private BigDecimal grossProfit;

    // ═══════════════════════════════════════════════════════════
    // EXPENSES SECTION
    // ═══════════════════════════════════════════════════════════

    /**
     * Operating expense sections.
     * Examples: Administrative, Sales, Personnel, Depreciation
     */
    private List<IncomeStatementSection> operatingExpenseSections;

    /**
     * Total operating expenses.
     */
    private BigDecimal totalOperatingExpenses;

    /**
     * Operating Income = Gross Profit - Operating Expenses
     *
     * Also known as EBIT (Earnings Before Interest and Taxes).
     * Shows profitability from core business operations.
     */
    private BigDecimal operatingIncome;

    /**
     * Non-operating expense sections.
     * Examples: Financial Expenses, Other Expenses
     */
    private List<IncomeStatementSection> nonOperatingExpenseSections;

    /**
     * Total non-operating expenses.
     */
    private BigDecimal totalNonOperatingExpenses;

    /**
     * Income Before Taxes = Operating Income - Non-Operating Expenses
     *
     * Also known as EBT (Earnings Before Taxes).
     */
    private BigDecimal incomeBeforeTaxes;

    /**
     * Tax expense sections.
     * Examples: Income Tax, Other Taxes
     */
    private List<IncomeStatementSection> taxExpenseSections;

    /**
     * Total tax expenses.
     */
    private BigDecimal totalTaxExpenses;

    // ═══════════════════════════════════════════════════════════
    // NET INCOME
    // ═══════════════════════════════════════════════════════════

    /**
     * Net Income = Income Before Taxes - Tax Expenses
     *
     * Also known as "Bottom Line" or "Profit/Loss"
     *
     * Positive = Profit (company made money)
     * Negative = Loss (company lost money)
     */
    private BigDecimal netIncome;

    // ═══════════════════════════════════════════════════════════
    // KEY METRICS (Financial Ratios)
    // ═══════════════════════════════════════════════════════════

    /**
     * Gross Profit Margin = (Gross Profit / Total Revenue) × 100
     *
     * Example: 60% means for every $100 in revenue, $60 is gross profit.
     * Higher is better - shows pricing power and production efficiency.
     */
    private BigDecimal grossProfitMargin;

    /**
     * Operating Margin = (Operating Income / Total Revenue) × 100
     *
     * Example: 20% means for every $100 in revenue, $20 is operating profit.
     * Shows efficiency of core business operations.
     */
    private BigDecimal operatingMargin;

    /**
     * Net Profit Margin = (Net Income / Total Revenue) × 100
     *
     * Example: 15% means for every $100 in revenue, $15 is net profit.
     * The "bottom line" profitability metric.
     */
    private BigDecimal netProfitMargin;

    // ═══════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════

    /**
     * Checks if the company was profitable during this period.
     */
    public boolean isProfitable() {
        return netIncome != null && netIncome.compareTo(BigDecimal.ZERO) > 0;
    }

    /**
     * Returns formatted report title.
     * Example: "Income Statement - January 1, 2026 to December 31, 2026"
     */
    public String getReportTitle() {
        return String.format("Income Statement - %s to %s", startDate, endDate);
    }

    /**
     * Returns formatted report title in Spanish.
     * Example: "Estado de Resultados - 1 de Enero de 2026 al 31 de Diciembre de 2026"
     */
    public String getReportTitleEs() {
        return String.format("Estado de Resultados - %s al %s", startDate, endDate);
    }

    /**
     * Returns formatted total revenue.
     */
    public String getFormattedTotalRevenue() {
        return formatCurrency(totalRevenue);
    }

    /**
     * Returns formatted gross profit.
     */
    public String getFormattedGrossProfit() {
        return formatCurrency(grossProfit);
    }

    /**
     * Returns formatted operating income.
     */
    public String getFormattedOperatingIncome() {
        return formatCurrency(operatingIncome);
    }

    /**
     * Returns formatted net income.
     */
    public String getFormattedNetIncome() {
        return formatCurrency(netIncome);
    }

    /**
     * Returns formatted gross profit margin.
     * Example: "60.3%"
     */
    public String getFormattedGrossProfitMargin() {
        return formatPercentage(grossProfitMargin);
    }

    /**
     * Returns formatted operating margin.
     * Example: "23.0%"
     */
    public String getFormattedOperatingMargin() {
        return formatPercentage(operatingMargin);
    }

    /**
     * Returns formatted net profit margin.
     * Example: "14.9%"
     */
    public String getFormattedNetProfitMargin() {
        return formatPercentage(netProfitMargin);
    }

    // Private helper methods for formatting
    private String formatCurrency(BigDecimal amount) {
        if (amount == null) return "$0.00";
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            return String.format("($%,.2f)", amount.abs());
        }
        return String.format("$%,.2f", amount);
    }

    private String formatPercentage(BigDecimal percentage) {
        if (percentage == null) return "0.0%";
        return String.format("%.1f%%", percentage);
    }
}