package com.erp.erp_cloud.service;

import com.erp.erp_cloud.dto.*;
import com.erp.erp_cloud.dto.reports.financial.TrialBalanceLineDetailed;
import com.erp.erp_cloud.dto.reports.financial.TrialBalanceReportDetailed;
import com.erp.erp_cloud.entity.*;
import com.erp.erp_cloud.enums.AccountClass;
import com.erp.erp_cloud.exception.InvalidOperationException;
import com.erp.erp_cloud.exception.ResourceNotFoundException;
import com.erp.erp_cloud.repository.JournalEntryRepository;
import com.erp.erp_cloud.repository.CompanyRepository;
import com.erp.erp_cloud.repository.ChartOfAccountsRepository;
import com.erp.erp_cloud.repository.CostCenterRepository;
import com.erp.erp_cloud.repository.ThirdPartyRepository;
import com.erp.erp_cloud.dto.TrialBalanceLine;
import com.erp.erp_cloud.dto.reports.financial.TrialBalanceReport;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.erp.erp_cloud.service.base.TenantAwareService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.math.BigDecimal;
import java.util.*;

/**
 * Service for managing Journal Entries (Comprobantes Contables).
 *
 * Current Version: Manual Entry Only
 * - No automatic tax calculation
 * - No automatic balancing
 * - Full accountant control over all entries
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JournalEntryService extends TenantAwareService {

    private static final Logger log = LoggerFactory.getLogger(JournalEntryService.class);

    private final JournalEntryRepository repository;
    private final ChartOfAccountsRepository accountRepository;
    private final ThirdPartyRepository thirdPartyRepository;
    private final CostCenterRepository costCenterRepository;
    private final DocumentTypeService docTypeService;
    private final AccountingPeriodService accountingPeriodService;
    private final CompanyRepository companyRepository;

    // ═══════════════════════════════════════════════════════════
    // CREATE OPERATIONS
    // ═══════════════════════════════════════════════════════════

    /**
     * Creates a new journal entry with strict manual validation.
     */
    @Transactional
    public JournalEntryResponseDTO create(JournalEntryRequest request) {
        Long companyId = currentTenantId();
        log.info("Creating journal entry for company ID: {}", companyId);

        // 1. Date and Period Validation
        validateEntryDate(request.getEntryDate());
        accountingPeriodService.validateDateIsOpen(request.getEntryDate(), companyId);


        // 2. Items Existence Validation
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new InvalidOperationException("Journal entry must contain at least one item.");
        }

        // 3. CRITICAL: Balance Validation (Zero Tolerance)
        validateBalance(request);

        // 4. Document Type Setup and Validation
        DocumentType docType = validateAndGetDocumentType(request.getDocumentTypeId(), companyId);

        // 5. Create Entry Header
        JournalEntry entry = createEntryHeader(docType, request, companyId);

        // 6. Process Line Items
        processLineItems(entry, request, companyId);

        // 7. Save and Return
        log.info("Saving journal entry {} with {} items for company ID {}",
                entry.getDocumentNumber(), entry.getItems().size(), companyId);

        return mapToResponseDTO(repository.save(entry));
    }

    // ═══════════════════════════════════════════════════════════
    // PRIVATE CREATION HELPERS
    // ═══════════════════════════════════════════════════════════

    /**
     * Validates that total debits equal total credits.
     */
    private void validateBalance(JournalEntryRequest request) {
        BigDecimal totalDebit = request.getItems().stream()
                .map(i -> i.getDebit() != null ? i.getDebit() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredit = request.getItems().stream()
                .map(i -> i.getCredit() != null ? i.getCredit() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalDebit.compareTo(totalCredit) != 0) {
            BigDecimal difference = totalDebit.subtract(totalCredit);
            log.warn("Unbalanced entry attempt: Debit={}, Credit={}, Diff={}",
                    totalDebit, totalCredit, difference);

            throw new InvalidOperationException(String.format(
                    "Journal entry is unbalanced. Debits: %s, Credits: %s, Difference: %s. " +
                            "Please ensure debits equal credits before submitting.",
                    totalDebit.setScale(2, RoundingMode.HALF_UP),
                    totalCredit.setScale(2, RoundingMode.HALF_UP),
                    difference.setScale(2, RoundingMode.HALF_UP)));
        }

        log.debug("Balance validation passed: Debits={}, Credits={}", totalDebit, totalCredit);
    }

    /**
     * Validates and retrieves the document type.
     */
    private DocumentType validateAndGetDocumentType(Long docTypeId, Long companyId) {
        DocumentType docType = docTypeService.findById(docTypeId);

        if (!docType.getCompany().getId().equals(companyId)) {
            throw new InvalidOperationException("This Document Type does not belong to the current company.");
        }

        return docType;
    }

    /**
     * Creates the journal entry header with document number assignment.
     */
    private JournalEntry createEntryHeader(DocumentType docType, JournalEntryRequest request, Long companyId) {
        JournalEntry entry = new JournalEntry();
        entry.setDocumentType(docType);
        entry.setEntryDate(request.getEntryDate());
        entry.setDescription(request.getDescription());

        // BUG FIX: company was never actually assigned here (only mentioned in a
        // comment), so every journal entry save hit the DB's NOT NULL constraint on
        // company_id and surfaced as a confusing "required field missing" error with
        // no field the user could see on the form. Same class of bug already fixed
        // this session in TaxService/ThirdPartyService.
        entry.setCompany(companyRepository.getReferenceById(companyId));

        // Get next consecutive number with pessimistic lock
        Long nextNumber = docTypeService.getNextConsecutive(docType.getId());
        entry.setConsecutive(nextNumber);
        entry.setDocumentNumber(generateDocNumber(docType, nextNumber));

        // Safety check for duplicate document numbers
        if (repository.existsByCompanyIdAndDocumentNumber(companyId, entry.getDocumentNumber())) {
            log.error("Duplicate document number detected: {}", entry.getDocumentNumber());
            throw new InvalidOperationException(
                    "Document number already exists. This may be a concurrency issue. Please retry.");
        }

        log.debug("Entry header created: DocType={}, DocNum={}, Date={}",
                docType.getCode(), entry.getDocumentNumber(), entry.getEntryDate());

        return entry;
    }

    /**
     * Processes and validates all line items.
     */
    private void processLineItems(JournalEntry entry, JournalEntryRequest request, Long companyId) {
        for (int i = 0; i < request.getItems().size(); i++) {
            var itemDto = request.getItems().get(i);
            final int itemIndex = i;

            // Validate amounts
            validateItemAmounts(itemDto.getDebit(), itemDto.getCredit(), itemIndex);

            // Fetch and validate account
            ChartOfAccounts account = fetchAndValidateAccount(itemDto.getAccountId(), companyId);

            // Create item
            JournalEntryItem item = new JournalEntryItem();
            item.setAccount(account);
            item.setDebit(scaleAmount(itemDto.getDebit()));
            item.setCredit(scaleAmount(itemDto.getCredit()));
            item.setDescription(itemDto.getDescription());

            // Handle optional fields based on account configuration
            handleThirdParty(item, itemDto, account, companyId);
            handleCostCenter(item, itemDto, account, companyId);

            entry.addItem(item);
        }

        log.debug("Processed {} line items", request.getItems().size());
    }

    private BigDecimal scaleAmount(BigDecimal amount) {
        return amount != null ? amount.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    private String generateDocNumber(DocumentType docType, Long consecutive) {
        return (docType.getPrefix() != null && !docType.getPrefix().isBlank())
                ? docType.getPrefix().trim() + "-" + consecutive
                : consecutive.toString();
    }

    // ═══════════════════════════════════════════════════════════
    // UPDATE & ANNUL OPERATIONS
    // ═══════════════════════════════════════════════════════════

    /**
     * Annuls a journal entry by neutralizing its financial impact.
     */
    @Transactional
    public JournalEntryResponseDTO annul(Long id, JournalEntryRequest.AnnulmentRequest annulRequest) {
        Long companyId = currentTenantId();

        // 1. Fetch and validate ownership
        JournalEntry entry = repository.findById(id)
                .filter(e -> e.getCompany().getId().equals(companyId))
                .orElseThrow(() -> new ResourceNotFoundException("Journal Entry", id));

        // 2. PERIOD VALIDATION
        accountingPeriodService.validateDateIsOpen(entry.getEntryDate(), companyId);

        // 3. STATE VALIDATION
        if (entry.isAnnulled()) {
            throw new InvalidOperationException(
                    "Journal entry is already annulled.", "ENTRY_ALREADY_ANNULLED");
        }

        if (!entry.isActive()) {
            throw new InvalidOperationException(
                    "Cannot annul an inactive (deleted) journal entry.", "ENTRY_INACTIVE_IMMUTABLE");
        }

        log.info("Annulling journal entry {} (ID: {})", entry.getDocumentNumber(), id);

        // 4. FINANCIAL NEUTRALIZATION
        entry.setAnnulled(true);
        entry.setAnnulledAt(java.time.LocalDateTime.now());
        entry.setAnnulmentReason(annulRequest.getReason());

        for (JournalEntryItem item : entry.getItems()) {
            item.setDebit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            item.setCredit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }

        // 5. AUDIT LOGGING
        String prefix = "[ANNULLED] ";
        if (!entry.getDescription().startsWith(prefix)) {
            entry.setDescription(prefix + entry.getDescription());
        }

        JournalEntry savedEntry = repository.save(entry);
        log.info("Journal entry {} successfully neutralized.", savedEntry.getDocumentNumber());

        return mapToResponseDTO(savedEntry);
    }

    /**
     * Updates an existing journal entry.
     */
    @Transactional
    public JournalEntryResponseDTO update(Long id, JournalEntryRequest request) {
        Long companyId = currentTenantId();

        // 1. Fetch existing entry and verify ownership
        JournalEntry existingEntry = repository.findById(id)
                .filter(e -> e.getCompany().getId().equals(companyId))
                .orElseThrow(() -> new ResourceNotFoundException("Journal Entry", id));

        // 2. STATE VALIDATION
        // BUG FIX: an annulled entry's financial effect is permanently
        // neutralized by annul() (items zeroed, description prefixed) --
        // editing it here would silently undo that neutralization and
        // corrupt the audit trail. Mirrors the guard annul() already
        // applies to itself.
        if (existingEntry.isAnnulled()) {
            throw new InvalidOperationException(
                    "Cannot edit an annulled journal entry.", "ENTRY_ANNULLED_IMMUTABLE");
        }
        if (!existingEntry.isActive()) {
            throw new InvalidOperationException(
                    "Cannot edit an inactive journal entry.", "ENTRY_INACTIVE_IMMUTABLE");
        }

        // 3. PERIOD VALIDATION
        accountingPeriodService.validateDateIsOpen(existingEntry.getEntryDate(), companyId);

        if (!existingEntry.getEntryDate().equals(request.getEntryDate())) {
            validateEntryDate(request.getEntryDate());
            accountingPeriodService.validateDateIsOpen(request.getEntryDate(), companyId);
        }

        // 4. FINANCIAL VALIDATION
        validateBalance(request);

        log.info("Updating journal entry {} (ID: {}) for company ID {}",
                existingEntry.getDocumentNumber(), id, companyId);

        // 5. HEADER UPDATE
        existingEntry.setEntryDate(request.getEntryDate());
        existingEntry.setDescription(request.getDescription());

        // 6. ITEMS UPDATE
        existingEntry.getItems().clear();
        processLineItems(existingEntry, request, companyId);

        JournalEntry saved = repository.save(existingEntry);
        log.info("Journal entry {} updated successfully", saved.getDocumentNumber());

        return mapToResponseDTO(saved);
    }

    // ═══════════════════════════════════════════════════════════
    // VALIDATION HELPERS
    // ═══════════════════════════════════════════════════════════

    private void validateEntryDate(LocalDate date) {
        if (date == null) {
            throw new InvalidOperationException("Entry date is required");
        }

        LocalDate today = LocalDate.now();
        if (date.isAfter(today)) {
            throw new InvalidOperationException("Entry date cannot be in the future");
        }

        LocalDate minAllowedDate = today.minusDays(90);
        if (date.isBefore(minAllowedDate)) {
            throw new InvalidOperationException(
                    String.format("Entry date cannot be older than %s (90 days back). " +
                            "For older dates, please contact system administrator.", minAllowedDate)
            );
        }

        log.debug("Entry date validated: {}", date);
    }

    private void validateItemAmounts(BigDecimal debit, BigDecimal credit, int itemIndex) {
        boolean hasDebit = debit != null && debit.compareTo(BigDecimal.ZERO) > 0;
        boolean hasCredit = credit != null && credit.compareTo(BigDecimal.ZERO) > 0;

        if (hasDebit && hasCredit) {
            throw new InvalidOperationException(
                    String.format("Item #%d cannot have both debit (%s) and credit (%s). " +
                                    "Each line must be either a debit OR a credit, not both.",
                            itemIndex + 1,
                            debit.setScale(2, RoundingMode.HALF_UP),
                            credit.setScale(2, RoundingMode.HALF_UP))
            );
        }

        if (!hasDebit && !hasCredit) {
            throw new InvalidOperationException(
                    String.format("Item #%d must have either debit or credit greater than zero.", itemIndex + 1)
            );
        }
    }

    private ChartOfAccounts fetchAndValidateAccount(Long accountId, Long companyId) {
        ChartOfAccounts account = accountRepository.findById(accountId)
                .filter(a -> a.getCompany().getId().equals(companyId))
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));

        if (!account.isActive()) {
            throw new InvalidOperationException(
                    String.format("Account %s - %s is inactive and cannot be used in journal entries.",
                            account.getCode(), account.getName())
            );
        }

        if (!account.isPostingAccount()) {
            throw new InvalidOperationException(
                    String.format("Account %s - %s is not a posting account. " +
                                    "Only auxiliary accounts (Level 4+) can be used in entries.",
                            account.getCode(), account.getName())
            );
        }

        return account;
    }

    private void handleThirdParty(JournalEntryItem item, JournalEntryRequest.ItemRequest dto, ChartOfAccounts account, Long companyId) {
        if (account.isRequiresThirdParty()) {
            if (dto.getThirdPartyId() == null) {
                throw new InvalidOperationException(
                        String.format("Account %s (%s) requires a Third Party to be specified.",
                                account.getCode(), account.getName())
                );
            }

            ThirdParty thirdParty = thirdPartyRepository.findById(dto.getThirdPartyId())
                    .filter(t -> t.getCompany().getId().equals(companyId))
                    .orElseThrow(() -> new ResourceNotFoundException("ThirdParty", dto.getThirdPartyId()));

            if (!thirdParty.getActive()) {
                throw new InvalidOperationException(
                        String.format("Third Party %s is inactive and cannot be used.", thirdParty.getLegalDisplayName())
                );
            }

            item.setThirdParty(thirdParty);
        }
    }

    private void handleCostCenter(JournalEntryItem item, JournalEntryRequest.ItemRequest dto, ChartOfAccounts account, Long companyId) {
        if (account.isRequiresCostCenter()) {
            if (dto.getCostCenterId() == null) {
                throw new InvalidOperationException(
                        String.format("Account %s (%s) requires a Cost Center to be specified.",
                                account.getCode(), account.getName())
                );
            }

            CostCenter costCenter = costCenterRepository.findById(dto.getCostCenterId())
                    .filter(c -> c.getCompany().getId().equals(companyId))
                    .orElseThrow(() -> new ResourceNotFoundException("CostCenter", dto.getCostCenterId()));

            if (!costCenter.isActive()) {
                throw new InvalidOperationException(
                        String.format("Cost Center %s is inactive and cannot be used.", costCenter.getName())
                );
            }

            item.setCostCenter(costCenter);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // READ OPERATIONS
    // ═══════════════════════════════════════════════════════════

    public Page<JournalEntryResponseDTO> listEntries(String searchTerm, LocalDate start, LocalDate end, Pageable pageable) {

        Long companyId = currentTenantId();
        log.debug("Listing journal entries for company ID: {}", companyId);

        return repository.searchEntries(companyId, searchTerm, start, end, pageable)
                .map(this::mapToResponseDTO);
    }

    public JournalEntryResponseDTO findByDocumentNumber(String docNum) {
        Long companyId = currentTenantId();
        return repository.findByCompanyIdAndDocumentNumber(companyId, docNum)
                .map(this::mapToResponseDTO)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Journal entry with document number '" + docNum + "' not found"
                ));
    }

    public JournalEntryResponseDTO findById(Long id) {
        Long companyId = currentTenantId();
        return repository.findById(id)
                .filter(entry -> entry.getCompany().getId().equals(companyId))
                .map(this::mapToResponseDTO)
                .orElseThrow(() -> new ResourceNotFoundException("Journal Entry", id));
    }
    // ═══════════════════════════════════════════════════════════
    // TRIAL BALANCE REPORTS
    // ═══════════════════════════════════════════════════════════

    public TrialBalanceReport getTrialBalanceReport(LocalDate asOfDate) {
        Long companyId = currentTenantId();
        if (asOfDate == null) {
            asOfDate = LocalDate.now();
        }

        log.info("Generating Trial Balance for company ID: {} as of {}", companyId, asOfDate);

        List<TrialBalanceLine> lines = accountRepository.getTrialBalance(companyId, asOfDate);

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (TrialBalanceLine line : lines) {
            totalDebit = totalDebit.add(line.getTotalDebit());
            totalCredit = totalCredit.add(line.getTotalCredit());
        }

        Map<String, BigDecimal> summary = new HashMap<>();
        int invalidCount = 0;

        for (TrialBalanceLine line : lines) {
            if (line.getNetBalance() != null) {
                String className = getAccountClassName(line.getAccountCode());
                summary.merge(className, line.getNetBalance(), BigDecimal::add);
            } else {
                invalidCount++;
            }
        }

        if (invalidCount > 0) {
            log.warn("Found {} trial balance lines with null net balance", invalidCount);
        }

        boolean isBalanced = totalDebit.setScale(2, RoundingMode.HALF_UP)
                .compareTo(totalCredit.setScale(2, RoundingMode.HALF_UP)) == 0;

        if (!isBalanced) {
            log.warn("TRIAL BALANCE OUT OF BALANCE! Debit: {}, Credit: {}, Difference: {}",
                    totalDebit, totalCredit, totalDebit.subtract(totalCredit));
        }

        return TrialBalanceReport.builder()
                .companyName(companyRepository.getReferenceById(companyId).getLegalName())
                .asOfDate(asOfDate)
                .generatedAt(LocalDateTime.now())
                .lines(lines)
                .totalDebit(totalDebit)
                .totalCredit(totalCredit)
                .balanced(isBalanced)
                .summary(summary)
                .build();
    }

    public TrialBalanceReportDetailed getTrialBalanceDetailed(LocalDate startDate, LocalDate endDate) {
        // CORREGIDO: Usamos el método unificado del Tenant en lugar de getCompanyId()
        Long companyId = currentTenantId();

        if (startDate == null || endDate == null) {
            throw new InvalidOperationException("Start date and end date are required");
        }
        if (startDate.isAfter(endDate)) {
            throw new InvalidOperationException("Start date cannot be after end date");
        }

        log.info("Generating detailed Trial Balance for company ID: {} from {} to {}", companyId, startDate, endDate);

        Map<String, BigDecimal> openingBalances = new HashMap<>();
        List<Object[]> openings = accountRepository.getOpeningBalances(companyId, startDate);

        for (Object[] row : openings) {
            String code = (String) row[0];
            BigDecimal balance = row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO;
            openingBalances.put(code, balance.setScale(2, RoundingMode.HALF_UP));
        }

        List<Object[]> activities = accountRepository.getPeriodActivity(companyId, startDate, endDate);

        List<TrialBalanceLineDetailed> lines = new ArrayList<>();
        BigDecimal totalOpeningBalance = BigDecimal.ZERO;
        BigDecimal totalPeriodDebit = BigDecimal.ZERO;
        BigDecimal totalPeriodCredit = BigDecimal.ZERO;
        BigDecimal totalNetMovement = BigDecimal.ZERO;
        BigDecimal totalClosingBalance = BigDecimal.ZERO;

        Map<String, BigDecimal> summaryByClass = new HashMap<>();

        for (Object[] row : activities) {
            String code = (String) row[0];
            String name = (String) row[1];
            AccountClass accountClass = (AccountClass) row[2];
            boolean closesAtYearEnd = (boolean) row[3];
            BigDecimal periodDebit = row[4] != null ? (BigDecimal) row[4] : BigDecimal.ZERO;
            BigDecimal periodCredit = row[5] != null ? (BigDecimal) row[5] : BigDecimal.ZERO;

            periodDebit = periodDebit.setScale(2, RoundingMode.HALF_UP);
            periodCredit = periodCredit.setScale(2, RoundingMode.HALF_UP);

            BigDecimal opening = closesAtYearEnd
                    ? BigDecimal.ZERO
                    : openingBalances.getOrDefault(code, BigDecimal.ZERO);

            BigDecimal netMovement = periodDebit.subtract(periodCredit);
            BigDecimal closing = opening.add(netMovement);

            String classDisplay = getAccountClassDisplay(accountClass);

            TrialBalanceLineDetailed line = TrialBalanceLineDetailed.builder()
                    .accountCode(code)
                    .accountName(name)
                    .accountClass(classDisplay)
                    .balanceSheetAccount(!closesAtYearEnd)
                    .openingBalance(opening)
                    .periodDebit(periodDebit)
                    .periodCredit(periodCredit)
                    .netMovement(netMovement)
                    .closingBalance(closing)
                    .build();

            lines.add(line);

            totalOpeningBalance = totalOpeningBalance.add(opening);
            totalPeriodDebit = totalPeriodDebit.add(periodDebit);
            totalPeriodCredit = totalPeriodCredit.add(periodCredit);
            totalNetMovement = totalNetMovement.add(netMovement);
            totalClosingBalance = totalClosingBalance.add(closing);

            summaryByClass.merge(classDisplay, closing, BigDecimal::add);
        }

        boolean isBalanced = totalPeriodDebit.setScale(2, RoundingMode.HALF_UP)
                .compareTo(totalPeriodCredit.setScale(2, RoundingMode.HALF_UP)) == 0;

        if (!isBalanced) {
            log.warn("DETAILED TRIAL BALANCE OUT OF BALANCE! Period Debit: {}, Period Credit: {}, Difference: {}",
                    totalPeriodDebit, totalPeriodCredit, totalPeriodDebit.subtract(totalPeriodCredit));
        }

        return TrialBalanceReportDetailed.builder()
                .companyName(companyRepository.getReferenceById(companyId).getLegalName())
                .startDate(startDate)
                .endDate(endDate)
                .generatedAt(LocalDateTime.now())
                .lines(lines)
                .totalOpeningBalance(totalOpeningBalance.setScale(2, RoundingMode.HALF_UP))
                .totalPeriodDebit(totalPeriodDebit.setScale(2, RoundingMode.HALF_UP))
                .totalPeriodCredit(totalPeriodCredit.setScale(2, RoundingMode.HALF_UP))
                .totalNetMovement(totalNetMovement.setScale(2, RoundingMode.HALF_UP))
                .totalClosingBalance(totalClosingBalance.setScale(2, RoundingMode.HALF_UP))
                .balanced(isBalanced)
                .summaryByClass(summaryByClass)
                .build();
    }

    // NOTE (2026-09-08): these used to return hardcoded English display
    // strings (e.g. "1 - Assets") that went straight into the summary Map
    // key -- fine as long as nothing rendered them, but the moment the
    // frontend put "Resumen por Clase" on screen, a Spanish-language user
    // saw "1 - Assets" with no way to translate it (the key WAS the
    // display text). Returning a bare, language-neutral code instead lets
    // the frontend translate it for display, same as every other
    // backend-owned code in this app (see apiErrors.js on the frontend).
    private String getAccountClassDisplay(AccountClass accountClass) {
        return switch (accountClass) {
            case ASSET -> "1";
            case LIABILITY -> "2";
            case EQUITY -> "3";
            case REVENUE -> "4";
            case EXPENSE -> "5";
            case COST -> "6-7";
        };
    }

    private String getAccountClassName(String code) {
        if (code == null || code.isEmpty()) {
            return "OTHER";
        }
        char firstDigit = code.charAt(0);
        return switch (firstDigit) {
            case '1' -> "1";
            case '2' -> "2";
            case '3' -> "3";
            case '4' -> "4";
            case '5' -> "5";
            case '6', '7' -> "6-7";
            default -> "OTHER";
        };
    }

    // ═══════════════════════════════════════════════════════════
    // DTO MAPPING
    // ═══════════════════════════════════════════════════════════

    private JournalEntryResponseDTO mapToResponseDTO(JournalEntry entry) {
        if (entry == null) {
            return null;
        }

        JournalEntryResponseDTO dto = new JournalEntryResponseDTO();
        dto.setId(entry.getId());
        dto.setDocumentNumber(entry.getDocumentNumber());
        dto.setDocumentTypeId(entry.getDocumentType().getId());
        dto.setEntryDate(entry.getEntryDate());
        dto.setDescription(entry.getDescription());
        // BUG FIX: these three fields exist on the DTO specifically so the
        // frontend can show annulment status, but were never populated here
        // -- every response (create/annul/update/find/list) silently
        // reported annulled=false regardless of the entry's real state.
        dto.setAnnulled(entry.isAnnulled());
        dto.setAnnulledAt(entry.getAnnulledAt());
        dto.setAnnulmentReason(entry.getAnnulmentReason());

        List<JournalEntryResponseDTO.ItemResponse> items = entry.getItems().stream()
                .map(item -> {
                    JournalEntryResponseDTO.ItemResponse i = new JournalEntryResponseDTO.ItemResponse();
                    i.setId(item.getId());
                    i.setAccountId(item.getAccount().getId());
                    i.setAccountCode(item.getAccount().getCode());
                    i.setAccountName(item.getAccount().getName());
                    i.setDebit(item.getDebit());
                    i.setCredit(item.getCredit());
                    i.setDescription(item.getDescription());

                    if (item.getThirdParty() != null) {
                        i.setThirdPartyId(item.getThirdParty().getId());
                        i.setThirdPartyIdNumber(item.getThirdParty().getDocumentNumber());
                        i.setThirdPartyName(item.getThirdParty().getLegalDisplayName());
                    }
                    if (item.getCostCenter() != null) {
                        i.setCostCenterId(item.getCostCenter().getId());
                        i.setCostCenterName(item.getCostCenter().getName());
                    }
                    return i;
                })
                .toList();

        dto.setItems(items);
        return dto;
    }
}