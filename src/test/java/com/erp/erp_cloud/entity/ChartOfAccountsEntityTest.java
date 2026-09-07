package com.erp.erp_cloud.entity;

import com.erp.erp_cloud.enums.AccountCategory;
import com.erp.erp_cloud.enums.AccountClass;
import com.erp.erp_cloud.enums.AccountNature;
import com.erp.erp_cloud.enums.FinancialStatement;
import com.erp.erp_cloud.exception.InvalidOperationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Plain unit tests for {@link ChartOfAccounts#validateEntity()} -- no
 * Spring context, no database. This method only ever runs as a JPA
 * @PrePersist/@PreUpdate lifecycle callback, so ChartOfAccountServiceTest's
 * pure-Mockito tests (repository.save() is mocked) never actually trigger
 * it, leaving it with zero coverage before this file. validateEntity() is
 * package-private specifically so it can be called directly here.
 *
 * Found and fixed alongside this test: the category- and financial
 * statement-mismatch checks threw a raw, untranslated IllegalArgumentException
 * message straight to the frontend (no stable error code), and the financial
 * statement rule additionally had no service-level pre-check or frontend
 * mirror at all -- unlike every other structural rule on this entity. This
 * was the exact "Financial statement BALANCE_SHEET does not match account
 * class REVENUE" message the user hit live.
 *
 * Found and fixed later (2026-09-07): validateCategoryMatchesClass() itself
 * re-derived the class/category match with a hand-rolled, name-substring
 * heuristic instead of calling AccountCategory.belongsToClass() (the real
 * mapping). That heuristic silently rejected 15 of the 44 categories --
 * e.g. CASH_AND_EQUIVALENTS/ASSET -- as "mismatched" even though they are
 * perfectly valid, which is exactly what the user hit editing an existing
 * account. validateEntity_allValidClassStatementPairings_succeed() below
 * had masked this for months because it only tried ONE representative
 * category per class, and every one it picked happened to satisfy the
 * broken heuristic by coincidence. validateEntity_everyCategory_matchesItsOwnClass()
 * now iterates ALL AccountCategory values so a future regression like this
 * can't hide behind a lucky representative sample again.
 */
class ChartOfAccountsEntityTest {

    private ChartOfAccounts validAccount() {
        ChartOfAccounts account = new ChartOfAccounts();
        account.setCode("4");
        account.setName("INGRESOS");
        account.setLevel((byte) 1);
        account.setNature(AccountNature.C);
        account.setAccountClass(AccountClass.REVENUE);
        account.setAccountCategory(AccountCategory.OPERATING_REVENUE);
        account.setFinancialStatement(FinancialStatement.INCOME_STATEMENT);
        return account;
    }

    @Test
    @DisplayName("validateEntity() accepts a consistent class/category/statement combination")
    void validateEntity_consistentCombination_succeeds() {
        assertThatCode(() -> validAccount().validateEntity()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateEntity() rejects a blank code")
    void validateEntity_blankCode_throws() {
        ChartOfAccounts account = validAccount();
        account.setCode("  ");

        assertThatThrownBy(account::validateEntity)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");
    }

    @Test
    @DisplayName("validateEntity() rejects a blank name")
    void validateEntity_blankName_throws() {
        ChartOfAccounts account = validAccount();
        account.setName("");

        assertThatThrownBy(account::validateEntity)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("validateEntity() rejects a level below 1")
    void validateEntity_levelBelowOne_throws() {
        ChartOfAccounts account = validAccount();
        account.setLevel((byte) 0);

        assertThatThrownBy(account::validateEntity)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("level");
    }

    @Test
    @DisplayName("validateEntity() rejects an account category that doesn't belong to the account class")
    void validateEntity_categoryClassMismatch_throwsWithStableCode() {
        ChartOfAccounts account = validAccount();
        account.setAccountClass(AccountClass.ASSET);
        // OPERATING_REVENUE only belongs to REVENUE, set on validAccount()
        account.setFinancialStatement(FinancialStatement.BALANCE_SHEET);

        assertThatThrownBy(account::validateEntity)
                .isInstanceOfSatisfying(InvalidOperationException.class, ex -> {
                    assertThat(ex.getMessage()).contains("does not match account class");
                    assertThat(ex.getErrorCode()).isEqualTo("CATEGORY_CLASS_MISMATCH");
                });
    }

    @Test
    @DisplayName("validateEntity() rejects a financial statement that doesn't match the account class (REGRESSION GUARD)")
    void validateEntity_financialStatementClassMismatch_throwsWithStableCode() {
        // This is exactly the scenario the user hit: Class=REVENUE (Ingreso)
        // with FinancialStatement=BALANCE_SHEET (Estado de Situación
        // Financiera) instead of INCOME_STATEMENT (Estado de Resultados).
        ChartOfAccounts account = validAccount();
        account.setFinancialStatement(FinancialStatement.BALANCE_SHEET);

        assertThatThrownBy(account::validateEntity)
                .isInstanceOfSatisfying(InvalidOperationException.class, ex -> {
                    assertThat(ex.getMessage()).contains("does not match account class");
                    assertThat(ex.getErrorCode()).isEqualTo("FINANCIAL_STATEMENT_CLASS_MISMATCH");
                });
    }

    @Test
    @DisplayName("validateEntity() accepts every valid class/statement pairing (BALANCE_SHEET vs INCOME_STATEMENT)")
    void validateEntity_allValidClassStatementPairings_succeed() {
        record Case(AccountClass accountClass, AccountCategory category, FinancialStatement statement) {}

        var cases = new Case[]{
                new Case(AccountClass.ASSET, AccountCategory.CURRENT_ASSET, FinancialStatement.BALANCE_SHEET),
                new Case(AccountClass.LIABILITY, AccountCategory.CURRENT_LIABILITY, FinancialStatement.BALANCE_SHEET),
                new Case(AccountClass.EQUITY, AccountCategory.SHARE_CAPITAL, FinancialStatement.BALANCE_SHEET),
                new Case(AccountClass.REVENUE, AccountCategory.OPERATING_REVENUE, FinancialStatement.INCOME_STATEMENT),
                new Case(AccountClass.EXPENSE, AccountCategory.OPERATING_EXPENSE, FinancialStatement.INCOME_STATEMENT),
                new Case(AccountClass.COST, AccountCategory.COST_OF_SALES, FinancialStatement.INCOME_STATEMENT),
        };

        for (Case c : cases) {
            ChartOfAccounts account = validAccount();
            account.setAccountClass(c.accountClass());
            account.setAccountCategory(c.category());
            account.setFinancialStatement(c.statement());

            assertThatCode(account::validateEntity)
                    .as("class=%s category=%s statement=%s", c.accountClass(), c.category(), c.statement())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("validateEntity() accepts EVERY AccountCategory paired with its own real AccountClass (REGRESSION GUARD)")
    void validateEntity_everyCategory_matchesItsOwnClass() {
        // Exhaustive, not representative: the previous test only tried one
        // category per class, which is exactly how the name-substring bug
        // in validateCategoryMatchesClass() stayed invisible -- every
        // representative it happened to use (CURRENT_ASSET,
        // CURRENT_LIABILITY, SHARE_CAPITAL, OPERATING_REVENUE,
        // OPERATING_EXPENSE, COST_OF_SALES) satisfied the broken heuristic
        // by coincidence, while categories like CASH_AND_EQUIVALENTS or
        // ACCOUNTS_PAYABLE did not. Looping over every enum value pins down
        // the real contract: validateEntity() must accept ANY category
        // together with the class it actually declares
        // (AccountCategory.getAccountClass()), with no exceptions.
        for (AccountCategory category : AccountCategory.values()) {
            ChartOfAccounts account = validAccount();
            account.setAccountClass(category.getAccountClass());
            account.setAccountCategory(category);
            account.setFinancialStatement(
                    switch (category.getAccountClass()) {
                        case ASSET, LIABILITY, EQUITY -> FinancialStatement.BALANCE_SHEET;
                        case REVENUE, EXPENSE, COST -> FinancialStatement.INCOME_STATEMENT;
                    }
            );

            assertThatCode(account::validateEntity)
                    .as("category=%s (declared class=%s)", category, category.getAccountClass())
                    .doesNotThrowAnyException();
        }
    }
}
