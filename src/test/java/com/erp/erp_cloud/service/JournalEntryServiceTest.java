package com.erp.erp_cloud.service;

import com.erp.erp_cloud.dto.JournalEntryRequest;
import com.erp.erp_cloud.dto.JournalEntryResponseDTO;
import com.erp.erp_cloud.entity.*;
import com.erp.erp_cloud.exception.InvalidOperationException;
import com.erp.erp_cloud.exception.ResourceNotFoundException;
import com.erp.erp_cloud.repository.ChartOfAccountsRepository;
import com.erp.erp_cloud.repository.CompanyRepository;
import com.erp.erp_cloud.repository.CostCenterRepository;
import com.erp.erp_cloud.repository.JournalEntryRepository;
import com.erp.erp_cloud.repository.ThirdPartyRepository;
import com.erp.erp_cloud.security.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests for JournalEntryService -- no Spring context, no
 * database. This is the core of the double-entry accounting engine, so
 * coverage focuses on the highest-risk business rules: balance (debit ==
 * credit, zero tolerance), the date/period gate, one-sided line items,
 * account eligibility (active + posting-only), and the third
 * party/cost-center requirements driven by the account's own flags.
 * No manual QA checklist section exists for this module (it postdates
 * checklist-pruebas-erp.md), so this is derived straight from the code.
 *
 * Runs via `./gradlew test` (no live MySQL needed).
 */
class JournalEntryServiceTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long DOC_TYPE_ID = 10L;
    private static final Long DEBIT_ACCOUNT_ID = 100L;
    private static final Long CREDIT_ACCOUNT_ID = 200L;
    private static final Long THIRD_PARTY_ID = 300L;
    private static final Long COST_CENTER_ID = 400L;

    @Mock private JournalEntryRepository repository;
    @Mock private ChartOfAccountsRepository accountRepository;
    @Mock private ThirdPartyRepository thirdPartyRepository;
    @Mock private CostCenterRepository costCenterRepository;
    @Mock private DocumentTypeService docTypeService;
    @Mock private AccountingPeriodService accountingPeriodService;
    @Mock private CompanyRepository companyRepository;

    private JournalEntryService service;
    private Company testCompany;
    private DocumentType testDocType;
    private ChartOfAccounts debitAccount;
    private ChartOfAccounts creditAccount;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        TenantContext.setCurrentTenant(COMPANY_ID);

        service = new JournalEntryService(repository, accountRepository, thirdPartyRepository,
                costCenterRepository, docTypeService, accountingPeriodService, companyRepository);

        testCompany = new Company();
        testCompany.setId(COMPANY_ID);
        when(companyRepository.getReferenceById(COMPANY_ID)).thenReturn(testCompany);

        testDocType = new DocumentType();
        testDocType.setId(DOC_TYPE_ID);
        testDocType.setCode("FV");
        testDocType.setPrefix("FV");
        testDocType.setCompany(testCompany);

        debitAccount = plainAccount(DEBIT_ACCOUNT_ID, "110505");
        creditAccount = plainAccount(CREDIT_ACCOUNT_ID, "220505");

        when(docTypeService.findById(DOC_TYPE_ID)).thenReturn(testDocType);
        when(docTypeService.getNextConsecutive(DOC_TYPE_ID)).thenReturn(1L);
        when(accountRepository.findById(DEBIT_ACCOUNT_ID)).thenReturn(Optional.of(debitAccount));
        when(accountRepository.findById(CREDIT_ACCOUNT_ID)).thenReturn(Optional.of(creditAccount));
        when(repository.save(any(JournalEntry.class))).thenAnswer(invocation -> {
            JournalEntry e = invocation.getArgument(0);
            if (e.getId() == null) e.setId(500L);
            return e;
        });
        // accountingPeriodService.validateDateIsOpen(...) is void -- Mockito's
        // default is a no-op, i.e. "period is open", unless a test stubs it
        // with doThrow(...) to simulate a closed period.
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ============================================================
    // Fixtures
    // ============================================================

    private ChartOfAccounts plainAccount(Long id, String code) {
        ChartOfAccounts a = new ChartOfAccounts();
        a.setId(id);
        a.setCode(code);
        a.setName("Account " + code);
        a.setCompany(testCompany);
        a.setActive(true);
        a.setPostingAccount(true);
        a.setRequiresThirdParty(false);
        a.setRequiresCostCenter(false);
        return a;
    }

    private JournalEntryRequest.ItemRequest item(Long accountId, BigDecimal debit, BigDecimal credit) {
        JournalEntryRequest.ItemRequest i = new JournalEntryRequest.ItemRequest();
        i.setAccountId(accountId);
        i.setDebit(debit);
        i.setCredit(credit);
        return i;
    }

    private JournalEntryRequest balancedRequest(LocalDate date) {
        JournalEntryRequest req = new JournalEntryRequest();
        req.setEntryDate(date);
        req.setDocumentTypeId(DOC_TYPE_ID);
        req.setDescription("Test entry");
        req.setItems(new ArrayList<>(List.of(
                item(DEBIT_ACCOUNT_ID, new BigDecimal("100.00"), BigDecimal.ZERO),
                item(CREDIT_ACCOUNT_ID, BigDecimal.ZERO, new BigDecimal("100.00"))
        )));
        return req;
    }

    private JournalEntry existingEntry(LocalDate date) {
        JournalEntry entry = new JournalEntry();
        entry.setId(999L);
        entry.setCompany(testCompany);
        entry.setDocumentType(testDocType);
        entry.setEntryDate(date);
        entry.setDescription("Original description");
        entry.setDocumentNumber("FV-1");
        entry.setConsecutive(1L);
        entry.setActive(true);
        entry.setAnnulled(false);

        JournalEntryItem debitItem = new JournalEntryItem();
        debitItem.setAccount(debitAccount);
        debitItem.setDebit(new BigDecimal("100.00"));
        debitItem.setCredit(BigDecimal.ZERO);
        entry.addItem(debitItem);

        JournalEntryItem creditItem = new JournalEntryItem();
        creditItem.setAccount(creditAccount);
        creditItem.setDebit(BigDecimal.ZERO);
        creditItem.setCredit(new BigDecimal("100.00"));
        entry.addItem(creditItem);

        return entry;
    }

    // ============================================================
    // create() -- date & period gate
    // ============================================================

    @Test
    @DisplayName("create() persists a balanced entry and generates PREFIX-consecutive as the document number")
    void create_success_basicBalancedEntry() {
        JournalEntryResponseDTO result = service.create(balancedRequest(LocalDate.now()));

        assertThat(result.getDocumentNumber()).isEqualTo("FV-1");
        assertThat(result.getItems()).hasSize(2);
        verify(accountingPeriodService).validateDateIsOpen(any(LocalDate.class), org.mockito.ArgumentMatchers.eq(COMPANY_ID));
    }

    @Test
    @DisplayName("create() always binds the entry header to the current tenant's company")
    void create_bindsCompany_regressionGuardForNullCompanyIdBug() {
        // REGRESSION GUARD: createEntryHeader() once left entry.setCompany(...)
        // as a comment only, so every save hit the DB's NOT NULL constraint on
        // company_id -- surfacing as a "required field missing" error with no
        // field visibly missing on the form. Same class of bug fixed earlier
        // this session in TaxService/ThirdPartyService.
        service.create(balancedRequest(LocalDate.now()));

        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getCompany()).isEqualTo(testCompany);
        assertThat(captor.getValue().getCompany().getId()).isEqualTo(COMPANY_ID);
    }

    @Test
    @DisplayName("create() response exposes the real accountId/thirdPartyId/costCenterId/documentTypeId, not just display strings (REGRESSION GUARD)")
    void create_response_exposesRealIdsForEditing() {
        // BUG FIX GUARD: the response DTO used to carry only display
        // strings (accountCode/accountName, thirdPartyIdNumber/Name,
        // costCenterName) -- enough to *show* an entry but not enough for
        // a frontend edit form to know which option to pre-select in each
        // picker. Also covers documentTypeId at the header level.
        debitAccount.setRequiresThirdParty(true);
        debitAccount.setRequiresCostCenter(true);

        ThirdParty tp = new ThirdParty();
        tp.setId(THIRD_PARTY_ID);
        tp.setCompany(testCompany);
        tp.setActive(true);
        tp.setDocumentNumber("900373115");
        when(thirdPartyRepository.findById(THIRD_PARTY_ID)).thenReturn(Optional.of(tp));

        CostCenter cc = new CostCenter();
        cc.setId(COST_CENTER_ID);
        cc.setCompany(testCompany);
        cc.setActive(true);
        cc.setAllowsMovement(true);
        when(costCenterRepository.findById(COST_CENTER_ID)).thenReturn(Optional.of(cc));

        JournalEntryRequest request = balancedRequest(LocalDate.now());
        request.getItems().get(0).setThirdPartyId(THIRD_PARTY_ID);
        request.getItems().get(0).setCostCenterId(COST_CENTER_ID);

        JournalEntryResponseDTO result = service.create(request);

        assertThat(result.getDocumentTypeId()).isEqualTo(DOC_TYPE_ID);
        assertThat(result.getItems().get(0).getAccountId()).isEqualTo(DEBIT_ACCOUNT_ID);
        assertThat(result.getItems().get(0).getThirdPartyId()).isEqualTo(THIRD_PARTY_ID);
        assertThat(result.getItems().get(0).getCostCenterId()).isEqualTo(COST_CENTER_ID);
        assertThat(result.getItems().get(1).getAccountId()).isEqualTo(CREDIT_ACCOUNT_ID);
    }

    @Test
    @DisplayName("create() rejects a null entry date")
    void create_nullEntryDate_throws() {
        assertThatThrownBy(() -> service.create(balancedRequest(null)))
                .isInstanceOf(InvalidOperationException.class);
    }

    @Test
    @DisplayName("create() rejects a future entry date")
    void create_futureEntryDate_throws() {
        assertThatThrownBy(() -> service.create(balancedRequest(LocalDate.now().plusDays(1))))
                .isInstanceOf(InvalidOperationException.class);
    }

    @Test
    @DisplayName("create() rejects an entry date older than 90 days")
    void create_entryDateOlderThan90Days_throws() {
        assertThatThrownBy(() -> service.create(balancedRequest(LocalDate.now().minusDays(91))))
                .isInstanceOf(InvalidOperationException.class);
    }

    @Test
    @DisplayName("create() allows an entry date exactly 90 days back (inclusive boundary)")
    void create_entryDateExactly90DaysBack_succeeds() {
        JournalEntryResponseDTO result = service.create(balancedRequest(LocalDate.now().minusDays(90)));

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("create() propagates a closed-period rejection from AccountingPeriodService")
    void create_periodClosed_propagates() {
        doThrow(new InvalidOperationException("The period is CLOSED."))
                .when(accountingPeriodService).validateDateIsOpen(any(LocalDate.class), any(Long.class));

        assertThatThrownBy(() -> service.create(balancedRequest(LocalDate.now())))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("CLOSED");
    }

    // ============================================================
    // create() -- items & balance
    // ============================================================

    @Test
    @DisplayName("create() rejects an entry with no items")
    void create_noItems_throws() {
        JournalEntryRequest request = balancedRequest(LocalDate.now());
        request.setItems(new ArrayList<>());

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(InvalidOperationException.class);
    }

    @Test
    @DisplayName("create() rejects an unbalanced entry (debits != credits, zero tolerance)")
    void create_unbalancedEntry_throws() {
        JournalEntryRequest request = balancedRequest(LocalDate.now());
        request.getItems().get(1).setCredit(new BigDecimal("99.99")); // off by a cent

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("unbalanced");
    }

    @Test
    @DisplayName("create() rejects a line item carrying both a debit and a credit")
    void create_itemWithBothDebitAndCredit_throws() {
        JournalEntryRequest request = balancedRequest(LocalDate.now());
        // Keep the GLOBAL total balanced (debit sum == credit sum) so the
        // per-item structural check -- not the balance check -- is what
        // rejects this request. Item #1 (index 0) is invalid on its own:
        // it carries both a debit and a credit.
        request.getItems().get(0).setDebit(new BigDecimal("50.00"));
        request.getItems().get(0).setCredit(new BigDecimal("50.00"));
        request.getItems().get(1).setDebit(new BigDecimal("50.00"));
        request.getItems().get(1).setCredit(new BigDecimal("50.00"));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("cannot have both");
    }

    @Test
    @DisplayName("create() rejects a line item with neither a debit nor a credit")
    void create_itemWithNeitherDebitNorCredit_throws() {
        JournalEntryRequest request = balancedRequest(LocalDate.now());
        // The first two items stay valid and balanced (100 debit / 100
        // credit); a harmless third 0/0 item is appended so the GLOBAL
        // balance check still passes and the per-item loop is what
        // rejects it, once it reaches this item.
        request.getItems().add(item(DEBIT_ACCOUNT_ID, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("must have either debit or credit");
    }

    @Test
    @DisplayName("create() scales amounts to 2 decimals with HALF_UP rounding")
    void create_scalesAmounts_toTwoDecimalsHalfUp() {
        JournalEntryRequest request = balancedRequest(LocalDate.now());
        request.getItems().get(0).setDebit(new BigDecimal("100.005")); // rounds to 100.01
        request.getItems().get(1).setCredit(new BigDecimal("100.005"));

        JournalEntryResponseDTO result = service.create(request);

        assertThat(result.getItems().get(0).getDebit()).isEqualByComparingTo("100.01");
    }

    // ============================================================
    // create() -- document type & document number
    // ============================================================

    @Test
    @DisplayName("create() rejects a document type that belongs to a different company")
    void create_documentTypeFromDifferentCompany_throws() {
        Company otherCompany = new Company();
        otherCompany.setId(999L);
        testDocType.setCompany(otherCompany);

        assertThatThrownBy(() -> service.create(balancedRequest(LocalDate.now())))
                .isInstanceOf(InvalidOperationException.class);
    }

    @Test
    @DisplayName("create() rejects a duplicate document number as a concurrency guard")
    void create_duplicateDocumentNumber_throws() {
        when(repository.existsByCompanyIdAndDocumentNumber(COMPANY_ID, "FV-1")).thenReturn(true);

        assertThatThrownBy(() -> service.create(balancedRequest(LocalDate.now())))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("create() generates a bare consecutive as the document number when the document type has no prefix")
    void create_documentNumber_withoutPrefix() {
        testDocType.setPrefix(null);

        JournalEntryResponseDTO result = service.create(balancedRequest(LocalDate.now()));

        assertThat(result.getDocumentNumber()).isEqualTo("1");
    }

    // ============================================================
    // create() -- account eligibility
    // ============================================================

    @Test
    @DisplayName("create() rejects a non-existent account")
    void create_accountNotFound_throwsResourceNotFound() {
        when(accountRepository.findById(DEBIT_ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(balancedRequest(LocalDate.now())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("create() rejects an inactive account")
    void create_accountInactive_throws() {
        debitAccount.setActive(false);

        assertThatThrownBy(() -> service.create(balancedRequest(LocalDate.now())))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("inactive");
    }

    @Test
    @DisplayName("create() rejects a non-posting (summary) account")
    void create_accountNotPosting_throws() {
        debitAccount.setPostingAccount(false);

        assertThatThrownBy(() -> service.create(balancedRequest(LocalDate.now())))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("not a posting account");
    }

    // ============================================================
    // create() -- third party requirement
    // ============================================================

    @Test
    @DisplayName("create() requires a third party when the account demands one")
    void create_accountRequiresThirdParty_missingId_throws() {
        debitAccount.setRequiresThirdParty(true);

        assertThatThrownBy(() -> service.create(balancedRequest(LocalDate.now())))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("requires a Third Party");
    }

    @Test
    @DisplayName("create() binds a valid, active third party when the account requires one")
    void create_accountRequiresThirdParty_valid_setsThirdParty() {
        debitAccount.setRequiresThirdParty(true);
        ThirdParty tp = new ThirdParty();
        tp.setId(THIRD_PARTY_ID);
        tp.setCompany(testCompany);
        tp.setActive(true);
        tp.setDocumentNumber("900373115");
        when(thirdPartyRepository.findById(THIRD_PARTY_ID)).thenReturn(Optional.of(tp));

        JournalEntryRequest request = balancedRequest(LocalDate.now());
        request.getItems().get(0).setThirdPartyId(THIRD_PARTY_ID);

        JournalEntryResponseDTO result = service.create(request);

        assertThat(result.getItems().get(0).getThirdPartyIdNumber()).isEqualTo("900373115");
    }

    @Test
    @DisplayName("create() rejects an inactive third party")
    void create_accountRequiresThirdParty_inactiveThirdParty_throws() {
        debitAccount.setRequiresThirdParty(true);
        ThirdParty tp = new ThirdParty();
        tp.setId(THIRD_PARTY_ID);
        tp.setCompany(testCompany);
        tp.setActive(false);
        when(thirdPartyRepository.findById(THIRD_PARTY_ID)).thenReturn(Optional.of(tp));

        JournalEntryRequest request = balancedRequest(LocalDate.now());
        request.getItems().get(0).setThirdPartyId(THIRD_PARTY_ID);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("inactive");
    }

    // ============================================================
    // create() -- cost center requirement
    // ============================================================

    @Test
    @DisplayName("create() requires a cost center when the account demands one")
    void create_accountRequiresCostCenter_missingId_throws() {
        debitAccount.setRequiresCostCenter(true);

        assertThatThrownBy(() -> service.create(balancedRequest(LocalDate.now())))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("requires a Cost Center");
    }

    @Test
    @DisplayName("create() binds a valid, active cost center when the account requires one")
    void create_accountRequiresCostCenter_valid_setsCostCenter() {
        debitAccount.setRequiresCostCenter(true);
        CostCenter cc = new CostCenter();
        cc.setId(COST_CENTER_ID);
        cc.setCompany(testCompany);
        cc.setActive(true);
        cc.setName("Administration");
        when(costCenterRepository.findById(COST_CENTER_ID)).thenReturn(Optional.of(cc));

        JournalEntryRequest request = balancedRequest(LocalDate.now());
        request.getItems().get(0).setCostCenterId(COST_CENTER_ID);

        JournalEntryResponseDTO result = service.create(request);

        assertThat(result.getItems().get(0).getCostCenterName()).isEqualTo("Administration");
    }

    @Test
    @DisplayName("create() rejects an inactive cost center")
    void create_accountRequiresCostCenter_inactiveCostCenter_throws() {
        debitAccount.setRequiresCostCenter(true);
        CostCenter cc = new CostCenter();
        cc.setId(COST_CENTER_ID);
        cc.setCompany(testCompany);
        cc.setActive(false);
        when(costCenterRepository.findById(COST_CENTER_ID)).thenReturn(Optional.of(cc));

        JournalEntryRequest request = balancedRequest(LocalDate.now());
        request.getItems().get(0).setCostCenterId(COST_CENTER_ID);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("inactive");
    }

    // ============================================================
    // annul()
    // ============================================================

    private JournalEntryRequest.AnnulmentRequest annulmentRequest(String reason) {
        JournalEntryRequest.AnnulmentRequest req = new JournalEntryRequest.AnnulmentRequest();
        req.setReason(reason);
        return req;
    }

    @Test
    @DisplayName("annul() zeroes out every item's debit/credit and prefixes the description")
    void annul_success_neutralizesAndPrefixes() {
        JournalEntry entry = existingEntry(LocalDate.now());
        when(repository.findById(999L)).thenReturn(Optional.of(entry));

        JournalEntryResponseDTO result = service.annul(999L, annulmentRequest("Posted in error"));

        assertThat(entry.isAnnulled()).isTrue();
        assertThat(entry.getAnnulmentReason()).isEqualTo("Posted in error");
        assertThat(entry.getDescription()).startsWith("[ANNULLED] ");
        entry.getItems().forEach(i -> {
            assertThat(i.getDebit()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(i.getCredit()).isEqualByComparingTo(BigDecimal.ZERO);
        });
        // Regression guard: the response DTO must actually reflect the
        // annulment (previously mapToResponseDTO never copied these three
        // fields, so every response silently reported annulled=false).
        assertThat(result.isAnnulled()).isTrue();
        assertThat(result.getAnnulmentReason()).isEqualTo("Posted in error");
        assertThat(result.getAnnulledAt()).isNotNull();
    }

    @Test
    @DisplayName("annul() does not double-prefix a description that's already marked annulled")
    void annul_descriptionAlreadyPrefixed_doesNotDoublePrefix() {
        JournalEntry entry = existingEntry(LocalDate.now());
        entry.setDescription("[ANNULLED] Original description");
        when(repository.findById(999L)).thenReturn(Optional.of(entry));

        service.annul(999L, annulmentRequest("x"));

        assertThat(entry.getDescription()).isEqualTo("[ANNULLED] Original description");
    }

    @Test
    @DisplayName("annul() rejects an entry that is already annulled")
    void annul_alreadyAnnulled_throws() {
        JournalEntry entry = existingEntry(LocalDate.now());
        entry.setAnnulled(true);
        when(repository.findById(999L)).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> service.annul(999L, annulmentRequest("x")))
                .isInstanceOfSatisfying(InvalidOperationException.class, ex -> {
                    assertThat(ex.getMessage()).contains("already annulled");
                    assertThat(ex.getErrorCode()).isEqualTo("ENTRY_ALREADY_ANNULLED");
                });
    }

    @Test
    @DisplayName("annul() rejects an inactive (deleted) entry")
    void annul_inactiveEntry_throws() {
        JournalEntry entry = existingEntry(LocalDate.now());
        entry.setActive(false);
        when(repository.findById(999L)).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> service.annul(999L, annulmentRequest("x")))
                .isInstanceOfSatisfying(InvalidOperationException.class, ex -> {
                    assertThat(ex.getMessage()).contains("inactive");
                    assertThat(ex.getErrorCode()).isEqualTo("ENTRY_INACTIVE_IMMUTABLE");
                });
    }

    @Test
    @DisplayName("annul() rejects an entry id that doesn't exist")
    void annul_notFound_throwsResourceNotFound() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.annul(999L, annulmentRequest("x")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("annul() rejects an entry belonging to a different company")
    void annul_crossTenant_throwsResourceNotFound() {
        JournalEntry entry = existingEntry(LocalDate.now());
        Company otherCompany = new Company();
        otherCompany.setId(999L);
        entry.setCompany(otherCompany);
        when(repository.findById(999L)).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> service.annul(999L, annulmentRequest("x")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("annul() propagates a closed-period rejection for the entry's own date")
    void annul_periodClosed_propagates() {
        JournalEntry entry = existingEntry(LocalDate.now());
        when(repository.findById(999L)).thenReturn(Optional.of(entry));
        doThrow(new InvalidOperationException("The period is CLOSED."))
                .when(accountingPeriodService).validateDateIsOpen(entry.getEntryDate(), COMPANY_ID);

        assertThatThrownBy(() -> service.annul(999L, annulmentRequest("x")))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("CLOSED");
    }

    // ============================================================
    // update()
    // ============================================================

    @Test
    @DisplayName("update() replaces the line items and header fields")
    void update_success_replacesItems() {
        JournalEntry entry = existingEntry(LocalDate.now());
        when(repository.findById(999L)).thenReturn(Optional.of(entry));

        JournalEntryRequest request = balancedRequest(entry.getEntryDate());
        request.setDescription("Corrected description");
        request.getItems().get(0).setDebit(new BigDecimal("250.00"));
        request.getItems().get(1).setCredit(new BigDecimal("250.00"));

        JournalEntryResponseDTO result = service.update(999L, request);

        assertThat(result.getDescription()).isEqualTo("Corrected description");
        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems().get(0).getDebit()).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("update() rejects editing an annulled entry (REGRESSION GUARD)")
    void update_annulledEntry_throws() {
        // BUG FIX GUARD: update() previously had no check for isAnnulled() at
        // all, so editing an annulled entry would silently overwrite the
        // zeroed items annul() set, undoing its neutralization guarantee.
        JournalEntry entry = existingEntry(LocalDate.now());
        entry.setAnnulled(true);
        when(repository.findById(999L)).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> service.update(999L, balancedRequest(entry.getEntryDate())))
                .isInstanceOfSatisfying(InvalidOperationException.class, ex -> {
                    assertThat(ex.getMessage()).contains("annulled");
                    assertThat(ex.getErrorCode()).isEqualTo("ENTRY_ANNULLED_IMMUTABLE");
                });
    }

    @Test
    @DisplayName("update() rejects editing an inactive entry (REGRESSION GUARD)")
    void update_inactiveEntry_throws() {
        JournalEntry entry = existingEntry(LocalDate.now());
        entry.setActive(false);
        when(repository.findById(999L)).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> service.update(999L, balancedRequest(entry.getEntryDate())))
                .isInstanceOfSatisfying(InvalidOperationException.class, ex -> {
                    assertThat(ex.getMessage()).contains("inactive");
                    assertThat(ex.getErrorCode()).isEqualTo("ENTRY_INACTIVE_IMMUTABLE");
                });
    }

    @Test
    @DisplayName("update() rejects an id that doesn't exist")
    void update_notFound_throwsResourceNotFound() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(999L, balancedRequest(LocalDate.now())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("update() rejects an unbalanced replacement request")
    void update_unbalanced_throws() {
        JournalEntry entry = existingEntry(LocalDate.now());
        when(repository.findById(999L)).thenReturn(Optional.of(entry));

        JournalEntryRequest request = balancedRequest(entry.getEntryDate());
        request.getItems().get(1).setCredit(new BigDecimal("50.00"));

        assertThatThrownBy(() -> service.update(999L, request))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("unbalanced");
    }

    @Test
    @DisplayName("update() re-validates date range and period only when the entry date actually changes")
    void update_dateChanged_validatesNewDate() {
        JournalEntry entry = existingEntry(LocalDate.now());
        when(repository.findById(999L)).thenReturn(Optional.of(entry));

        JournalEntryRequest request = balancedRequest(LocalDate.now().plusDays(1)); // future date

        assertThatThrownBy(() -> service.update(999L, request))
                .isInstanceOf(InvalidOperationException.class);
    }

    @Test
    @DisplayName("update() does not re-validate the 90-day/future window when the entry date is unchanged, even if that date is now older than 90 days")
    void update_dateUnchanged_skipsAgeValidation() {
        LocalDate oldDate = LocalDate.now().minusDays(200); // would fail validateEntryDate() if re-checked
        JournalEntry entry = existingEntry(oldDate);
        when(repository.findById(999L)).thenReturn(Optional.of(entry));

        JournalEntryRequest request = balancedRequest(oldDate); // same date, unchanged

        JournalEntryResponseDTO result = service.update(999L, request);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("update() rejects editing an entry whose own (existing) period is closed, even if the date is unchanged")
    void update_existingPeriodClosed_throws() {
        JournalEntry entry = existingEntry(LocalDate.now());
        when(repository.findById(999L)).thenReturn(Optional.of(entry));
        doThrow(new InvalidOperationException("The period is CLOSED."))
                .when(accountingPeriodService).validateDateIsOpen(entry.getEntryDate(), COMPANY_ID);

        assertThatThrownBy(() -> service.update(999L, balancedRequest(entry.getEntryDate())))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("CLOSED");
    }

    // ============================================================
    // Trial Balance reports -- regression coverage for a bug where
    // companyName was never populated (builder simply omitted it) and
    // generatedAt was a bare LocalDate, so exported PDF/Excel headers
    // (which need company name + generation date AND time) had no
    // company to show and no time component.
    // ============================================================

    @Test
    @DisplayName("getTrialBalanceReport() populates companyName and a generatedAt with a time component")
    void getTrialBalanceReport_populatesCompanyNameAndGeneratedAt() {
        testCompany.setLegalName("ERP Demo Company S.A.S.");
        when(accountRepository.getTrialBalance(eq(COMPANY_ID), any(LocalDate.class)))
                .thenReturn(new ArrayList<>());

        var report = service.getTrialBalanceReport(LocalDate.now());

        assertThat(report.getCompanyName()).isEqualTo("ERP Demo Company S.A.S.");
        assertThat(report.getGeneratedAt()).isNotNull();
    }

    @Test
    @DisplayName("getTrialBalanceDetailed() populates companyName and a generatedAt with a time component")
    void getTrialBalanceDetailed_populatesCompanyNameAndGeneratedAt() {
        testCompany.setLegalName("ERP Demo Company S.A.S.");
        LocalDate start = LocalDate.now().minusDays(30);
        LocalDate end = LocalDate.now();
        when(accountRepository.getOpeningBalances(eq(COMPANY_ID), any(LocalDate.class)))
                .thenReturn(new ArrayList<>());
        when(accountRepository.getPeriodActivity(eq(COMPANY_ID), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(new ArrayList<>());

        var report = service.getTrialBalanceDetailed(start, end);

        assertThat(report.getCompanyName()).isEqualTo("ERP Demo Company S.A.S.");
        assertThat(report.getGeneratedAt()).isNotNull();
    }
}
