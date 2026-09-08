package com.erp.erp_cloud.service;

import com.erp.erp_cloud.dto.ChartOfAccountRequest;
import com.erp.erp_cloud.dto.ChartOfAccountResponseDTO;
import com.erp.erp_cloud.entity.ChartOfAccounts;
import com.erp.erp_cloud.exception.DuplicateResourceException;
import com.erp.erp_cloud.exception.InvalidOperationException;
import com.erp.erp_cloud.exception.ResourceNotFoundException;
import com.erp.erp_cloud.repository.ChartOfAccountsRepository;
import com.erp.erp_cloud.repository.CompanyRepository;
import com.erp.erp_cloud.security.context.TenantContext;
import com.erp.erp_cloud.service.base.TenantAwareService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ChartOfAccountService extends TenantAwareService {

    private final ChartOfAccountsRepository repository;
    private final CompanyRepository companyRepository;


    // ═══════════════════════════════════════════════════════════
    // WRITE OPERATIONS
    // ═══════════════════════════════════════════════════════════

    /**
     * Creates a new chart of accounts entry with full accounting structure validation.
     * Tenant isolation enforced via currentTenantId() from TenantAwareService.
     */
    public ChartOfAccountResponseDTO create(ChartOfAccountRequest request) {
        Long companyId = currentTenantId();

        log.debug("Creating account | code: {} | tenant: {}", request.getCode(), companyId);


        // Prevent duplicate account codes within the same tenant
        if (repository.existsByCompanyIdAndCode(companyId, request.getCode())) {
            throw new DuplicateResourceException("ChartOfAccount", "code", request.getCode());
        }

        ChartOfAccounts account = new ChartOfAccounts();
        ChartOfAccounts parent = null;

        // Resolve and validate parent account if provided
        if (request.getParentId() != null) {
            // findByIdAndCompany prevents loading parent accounts from other tenants
            parent = repository.findByIdAndCompany(request.getParentId(), companyId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "ChartOfAccount (parent)", request.getParentId()));

            // BUSINESS RULE: Posting (auxiliary) accounts cannot have children

            if (parent.isPostingAccount()) {
                throw new InvalidOperationException(
                        "Cannot add children to a posting account",
                        "PARENT_IS_POSTING_ACCOUNT"
                );
            }


            // BUSINESS RULE: All children must share the same AccountClass as the parent
            validateHierarchyConsistency(parent, request);

            account.setParent(parent);
            account.setLevel((byte) (parent.getLevel() + 1));


        } else {
            account.setParent(null);
            account.setLevel((byte) 1);
        }

        // Validate PUC code structure before persisting
        validateCodeStructure(parent, request);

        if (request.getAccountCategory() != null && request.getAccountClass() != null
                && !request.getAccountCategory().belongsToClass(request.getAccountClass())) {
            throw new InvalidOperationException(
                    "The selected account category does not belong to the chosen account class.",
                    "CATEGORY_CLASS_MISMATCH"
            );
        }



        // Map DTO fields to entity and bind to current tenant
        mapDtoToEntity(request, account);

        account.setCompany(companyRepository.getReferenceById(companyId));
        account.setActive(true);


        ChartOfAccounts saved = repository.save(account);
        log.info("Account created | id: {} | code: {} | tenant: {}",
                saved.getId(), saved.getCode(), companyId);



        return mapToResponseDTO(saved);
    }

    /**
     * Updates an existing chart of accounts entry.
     * Account code changes are strictly forbidden per accounting standards.
     */
    public ChartOfAccountResponseDTO update(Long id, ChartOfAccountRequest request) {
        log.debug("Updating account | id: {} | tenant: {}", id, currentTenantId());

        // Secure lookup — prevents accessing accounts from other tenants
        ChartOfAccounts existing = findEntityById(id);

        // ACCOUNTING RULE: Account codes are immutable once created
        if (!existing.getCode().equals(request.getCode())) {
            throw new InvalidOperationException(
                    "Account code cannot be modified. Create a new account if the code is incorrect.",
                    "CODE_IMMUTABLE"
            );
        }

        // BUSINESS RULE: Cannot convert to posting account if children exist
        if (Boolean.TRUE.equals(request.getPostingAccount()) && !existing.isPostingAccount()) {
            if (repository.existsByParent(existing)) {
                throw new InvalidOperationException(
                        "Cannot convert to posting account — account has child accounts.",
                        "HAS_CHILDREN_CANNOT_POST"
                );
            }
        }

        // BUSINESS RULE (2026-09-07, user-reported): the mirror-image case
        // of the rule above. A posting account is the ONLY kind of account
        // journal entry items are allowed to reference; every reporting
        // query in ChartOfAccountsRepository (getTrialBalance,
        // getAccountsForBalanceSheet, getOpeningBalances, getPeriodActivity,
        // getAccountsForIncomeStatement...) filters strictly on
        // postingAccount = true to decide what counts as a real leaf with
        // movements. Turning postingAccount OFF on an account that already
        // has journal entry items would silently drop its historical
        // movements out of every one of those reports while leaving the
        // underlying entries untouched — a data-integrity trap, not just a
        // UX nicety. The repository already had existsByCompanyIdAndAccount
        // defined for exactly this ("Used to prevent deletion/deactivation
        // of accounts with movements") but nothing ever called it; before
        // this fix, unchecking "Cuenta de Movimiento" on an account with
        // existing entries (e.g. account 110505) succeeded silently.
        if (Boolean.FALSE.equals(request.getPostingAccount()) && existing.isPostingAccount()) {
            if (repository.existsByCompanyIdAndAccount(currentTenantId(), existing)) {
                throw new InvalidOperationException(
                        "Cannot remove posting-account status — this account already has journal entry movements.",
                        "POSTING_ACCOUNT_HAS_MOVEMENTS"
                );
            }
        }

        // INTENTIONAL: Account class changes are allowed even with existing transactions.
        // Rationale: Accountants may reclassify accounts retroactively for reporting purposes.
        // Historical transactions are preserved; only future reporting is affected.
        if (!existing.getAccountClass().equals(request.getAccountClass())) {
            log.warn("Account class reclassification | account: {} | from: {} | to: {} | tenant: {}",
                    existing.getCode(),
                    existing.getAccountClass(),
                    request.getAccountClass(),
                    currentTenantId());
        }

        // Resolve parent for hierarchy validation
        ChartOfAccounts parentToValidate;

        if (request.getParentId() != null) {
            // Prevent self-referencing
            if (id.equals(request.getParentId())) {
                throw new InvalidOperationException("An account cannot be its own parent.", "SELF_PARENT_NOT_ALLOWED");
            }
            // Secure parent lookup — scoped to current tenant
            parentToValidate = repository.findByIdAndCompany(request.getParentId(), currentTenantId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "ChartOfAccount (parent)", request.getParentId()));

            // Prevent circular references in the account hierarchy
            if (isDescendant(id, parentToValidate)) {
                throw new InvalidOperationException(
                        "Circular reference detected — cannot move account under one of its descendants.",
                        "CIRCULAR_REFERENCE"
                );
            }

            if (parentToValidate.isPostingAccount()) {
                throw new InvalidOperationException("Parent cannot be a posting account.", "PARENT_IS_POSTING_ACCOUNT");
            }

            validateHierarchyConsistency(parentToValidate, request);

            existing.setParent(parentToValidate);
            existing.setLevel((byte) (parentToValidate.getLevel() + 1));

        } else {
            // No parent change requested — preserve existing hierarchy position
            parentToValidate = existing.getParent();
            existing.setLevel(parentToValidate == null ? (byte) 1
                    : (byte) (parentToValidate.getLevel() + 1));
        }

        if (request.getAccountCategory() != null && request.getAccountClass() != null
                && !request.getAccountCategory().belongsToClass(request.getAccountClass())) {
            throw new InvalidOperationException(
                    "The selected account category does not belong to the chosen account class.",
                    "CATEGORY_CLASS_MISMATCH"
            );
        }
        validateCodeStructure(parentToValidate, request);
        mapDtoToEntity(request, existing);

        ChartOfAccounts updated = repository.save(existing);
        log.info("Account updated | id: {} | code: {} | tenant: {}",
                updated.getId(), updated.getCode(), currentTenantId());

        return mapToResponseDTO(updated);
    }

    /**
     * Deactivates an account to prevent use in future journal entries.
     *
     * INTENTIONAL: Deactivation is allowed even on accounts with existing transactions.
     * Historical data integrity is preserved — only new usage is blocked.
     * Deactivated accounts remain visible in historical reports.
     *
     * BUG FIX (2026-09-08): that last sentence was previously false in
     * practice -- every date-scoped reporting query (Balance Sheet, Income
     * Statement, Trial Balance as of date, opening balances, period
     * activity) filtered on the account's CURRENT active flag, so
     * deactivating an account silently erased its historical balances from
     * any report covering a date it was still active for. Those queries
     * (ChartOfAccountsRepository / JournalEntryRepository) no longer filter
     * on active for historical reporting -- postingAccount = true plus the
     * date range is what actually decides inclusion now, which is also why
     * update() blocks removing postingAccount once an account has real
     * movements (see POSTING_ACCOUNT_HAS_MOVEMENTS above): that invariant
     * is what keeps these reports trustworthy.
     */
    public void deactivate(Long id) {
        log.debug("Deactivating account | id: {} | tenant: {}", id, currentTenantId());

        ChartOfAccounts account = findEntityById(id);

        // Log warning if deactivating a parent — children may be orphaned operationally
        if (repository.existsByParent(account)) {
            log.warn("Deactivating account with children | code: {} | tenant: {} | " +
                    "Consider deactivating children first.", account.getCode(), currentTenantId());
        }

        account.setActive(false);
        repository.save(account);

        log.info("Account deactivated | id: {} | code: {} | tenant: {} | " +
                "Historical transactions preserved.", id, account.getCode(), currentTenantId());
    }

    /**
     * Reactivates a previously deactivated account.
     * Requires the parent account to be active before reactivating a child.
     */
    public void activate(Long id) {
        log.debug("Activating account | id: {} | tenant: {}", id, currentTenantId());

        ChartOfAccounts account = findEntityById(id);

        // BUSINESS RULE: Parent must be active before a child can be reactivated
        if (account.getParent() != null && !account.getParent().isActive()) {
            throw new InvalidOperationException(String.format(
                    "Cannot activate account '%s' — parent account '%s' is inactive. Activate the parent first.",
                    account.getCode(), account.getParent().getCode()),
                    "PARENT_INACTIVE_CANNOT_ACTIVATE");
        }

        account.setActive(true);
        repository.save(account);

        log.info("Account activated | id: {} | code: {} | tenant: {}",
                id, account.getCode(), currentTenantId());
    }

    // ═══════════════════════════════════════════════════════════
    // READ OPERATIONS
    // ═══════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Page<ChartOfAccountResponseDTO> listAll(String searchTerm, Pageable pageable) {
        Long companyId = currentTenantId();

        log.debug("Listing accounts | tenant: {} | search: {}", companyId, searchTerm);

        Page<ChartOfAccounts> entities = (searchTerm != null && !searchTerm.trim().isEmpty())
                ? repository.searchByText(companyId, searchTerm, pageable)
                : repository.findAllByCompany(companyId, pageable);

        return entities.map(this::mapToResponseDTO);
    }

    @Transactional(readOnly = true)
    public ChartOfAccountResponseDTO findById(Long id) {
        return mapToResponseDTO(findEntityById(id));
    }

    @Transactional(readOnly = true)
    public List<ChartOfAccountResponseDTO> listRoots() {
        Long companyId = currentTenantId();
        log.debug("Listing root accounts | tenant: {}", companyId);

        return repository.findByCompanyIdAndParentIsNullOrderByCodeAsc(companyId)
                .stream()
                .map(this::mapToResponseDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public ChartOfAccountResponseDTO findByCode(String code) {
        Long companyId = currentTenantId();
        log.debug("Finding account by code: {} | tenant: {}", code, companyId);

        return repository.findByCompanyIdAndCode(companyId, code)
                .map(this::mapToResponseDTO)
                .orElseThrow(() -> new ResourceNotFoundException(
                        String.format("ChartOfAccount with code '%s' not found", code)));
    }

    @Transactional(readOnly = true)
    public List<ChartOfAccountResponseDTO> listChildren(Long parentId) {
        Long companyId = currentTenantId();
        log.debug("Listing children | parentId: {} | tenant: {}", parentId, companyId);

        return repository.findByCompanyIdAndParentIdOrderByCodeAsc(companyId, parentId)
                .stream()
                .map(this::mapToResponseDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<ChartOfAccountResponseDTO> listPostingAccounts(Pageable pageable) {
        Long companyId = currentTenantId();
        log.debug("Listing posting accounts | tenant: {}", companyId);

        return repository.findPostingAccounts(companyId, pageable)
                .map(this::mapToResponseDTO);
    }

    @Transactional(readOnly = true)
    public Page<ChartOfAccountResponseDTO> listByLevel(Byte level, Pageable pageable) {
        Long companyId = currentTenantId();
        log.debug("Listing accounts by level: {} | tenant: {}", level, companyId);

        return repository.findByCompanyIdAndLevelAndActiveTrue(companyId, level, pageable)
                .map(this::mapToResponseDTO);
    }

    // ═══════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════

    /**
     * Secure entity lookup — scoped to current tenant via TenantAwareRepository.
     * Prevents horizontal privilege escalation between tenants.
     */
    private ChartOfAccounts findEntityById(Long id) {
        Long companyId = currentTenantId();
        log.debug("Fetching account | id: {} | tenant: {}", id, companyId);

        return repository.findByIdAndCompany(id, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("ChartOfAccount", id));
    }

    /**
     * Validates PUC (Colombian accounting standard) code structure:
     * Level 1 → 1 digit  (e.g. "1")
     * Level 2 → 2 digits (e.g. "11")
     * Level 3 → 4 digits (e.g. "1105")
     * Level 4+ → parent length + 2 (minimum 6 digits for posting accounts)
     */
    private void validateCodeStructure(ChartOfAccounts parent, ChartOfAccountRequest request) {
        String code = request.getCode();
        int codeLength = code.length();

        // Posting accounts must be at Level 4 or deeper (minimum 6 digits)
        if (Boolean.TRUE.equals(request.getPostingAccount()) && codeLength < 6) {
            throw new InvalidOperationException(String.format(
                    "Posting accounts require at least 6 digits (Level 4+). Provided: %d digits.", codeLength),
                    "POSTING_ACCOUNT_CODE_TOO_SHORT");
        }

        // Root accounts must have exactly 1 digit
        if (parent == null) {
            if (codeLength != 1) {
                throw new InvalidOperationException(String.format(
                        "Root accounts must have exactly 1 digit. Provided: '%s'.", code),
                        "ROOT_ACCOUNT_CODE_INVALID_LENGTH");
            }
            return;
        }

        String parentCode = parent.getCode();
        int parentLength = parentCode.length();

        // Child code must start with parent code (hierarchical prefix rule)
        if (!code.startsWith(parentCode)) {
            throw new InvalidOperationException(String.format(
                    "Child code '%s' must start with parent code '%s'.", code, parentCode),
                    "CHILD_CODE_MUST_START_WITH_PARENT");
        }

        // Validate the digit-length jump follows PUC structure
        boolean isValidJump = (parentLength == 1 && codeLength == 2)
                || (parentLength == 2 && codeLength == 4)
                || (parentLength >= 4 && codeLength == parentLength + 2);

        if (!isValidJump) {
            throw new InvalidOperationException(String.format(
                    "Invalid code structure. Parent '%s' (%d digits) requires a child with %d digits. Provided: %d digits.",
                    parentCode, parentLength, getExpectedLength(parentLength), codeLength),
                    "INVALID_CODE_STRUCTURE");
        }
    }

    private int getExpectedLength(int parentLength) {
        if (parentLength == 1) return 2;
        if (parentLength == 2) return 4;
        return parentLength + 2;
    }

    /**
     * Detects circular references in the account hierarchy.
     * Traverses upward from the candidate parent to check if accountId appears.
     */
    private boolean isDescendant(Long accountId, ChartOfAccounts candidateParent) {
        ChartOfAccounts current = candidateParent;
        while (current != null) {
            if (accountId.equals(current.getId())) {
                log.warn("Circular reference detected | accountId: {} | candidateParent: {}",
                        accountId, candidateParent.getId());
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    /**
     * Validates that a child account shares the same AccountClass as its parent.
     * Nature (Debit/Credit) is intentionally NOT validated here to allow contra-accounts.
     */
    private void validateHierarchyConsistency(ChartOfAccounts parent, ChartOfAccountRequest request) {
        if (parent == null) return;

        if (!parent.getAccountClass().equals(request.getAccountClass())) {
            throw new InvalidOperationException(String.format(
                    "AccountClass mismatch. Parent '%s' is %s — all sub-accounts must share the same class.",
                    parent.getCode(), parent.getAccountClass()),
                    "PARENT_CLASS_MISMATCH");
        }
    }

    /**
     * Maps DTO fields onto an entity instance.
     * displayOrder and closesAtYearEnd are auto-set in @PrePersist/@PreUpdate.
     */
    private void mapDtoToEntity(ChartOfAccountRequest dto, ChartOfAccounts entity) {
        entity.setCode(dto.getCode());
        entity.setName(dto.getName());
        entity.setNature(dto.getNature());
        entity.setAccountClass(dto.getAccountClass());
        entity.setAccountCategory(dto.getAccountCategory());
        entity.setFinancialStatement(dto.getFinancialStatement());
        entity.setPostingAccount(dto.getPostingAccount());
        entity.setRequiresThirdParty(dto.getRequiresThirdParty());
        entity.setRequiresCostCenter(dto.getRequiresCostCenter());

        if (dto.getActive() != null) {
            entity.setActive(dto.getActive());
        }
    }

    /**
     * Maps a ChartOfAccounts entity to its response DTO.
     * Parent info is included as flat fields (code + name) to avoid deep nesting.
     */
    private ChartOfAccountResponseDTO mapToResponseDTO(ChartOfAccounts entity) {
        if (entity == null) return null;

        ChartOfAccountResponseDTO dto = new ChartOfAccountResponseDTO();
        dto.setId(entity.getId());
        dto.setCode(entity.getCode());
        dto.setName(entity.getName());
        dto.setLevel(entity.getLevel());
        dto.setNature(entity.getNature());
        dto.setAccountClass(entity.getAccountClass());
        dto.setAccountCategory(entity.getAccountCategory());
        dto.setAccountCategoryDisplay(entity.getCategoryDisplayName());
        dto.setFinancialStatement(entity.getFinancialStatement());
        dto.setFinancialStatementDisplay(entity.getFinancialStatementDisplayName());
        dto.setClosesAtYearEnd(entity.isClosesAtYearEnd());
        dto.setDisplayOrder(entity.getDisplayOrder());
        dto.setPostingAccount(entity.isPostingAccount());
        dto.setRequiresThirdParty(entity.isRequiresThirdParty());
        dto.setRequiresCostCenter(entity.isRequiresCostCenter());
        dto.setActive(entity.isActive());

        if (entity.getParent() != null) {
            dto.setParentCode(entity.getParent().getCode());
            dto.setParentName(entity.getParent().getName());
        }

        dto.setFullDescription(entity.getFullDescription());

        return dto;
    }
}