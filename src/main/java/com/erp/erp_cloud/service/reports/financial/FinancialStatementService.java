package com.erp.erp_cloud.service.reports.financial;

import com.erp.erp_cloud.dto.reports.financial.*;
import com.erp.erp_cloud.entity.Company;
import com.erp.erp_cloud.enums.AccountCategory;
import com.erp.erp_cloud.enums.AccountClass;
import com.erp.erp_cloud.exception.InvalidOperationException;
import com.erp.erp_cloud.repository.ChartOfAccountsRepository;
import com.erp.erp_cloud.repository.JournalEntryRepository;
import com.erp.erp_cloud.security.context.TenantContext;
import com.erp.erp_cloud.service.base.TenantAwareService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FinancialStatementService extends TenantAwareService {

    private static final Logger log = LoggerFactory.getLogger(FinancialStatementService.class);

    private final JournalEntryRepository journalEntryRepository;
    private final ChartOfAccountsRepository chartOfAccountsRepository;

    // ═══════════════════════════════════════════════════════════
    // BALANCE SHEET
    // ═══════════════════════════════════════════════════════════

    /**
     * Generates a Balance Sheet (Estado de Situación Financiera) as of a specific date.
     *
     * The Balance Sheet shows:
     * - Assets (what the company owns)
     * - Liabilities (what the company owes)
     * - Equity (owner's stake in the company)
     *
     * ACCOUNTING EQUATION: Assets = Liabilities + Equity
     *
     * @param asOfDate The date for which to generate the balance sheet
     * @return Complete balance sheet with all sections and totals
     */
    public BalanceSheetReport getBalanceSheet(LocalDate asOfDate) {
        // Obtenemos la entidad Company desde el ThreadLocal sin generar queries SQL adicionales
        Company company = TenantContext.getCurrentCompany();
        Long companyId = currentTenantId();

        log.info("Generating Balance Sheet for company ID: {} as of {}", companyId, asOfDate);

        if (asOfDate == null) {
            throw new InvalidOperationException("Balance Sheet date cannot be null");
        }

        if (asOfDate.isAfter(LocalDate.now())) {
            throw new InvalidOperationException("Cannot generate Balance Sheet for future date: " + asOfDate);
        }

        // 1. Get account balances as of the specified date (ADAPTED to Long companyId)
        Map<String, BigDecimal> accountBalances = getAccountBalances(companyId, asOfDate);

        // 2. Build Asset sections
        List<BalanceSheetSection> assetSections = buildAssetSections(accountBalances);
        BigDecimal totalAssets = calculateTotal(assetSections);

        // 3. Build Liability sections
        List<BalanceSheetSection> liabilitySections = buildLiabilitySections(accountBalances);
        BigDecimal totalLiabilities = calculateTotal(liabilitySections);

        // 4. Build Equity sections
        List<BalanceSheetSection> equitySections = buildEquitySections(accountBalances);
        BigDecimal totalEquity = calculateTotal(equitySections);

        // 5. Verify the accounting equation
        boolean isBalanced = verifyAccountingEquation(totalAssets, totalLiabilities, totalEquity);

        if (!isBalanced) {
            log.error("BALANCE SHEET OUT OF BALANCE! Assets: {}, Liabilities: {}, Equity: {}",
                    totalAssets, totalLiabilities, totalEquity);
        }

        // 6. Build and return the report
        BalanceSheetReport report = BalanceSheetReport.builder()
                .companyName(company.getLegalName())
                .asOfDate(asOfDate)
                .assetSections(assetSections)
                .liabilitySections(liabilitySections)
                .equitySections(equitySections)
                .totalAssets(totalAssets)
                .totalLiabilities(totalLiabilities)
                .totalEquity(totalEquity)
                .totalLiabilitiesAndEquity(totalLiabilities.add(totalEquity))
                .balanced(isBalanced)
                .generatedAt(LocalDateTime.now())
                .build();

        log.info("Balance Sheet generated successfully. Assets: {}, L+E: {}, Balanced: {}",
                totalAssets, totalLiabilities.add(totalEquity), isBalanced);

        return report;
    }

    // ═══════════════════════════════════════════════════════════
    // ACCOUNT BALANCE CALCULATION
    // ═══════════════════════════════════════════════════════════

    /**
     * Calculates the balance for each account as of a specific date.
     *
     * Balance calculation:
     * - For Debit accounts (Assets, Expenses): Balance = Total Debits - Total Credits
     * - For Credit accounts (Liabilities, Equity, Revenue): Balance = Total Credits - Total Debits
     *
     * @param companyId The active tenant primitive ID
     * @param asOfDate Calculate balances up to and including this date
     * @return Map of account code to balance
     */
    private Map<String, BigDecimal> getAccountBalances(Long companyId, LocalDate asOfDate) {
        log.debug("Calculating account balances for company ID: {} as of {}", companyId, asOfDate);

        // ADAPTED: Calls repository using the numeric company ID parameter
        List<Object[]> balances = journalEntryRepository.getAccountBalancesAsOfDate(companyId, asOfDate);

        Map<String, BigDecimal> accountBalances = new HashMap<>();

        for (Object[] row : balances) {
            String accountCode = (String) row[0];
            String accountNature = (String) row[1];

            // Add null checks
            BigDecimal totalDebit = row[2] != null ? (BigDecimal) row[2] : BigDecimal.ZERO;
            BigDecimal totalCredit = row[3] != null ? (BigDecimal) row[3] : BigDecimal.ZERO;

            BigDecimal balance;
            if ("D".equals(accountNature)) {
                balance = totalDebit.subtract(totalCredit);
            } else {
                balance = totalCredit.subtract(totalDebit);
            }

            accountBalances.put(accountCode, balance.setScale(2, RoundingMode.HALF_UP));
        }

        log.debug("Calculated balances for {} accounts", accountBalances.size());

        return accountBalances;
    }

    // ═══════════════════════════════════════════════════════════
    // SECTION BUILDERS
    // ═══════════════════════════════════════════════════════════

    /**
     * Builds the Asset sections of the Balance Sheet.
     */
    private List<BalanceSheetSection> buildAssetSections(Map<String, BigDecimal> accountBalances) {
        return buildSectionsForClass(AccountClass.ASSET, accountBalances);
    }

    /**
     * Builds the Liability sections of the Balance Sheet.
     */
    private List<BalanceSheetSection> buildLiabilitySections(Map<String, BigDecimal> accountBalances) {
        return buildSectionsForClass(AccountClass.LIABILITY, accountBalances);
    }

    /**
     * Builds the Equity sections of the Balance Sheet.
     */
    private List<BalanceSheetSection> buildEquitySections(Map<String, BigDecimal> accountBalances) {
        return buildSectionsForClass(AccountClass.EQUITY, accountBalances);
    }

    /**
     * Generic method to build sections for a specific account class.
     */
    private List<BalanceSheetSection> buildSectionsForClass(
            AccountClass accountClass,
            Map<String, BigDecimal> accountBalances) {

        List<Object[]> accounts = chartOfAccountsRepository.getAccountsForBalanceSheet(
                currentTenantId(),
                accountClass
        );

        Map<AccountCategory, List<BalanceSheetSection.AccountLine>> categorizedAccounts = new LinkedHashMap<>();

        for (Object[] row : accounts) {
            try {
                String accountCode = (String) row[0];
                String accountName = (String) row[1];
                AccountCategory category = (AccountCategory) row[2];
                Integer displayOrder = row[3] != null ? (Integer) row[3] : 999;

                BigDecimal balance = accountBalances.getOrDefault(accountCode, BigDecimal.ZERO);

                // Only include accounts with non-zero balances
                if (balance.compareTo(BigDecimal.ZERO) != 0) {
                    BalanceSheetSection.AccountLine line = BalanceSheetSection.AccountLine.builder()
                            .accountCode(accountCode)
                            .accountName(accountName)
                            .balance(balance.abs()) // Always positive in display
                            .build();

                    categorizedAccounts
                            .computeIfAbsent(category, k -> new ArrayList<>())
                            .add(line);
                }
            } catch (ClassCastException e) {
                log.error("Error casting account data from row: {}", row, e);
            }
        }

        // Build sections from categorized accounts
        List<BalanceSheetSection> sections = new ArrayList<>();

        for (Map.Entry<AccountCategory, List<BalanceSheetSection.AccountLine>> entry : categorizedAccounts.entrySet()) {
            AccountCategory category = entry.getKey();
            List<BalanceSheetSection.AccountLine> lines = entry.getValue();

            BigDecimal sectionTotal = lines.stream()
                    .map(BalanceSheetSection.AccountLine::getBalance)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BalanceSheetSection section = BalanceSheetSection.builder()
                    .sectionName(category.getDisplayName())
                    .sectionNameEs(category.getDisplayNameEs())
                    .accountLines(lines)
                    .sectionTotal(sectionTotal)
                    .displayOrder(category.getDisplayOrder())
                    .build();

            sections.add(section);
        }

        sections.sort(Comparator.comparing(BalanceSheetSection::getDisplayOrder));

        return sections;
    }

    // ═══════════════════════════════════════════════════════════
    // CALCULATION HELPERS (Polymorphic)
    // ═══════════════════════════════════════════════════════════

    private <T extends FinancialSection> BigDecimal calculateTotal(List<T> sections) {
        if (sections == null || sections.isEmpty()) {
            return BigDecimal.ZERO;
        }

        return sections.stream()
                .map(FinancialSection::getSectionTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private boolean verifyAccountingEquation(
            BigDecimal totalAssets,
            BigDecimal totalLiabilities,
            BigDecimal totalEquity) {

        BigDecimal totalLiabilitiesAndEquity = totalLiabilities.add(totalEquity);
        BigDecimal difference = totalAssets.subtract(totalLiabilitiesAndEquity).abs();

        BigDecimal tolerance = new BigDecimal("0.01");

        return difference.compareTo(tolerance) <= 0;
    }

    // ═══════════════════════════════════════════════════════════
    // INCOME STATEMENT
    // ═══════════════════════════════════════════════════════════

    public IncomeStatementReport getIncomeStatement(LocalDate startDate, LocalDate endDate) {
        Company company = TenantContext.getCurrentCompany();
        Long companyId = currentTenantId();

        log.info("Generating Income Statement for company ID: {} from {} to {}",
                companyId, startDate, endDate);

        if (startDate == null || endDate == null) {
            throw new InvalidOperationException("Start date and end date are required for Income Statement");
        }

        if (startDate.isAfter(endDate)) {
            throw new InvalidOperationException("Start date cannot be after end date");
        }

        if (endDate.isAfter(LocalDate.now())) {
            throw new InvalidOperationException("Cannot generate Income Statement for future dates");
        }

        // STEP 1: Build Revenue Sections
        List<IncomeStatementSection> revenueSections = buildSectionsForIncomeStatement(
                AccountClass.REVENUE, startDate, endDate
        );
        BigDecimal totalRevenue = calculateTotal(revenueSections);

        // STEP 2: Build Cost Sections
        List<IncomeStatementSection> costSections = buildSectionsForIncomeStatement(
                AccountClass.COST, startDate, endDate
        );
        BigDecimal totalCosts = calculateTotal(costSections);

        // STEP 3: Calculate Gross Profit
        BigDecimal grossProfit = totalRevenue.subtract(totalCosts);

        // STEP 4: Build Expense Sections
        List<IncomeStatementSection> allExpenseSections = buildSectionsForIncomeStatement(
                AccountClass.EXPENSE, startDate, endDate
        );

        List<IncomeStatementSection> operatingExpenseSections = new ArrayList<>();
        List<IncomeStatementSection> nonOperatingExpenseSections = new ArrayList<>();
        List<IncomeStatementSection> taxExpenseSections = new ArrayList<>();

        for (IncomeStatementSection section : allExpenseSections) {
            String sectionName = section.getSectionName();

            if (sectionName.contains("Tax")) {
                taxExpenseSections.add(section);
            } else if (sectionName.contains("Financial") ||
                    sectionName.contains("Non-operating") ||
                    sectionName.contains("Other Expense")) {
                nonOperatingExpenseSections.add(section);
            } else {
                operatingExpenseSections.add(section);
            }
        }

        BigDecimal totalOperatingExpenses = calculateTotal(operatingExpenseSections);
        BigDecimal totalNonOperatingExpenses = calculateTotal(nonOperatingExpenseSections);
        BigDecimal totalTaxExpenses = calculateTotal(taxExpenseSections);

        // STEP 5: Calculate Key Subtotals
        BigDecimal operatingIncome = grossProfit.subtract(totalOperatingExpenses);
        BigDecimal incomeBeforeTaxes = operatingIncome.subtract(totalNonOperatingExpenses);
        BigDecimal netIncome = incomeBeforeTaxes.subtract(totalTaxExpenses);

        // STEP 6: Calculate Financial Metrics
        BigDecimal grossProfitMargin = calculateMargin(grossProfit, totalRevenue);
        BigDecimal operatingMargin = calculateMargin(operatingIncome, totalRevenue);
        BigDecimal netProfitMargin = calculateMargin(netIncome, totalRevenue);

        // STEP 7: Build and Return Report
        return IncomeStatementReport.builder()
                .companyName(company.getLegalName())
                .startDate(startDate)
                .endDate(endDate)
                .generatedAt(LocalDateTime.now())
                .revenueSections(revenueSections)
                .totalRevenue(totalRevenue.setScale(2, RoundingMode.HALF_UP))
                .costSections(costSections)
                .totalCosts(totalCosts.setScale(2, RoundingMode.HALF_UP))
                .grossProfit(grossProfit.setScale(2, RoundingMode.HALF_UP))
                .operatingExpenseSections(operatingExpenseSections)
                .totalOperatingExpenses(totalOperatingExpenses.setScale(2, RoundingMode.HALF_UP))
                .operatingIncome(operatingIncome.setScale(2, RoundingMode.HALF_UP))
                .nonOperatingExpenseSections(nonOperatingExpenseSections)
                .totalNonOperatingExpenses(totalNonOperatingExpenses.setScale(2, RoundingMode.HALF_UP))
                .incomeBeforeTaxes(incomeBeforeTaxes.setScale(2, RoundingMode.HALF_UP))
                .taxExpenseSections(taxExpenseSections)
                .totalTaxExpenses(totalTaxExpenses.setScale(2, RoundingMode.HALF_UP))
                .netIncome(netIncome.setScale(2, RoundingMode.HALF_UP))
                .grossProfitMargin(grossProfitMargin.setScale(1, RoundingMode.HALF_UP))
                .operatingMargin(operatingMargin.setScale(1, RoundingMode.HALF_UP))
                .netProfitMargin(netProfitMargin.setScale(1, RoundingMode.HALF_UP))
                .build();
    }

    private List<IncomeStatementSection> buildSectionsForIncomeStatement(
            AccountClass accountClass,
            LocalDate startDate,
            LocalDate endDate) {

        List<Object[]> accounts = chartOfAccountsRepository.getAccountsForIncomeStatement(
                currentTenantId(),
                accountClass,
                startDate,
                endDate
        );

        Map<AccountCategory, List<IncomeStatementSection.AccountLine>> categorizedAccounts = new LinkedHashMap<>();

        for (Object[] row : accounts) {
            try {
                String accountCode = (String) row[0];
                String accountName = (String) row[1];
                AccountCategory category = (AccountCategory) row[2];
                Integer displayOrder = row[3] != null ? (Integer) row[3] : 999;
                BigDecimal periodBalance = row[4] != null ? (BigDecimal) row[4] : BigDecimal.ZERO;

                if (periodBalance.compareTo(BigDecimal.ZERO) != 0) {
                    IncomeStatementSection.AccountLine line = IncomeStatementSection.AccountLine.builder()
                            .accountCode(accountCode)
                            .accountName(accountName)
                            .amount(periodBalance.abs())
                            .build();

                    categorizedAccounts
                            .computeIfAbsent(category, k -> new ArrayList<>())
                            .add(line);
                }
            } catch (Exception e) {
                log.error("Error processing account data from row", e);
            }
        }

        List<IncomeStatementSection> sections = new ArrayList<>();

        for (Map.Entry<AccountCategory, List<IncomeStatementSection.AccountLine>> entry : categorizedAccounts.entrySet()) {
            AccountCategory category = entry.getKey();
            List<IncomeStatementSection.AccountLine> lines = entry.getValue();

            BigDecimal sectionTotal = lines.stream()
                    .map(IncomeStatementSection.AccountLine::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            IncomeStatementSection section = IncomeStatementSection.builder()
                    .sectionName(category.getDisplayName())
                    .sectionNameEs(category.getDisplayNameEs())
                    .accountLines(lines)
                    .sectionTotal(sectionTotal.setScale(2, RoundingMode.HALF_UP))
                    .displayOrder(category.getDisplayOrder())
                    .build();

            sections.add(section);
        }

        sections.sort(Comparator.comparing(IncomeStatementSection::getDisplayOrder));

        return sections;
    }

    private BigDecimal calculateMargin(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        if (numerator == null) {
            return BigDecimal.ZERO;
        }
        return numerator
                .divide(denominator, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(1, RoundingMode.HALF_UP);
    }
}