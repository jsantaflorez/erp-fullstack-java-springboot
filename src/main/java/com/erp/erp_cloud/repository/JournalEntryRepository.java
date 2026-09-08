package com.erp.erp_cloud.repository;

import com.erp.erp_cloud.entity.Company;
import com.erp.erp_cloud.entity.JournalEntry;
import com.erp.erp_cloud.entity.JournalEntryItem;
import com.erp.erp_cloud.entity.ThirdParty;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Pageable;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    // ═══════════════════════════════════════════════════════════
    // ADVANCED SEARCH ENGINE (With Native Tenant & Pagination Shield)
    // ═══════════════════════════════════════════════════════════

    /**
     * Search journal entries with filters.
     * Excludes logically deleted records (active = false).
     * ADAPTED: Uses companyId (Long) and synchronizes countQuery filters to prevent Pageable discrepancies.
     */
    @Query(value = "SELECT j FROM JournalEntry j " +
            "JOIN FETCH j.documentType " +
            "WHERE j.company.id = :companyId " +
            "AND j.active = true " +
            "AND (:searchTerm IS NULL OR " +
            "     LOWER(j.description) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
            "     LOWER(j.documentNumber) LIKE LOWER(CONCAT(:searchTerm, '%'))) " +
            "AND (:startDate IS NULL OR j.entryDate >= :startDate) " +
            "AND (:endDate IS NULL OR j.entryDate <= :endDate)",
            countQuery = "SELECT COUNT(j) FROM JournalEntry j " +
                    "WHERE j.company.id = :companyId " +
                    "AND j.active = true " +
                    "AND (:searchTerm IS NULL OR " +
                    "     LOWER(j.description) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
                    "     LOWER(j.documentNumber) LIKE LOWER(CONCAT(:searchTerm, '%'))) " +
                    "AND (:startDate IS NULL OR j.entryDate >= :startDate) " +
                    "AND (:endDate IS NULL OR j.entryDate <= :endDate)")
    Page<JournalEntry> searchEntries(
            @Param("companyId") Long companyId,
            @Param("searchTerm") String searchTerm,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            Pageable pageable);

    // ═══════════════════════════════════════════════════════════
    // ADAPTED TENANT METHODS (Primitive ID-based for optimization)
    // ═══════════════════════════════════════════════════════════

    /**
     * Checks if a third party has associated transactions in active journal entries under the current tenant context.
     * ADAPTED: Multi-tenant defense added to ensure data isolation.
     */
    @Query("SELECT CASE WHEN COUNT(item) > 0 THEN true ELSE false END " +
            "FROM JournalEntry e JOIN e.items item " +
            "WHERE e.company.id = :companyId " +
            "AND item.thirdParty = :thirdParty " +
            "AND e.active = true")
    boolean existsByCompanyIdAndThirdParty(@Param("companyId") Long companyId, @Param("thirdParty") ThirdParty thirdParty);

    /**
     * Checks for duplicate document numbers.
     * ADAPTED: Uses CompanyId instead of Company entity to support multi-tenant native long IDs.
     */
    boolean existsByCompanyIdAndDocumentNumberAndActiveTrue(Long companyId, String documentNumber);

    /**
     * Alias for compatibility with existing Service logic.
     * ADAPTED: Accepts Long companyId and routes to the updated query method.
     */
    default boolean existsByCompanyIdAndDocumentNumber(Long companyId, String documentNumber) {
        return existsByCompanyIdAndDocumentNumberAndActiveTrue(companyId, documentNumber);
    }

    /**
     * Finds an active journal entry by document number.
     * ADAPTED: Derived query method using CompanyId.
     */
    Optional<JournalEntry> findByCompanyIdAndDocumentNumberAndActiveTrue(Long companyId, String documentNumber);

    /**
     * Alias for compatibility with existing Service logic.
     * ADAPTED: Accepts Long companyId and routes to the active-check query method.
     */
    default Optional<JournalEntry> findByCompanyIdAndDocumentNumber(Long companyId, String documentNumber) {
        return findByCompanyIdAndDocumentNumberAndActiveTrue(companyId, documentNumber);
    }

    // ═══════════════════════════════════════════════════════════
    // CORE FINANCIAL REPORTING METHODS
    // ═══════════════════════════════════════════════════════════

    /**
     * Calculates account balances for the Trial Balance report.
     * ADAPTED: Evaluates a.company.id against the primitive Long parameter.
     *
     * BUG FIX (2026-09-08): dropped "AND a.active = true". This report is
     * explicitly generated for an arbitrary past asOfDate (see
     * FinancialStatementService.getBalanceSheet), but the CURRENT active
     * flag has nothing to do with whether the account legitimately had a
     * balance as of that historical date -- deactivating an account (e.g.
     * closing an old bank account) is meant to block its use in NEW entries
     * only, per the deactivate() docs ("Historical transactions are
     * preserved... Deactivated accounts remain visible in historical
     * reports"). Filtering on current active status here made a Balance
     * Sheet for a PAST date silently drop any account deactivated since
     * then, even though it correctly had a balance on that date. Safe to
     * remove: an inactive account with no real postings simply never
     * matches the join to JournalEntryItem in the first place, so this
     * can't resurrect zero-activity dead accounts -- only genuinely
     * historical balances.
     */
    @Query("""
        SELECT 
            a.code,
            CAST(a.nature AS string),
            COALESCE(SUM(i.debit), 0),
            COALESCE(SUM(i.credit), 0)
        FROM JournalEntryItem i
        JOIN i.account a
        JOIN i.journalEntry je
        WHERE a.company.id = :companyId
          AND je.entryDate <= :asOfDate
          AND je.active = true
          AND a.postingAccount = true
        GROUP BY a.code, a.nature
        ORDER BY a.code
    """)
    List<Object[]> getAccountBalancesAsOfDate(
            @Param("companyId") Long companyId,
            @Param("asOfDate") LocalDate asOfDate
    );

    /**
     * Retrieves all items for the Auxiliary Ledger.
     * ADAPTED: Filters via e.company.id utilizing the primitive tenant ID.
     */
    @Query("""
        SELECT i FROM JournalEntry e 
        JOIN e.items i 
        JOIN i.account a 
        WHERE e.company.id = :companyId 
          AND e.active = true 
          AND e.entryDate BETWEEN :startDate AND :endDate 
          AND a.code BETWEEN :startCode AND :endCode 
          AND a.postingAccount = true 
        ORDER BY a.code ASC, e.entryDate ASC, e.id ASC
    """)
    List<JournalEntryItem> findItemsForAuxiliary(
            @Param("companyId") Long companyId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("startCode") String startCode,
            @Param("endCode") String endCode
    );

    // ═══════════════════════════════════════════════════════════
    // LEGACY METHODS (Object-based for backward compatibility)
    // ═══════════════════════════════════════════════════════════

    @Query("SELECT CASE WHEN COUNT(item) > 0 THEN true ELSE false END " +
            "FROM JournalEntry e JOIN e.items item " +
            "WHERE item.thirdParty = :thirdParty AND e.active = true")
    boolean existsByThirdParty(@Param("thirdParty") ThirdParty thirdParty);
}