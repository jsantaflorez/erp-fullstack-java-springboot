package com.erp.erp_cloud.service;

import com.erp.erp_cloud.dto.ChartOfAccountRequest;
import com.erp.erp_cloud.dto.ChartOfAccountResponseDTO;
import com.erp.erp_cloud.entity.ChartOfAccounts;
import com.erp.erp_cloud.entity.Company;
import com.erp.erp_cloud.enums.AccountCategory;
import com.erp.erp_cloud.enums.AccountClass;
import com.erp.erp_cloud.enums.AccountNature;
import com.erp.erp_cloud.enums.FinancialStatement;
import com.erp.erp_cloud.exception.DuplicateResourceException;
import com.erp.erp_cloud.exception.InvalidOperationException;
import com.erp.erp_cloud.exception.ResourceNotFoundException;
import com.erp.erp_cloud.repository.ChartOfAccountsRepository;
import com.erp.erp_cloud.repository.CompanyRepository;
import com.erp.erp_cloud.security.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for ChartOfAccountService's PUC (Plan Unico de Cuentas)
 * business rules -- no Spring context, no database. Mirrors the manual
 * scenarios in checklist-pruebas-erp.md under "Plan de Cuentas".
 *
 * Runs via `./gradlew test` (no live MySQL needed).
 */
class ChartOfAccountServiceTest {

    private static final Long COMPANY_ID = 1L;

    @Mock private ChartOfAccountsRepository repository;
    @Mock private CompanyRepository companyRepository;

    private ChartOfAccountService service;
    private Company testCompany;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        TenantContext.setCurrentTenant(COMPANY_ID);

        service = new ChartOfAccountService(repository, companyRepository);

        testCompany = new Company();
        testCompany.setId(COMPANY_ID);

        when(companyRepository.getReferenceById(COMPANY_ID)).thenReturn(testCompany);
        when(repository.save(any(ChartOfAccounts.class)))
                .thenAnswer(invocation -> {
                    ChartOfAccounts acc = invocation.getArgument(0);
                    if (acc.getId() == null) acc.setId(100L);
                    return acc;
                });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ChartOfAccountRequest baseRequest(String code, Boolean postingAccount, Long parentId) {
        ChartOfAccountRequest req = new ChartOfAccountRequest();
        req.setCode(code);
        req.setName("Test account " + code);
        req.setNature(AccountNature.D);
        req.setAccountClass(AccountClass.ASSET);
        req.setAccountCategory(AccountCategory.CURRENT_ASSET);
        req.setFinancialStatement(FinancialStatement.BALANCE_SHEET);
        req.setPostingAccount(postingAccount);
        req.setParentId(parentId);
        return req;
    }

    private ChartOfAccounts existingAccount(Long id, String code, byte level, boolean postingAccount,
                                             AccountClass accountClass, ChartOfAccounts parent) {
        ChartOfAccounts acc = new ChartOfAccounts();
        acc.setId(id);
        acc.setCode(code);
        acc.setLevel(level);
        acc.setName("Existing " + code);
        acc.setNature(AccountNature.D);
        acc.setAccountClass(accountClass);
        acc.setAccountCategory(AccountCategory.CURRENT_ASSET);
        acc.setFinancialStatement(FinancialStatement.BALANCE_SHEET);
        acc.setPostingAccount(postingAccount);
        acc.setActive(true);
        acc.setCompany(testCompany);
        acc.setParent(parent);
        return acc;
    }

    // ============================================================
    // Root account code structure — checklist: "cuentas raiz deben
    // tener exactamente 1 digito"
    // ============================================================

    @Test
    @DisplayName("create() accepts a root account with exactly 1 digit")
    void create_rootAccount_oneDigit_succeeds() {
        ChartOfAccountRequest request = baseRequest("1", false, null);

        ChartOfAccountResponseDTO result = service.create(request);

        assertThat(result.getCode()).isEqualTo("1");
    }

    @Test
    @DisplayName("create() rejects a root account with more than 1 digit")
    void create_rootAccount_multipleDigits_throws() {
        ChartOfAccountRequest request = baseRequest("11", false, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(InvalidOperationException.class, ex -> {
                    assertThat(ex.getMessage()).contains("exactly 1 digit");
                    assertThat(ex.getErrorCode()).isEqualTo("ROOT_ACCOUNT_CODE_INVALID_LENGTH");
                });
    }

    // ============================================================
    // Child code structure — prefix rule + digit-jump rule
    // ============================================================

    @Test
    @DisplayName("create() rejects a child code that does not start with the parent code")
    void create_childCode_wrongPrefix_throws() {
        ChartOfAccounts parent = existingAccount(1L, "1", (byte) 1, false, AccountClass.ASSET, null);
        when(repository.findByIdAndCompany(1L, COMPANY_ID)).thenReturn(Optional.of(parent));

        ChartOfAccountRequest request = baseRequest("21", false, 1L);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(InvalidOperationException.class, ex -> {
                    assertThat(ex.getMessage()).contains("must start with parent code");
                    assertThat(ex.getErrorCode()).isEqualTo("CHILD_CODE_MUST_START_WITH_PARENT");
                });
    }

    @ParameterizedTest(name = "parent code {0} + child code {1} -> valid jump")
    @DisplayName("create() accepts the standard PUC digit jumps (1->2->4->6...)")
    @CsvSource({
            "1, 11",
            "11, 1105",
            "1105, 110501"
    })
    void create_childCode_validDigitJump_succeeds(String parentCode, String childCode) {
        byte parentLevel = (byte) (parentCode.length() == 1 ? 1 : parentCode.length() == 2 ? 2 : 3);
        ChartOfAccounts parent = existingAccount(1L, parentCode, parentLevel, false, AccountClass.ASSET, null);
        when(repository.findByIdAndCompany(1L, COMPANY_ID)).thenReturn(Optional.of(parent));

        ChartOfAccountRequest request = baseRequest(childCode, false, 1L);

        ChartOfAccountResponseDTO result = service.create(request);

        assertThat(result.getCode()).isEqualTo(childCode);
    }

    @Test
    @DisplayName("create() rejects a child code with the wrong digit jump (e.g. 1 digit -> 3 digits)")
    void create_childCode_invalidDigitJump_throws() {
        ChartOfAccounts parent = existingAccount(1L, "1", (byte) 1, false, AccountClass.ASSET, null);
        when(repository.findByIdAndCompany(1L, COMPANY_ID)).thenReturn(Optional.of(parent));

        ChartOfAccountRequest request = baseRequest("110", false, 1L); // should be 2 digits, not 3

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(InvalidOperationException.class, ex -> {
                    assertThat(ex.getMessage()).contains("Invalid code structure");
                    assertThat(ex.getErrorCode()).isEqualTo("INVALID_CODE_STRUCTURE");
                });
    }

    // ============================================================
    // Posting (movement) accounts — checklist: "cuentas de movimiento
    // requieren al menos 6 digitos"
    // ============================================================

    @Test
    @DisplayName("create() rejects a posting account with fewer than 6 digits")
    void create_postingAccount_tooShort_throws() {
        ChartOfAccounts parent = existingAccount(1L, "1105", (byte) 3, false, AccountClass.ASSET, null);
        when(repository.findByIdAndCompany(1L, COMPANY_ID)).thenReturn(Optional.of(parent));

        ChartOfAccountRequest request = baseRequest("1105", true, 1L); // 4 digits, posting=true

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(InvalidOperationException.class, ex -> {
                    assertThat(ex.getMessage()).contains("at least 6 digits");
                    assertThat(ex.getErrorCode()).isEqualTo("POSTING_ACCOUNT_CODE_TOO_SHORT");
                });
    }

    @Test
    @DisplayName("create() accepts a posting account with exactly 6 digits")
    void create_postingAccount_sixDigits_succeeds() {
        ChartOfAccounts parent = existingAccount(1L, "1105", (byte) 3, false, AccountClass.ASSET, null);
        when(repository.findByIdAndCompany(1L, COMPANY_ID)).thenReturn(Optional.of(parent));

        ChartOfAccountRequest request = baseRequest("110501", true, 1L);

        ChartOfAccountResponseDTO result = service.create(request);

        assertThat(result.getCode()).isEqualTo("110501");
    }

    // ============================================================
    // Hierarchy rules
    // ============================================================

    @Test
    @DisplayName("create() rejects adding a child under a posting (auxiliary) account")
    void create_parentIsPostingAccount_throws() {
        ChartOfAccounts parent = existingAccount(1L, "110501", (byte) 4, true, AccountClass.ASSET, null);
        when(repository.findByIdAndCompany(1L, COMPANY_ID)).thenReturn(Optional.of(parent));

        ChartOfAccountRequest request = baseRequest("11050101", false, 1L);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("posting account");
    }

    @Test
    @DisplayName("create() rejects a child whose AccountClass differs from its parent's")
    void create_accountClassMismatchWithParent_throws() {
        ChartOfAccounts parent = existingAccount(1L, "1", (byte) 1, false, AccountClass.ASSET, null);
        when(repository.findByIdAndCompany(1L, COMPANY_ID)).thenReturn(Optional.of(parent));

        ChartOfAccountRequest request = baseRequest("11", false, 1L);
        request.setAccountClass(AccountClass.LIABILITY); // parent is ASSET

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(InvalidOperationException.class, ex -> {
                    assertThat(ex.getMessage()).contains("AccountClass mismatch");
                    assertThat(ex.getErrorCode()).isEqualTo("PARENT_CLASS_MISMATCH");
                });
    }

    @Test
    @DisplayName("create() rejects a category that does not belong to the selected class")
    void create_categoryDoesNotBelongToClass_throws() {
        ChartOfAccountRequest request = baseRequest("2", false, null);
        request.setAccountClass(AccountClass.LIABILITY);
        request.setAccountCategory(AccountCategory.CURRENT_ASSET); // belongs to ASSET, not LIABILITY

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("does not belong to the chosen account class");
    }

    @Test
    @DisplayName("create() rejects a duplicate account code within the same company")
    void create_duplicateCode_throws() {
        when(repository.existsByCompanyIdAndCode(COMPANY_ID, "1")).thenReturn(true);

        ChartOfAccountRequest request = baseRequest("1", false, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(DuplicateResourceException.class);
    }

    // ============================================================
    // Update rules — code is immutable, children block posting-account
    // conversion, self/circular parent references are rejected
    // ============================================================

    @Test
    @DisplayName("update() rejects any attempt to change the account code")
    void update_changingCode_throws() {
        ChartOfAccounts existing = existingAccount(5L, "1105", (byte) 3, false, AccountClass.ASSET, null);
        when(repository.findByIdAndCompany(5L, COMPANY_ID)).thenReturn(Optional.of(existing));

        ChartOfAccountRequest request = baseRequest("1106", false, null);

        assertThatThrownBy(() -> service.update(5L, request))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("cannot be modified");
    }

    @Test
    @DisplayName("update() rejects converting to a posting account when the account already has children")
    void update_convertToPostingAccountWithChildren_throws() {
        ChartOfAccounts existing = existingAccount(5L, "1105", (byte) 3, false, AccountClass.ASSET, null);
        when(repository.findByIdAndCompany(5L, COMPANY_ID)).thenReturn(Optional.of(existing));
        when(repository.existsByParent(existing)).thenReturn(true);

        ChartOfAccountRequest request = baseRequest("1105", true, null); // same code, now posting=true

        assertThatThrownBy(() -> service.update(5L, request))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("has child accounts");
    }

    @Test
    @DisplayName("update() rejects removing posting-account status when the account already has journal entry movements (REGRESSION GUARD)")
    void update_removePostingAccountWithMovements_throws() {
        // User-reported (2026-09-07): unchecking "Cuenta de Movimiento" on
        // account 110505 -- which already has journal entries posted to it
        // -- silently succeeded, even though every reporting query
        // (getTrialBalance, getAccountsForBalanceSheet, ...) only ever
        // looks at postingAccount = true leaves. This would have made its
        // historical movements vanish from every report without touching
        // the underlying entries. existsByCompanyIdAndAccount already
        // existed in the repository for exactly this check but nothing
        // called it.
        ChartOfAccounts existing = existingAccount(5L, "110505", (byte) 3, true, AccountClass.ASSET, null);
        when(repository.findByIdAndCompany(5L, COMPANY_ID)).thenReturn(Optional.of(existing));
        when(repository.existsByCompanyIdAndAccount(COMPANY_ID, existing)).thenReturn(true);

        ChartOfAccountRequest request = baseRequest("110505", false, null); // same code, now posting=false

        assertThatThrownBy(() -> service.update(5L, request))
                .isInstanceOfSatisfying(InvalidOperationException.class, ex -> {
                    assertThat(ex.getMessage()).contains("journal entry movements");
                    assertThat(ex.getErrorCode()).isEqualTo("POSTING_ACCOUNT_HAS_MOVEMENTS");
                });
    }

    @Test
    @DisplayName("update() allows removing posting-account status when the account has no journal entry movements")
    void update_removePostingAccountWithoutMovements_succeeds() {
        // Needs a real parent in the hierarchy ("1105", 4 digits) so
        // validateCodeStructure sees "110505" (6 digits) as a valid +2
        // digit jump instead of being treated as a (invalid) root code --
        // request.parentId is left null here (no parent change requested),
        // so the service falls back to existing.getParent() for that check.
        ChartOfAccounts parent = existingAccount(2L, "1105", (byte) 2, false, AccountClass.ASSET, null);
        ChartOfAccounts existing = existingAccount(5L, "110505", (byte) 3, true, AccountClass.ASSET, parent);
        when(repository.findByIdAndCompany(5L, COMPANY_ID)).thenReturn(Optional.of(existing));
        when(repository.existsByCompanyIdAndAccount(COMPANY_ID, existing)).thenReturn(false);
        when(repository.save(any(ChartOfAccounts.class))).thenAnswer(inv -> inv.getArgument(0));

        ChartOfAccountRequest request = baseRequest("110505", false, null); // same code, now posting=false

        ChartOfAccountResponseDTO result = service.update(5L, request);

        assertThat(result.isPostingAccount()).isFalse();
    }

    @Test
    @DisplayName("update() rejects setting an account as its own parent")
    void update_selfAsParent_throws() {
        ChartOfAccounts existing = existingAccount(5L, "1105", (byte) 3, false, AccountClass.ASSET, null);
        when(repository.findByIdAndCompany(5L, COMPANY_ID)).thenReturn(Optional.of(existing));

        ChartOfAccountRequest request = baseRequest("1105", false, 5L); // parentId == own id

        assertThatThrownBy(() -> service.update(5L, request))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("cannot be its own parent");
    }

    @Test
    @DisplayName("update() rejects moving an account under one of its own descendants (circular reference)")
    void update_circularReference_throws() {
        ChartOfAccounts grandparent = existingAccount(1L, "1", (byte) 1, false, AccountClass.ASSET, null);
        ChartOfAccounts existing = existingAccount(2L, "11", (byte) 2, false, AccountClass.ASSET, grandparent);
        ChartOfAccounts descendant = existingAccount(3L, "1101", (byte) 3, false, AccountClass.ASSET, existing);

        when(repository.findByIdAndCompany(2L, COMPANY_ID)).thenReturn(Optional.of(existing));
        // Attempt to move "existing" (id=2) under its own descendant (id=3)
        when(repository.findByIdAndCompany(3L, COMPANY_ID)).thenReturn(Optional.of(descendant));

        ChartOfAccountRequest request = baseRequest("11", false, 3L);

        assertThatThrownBy(() -> service.update(2L, request))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("Circular reference");
    }

    // ============================================================
    // Activate — checklist: "no se puede activar si el padre esta
    // inactiva"
    // ============================================================

    @Test
    @DisplayName("activate() refuses to activate an account whose parent is inactive")
    void activate_parentInactive_throws() {
        ChartOfAccounts parent = existingAccount(1L, "1", (byte) 1, false, AccountClass.ASSET, null);
        parent.setActive(false);
        ChartOfAccounts child = existingAccount(2L, "11", (byte) 2, false, AccountClass.ASSET, parent);
        child.setActive(false);

        when(repository.findByIdAndCompany(2L, COMPANY_ID)).thenReturn(Optional.of(child));

        assertThatThrownBy(() -> service.activate(2L))
                .isInstanceOfSatisfying(InvalidOperationException.class, ex -> {
                    assertThat(ex.getMessage()).contains("parent account");
                    assertThat(ex.getErrorCode()).isEqualTo("PARENT_INACTIVE_CANNOT_ACTIVATE");
                });
    }

    @Test
    @DisplayName("activate() succeeds when the parent is active")
    void activate_parentActive_succeeds() {
        ChartOfAccounts parent = existingAccount(1L, "1", (byte) 1, false, AccountClass.ASSET, null);
        ChartOfAccounts child = existingAccount(2L, "11", (byte) 2, false, AccountClass.ASSET, parent);
        child.setActive(false);

        when(repository.findByIdAndCompany(2L, COMPANY_ID)).thenReturn(Optional.of(child));

        service.activate(2L);

        assertThat(child.isActive()).isTrue();
    }

    @Test
    @DisplayName("findById() throws ResourceNotFoundException for an account outside the current tenant")
    void findById_unknownAccount_throwsResourceNotFound() {
        when(repository.findByIdAndCompany(999L, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
