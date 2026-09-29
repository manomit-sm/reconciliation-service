package be.smobile.reconciliation.repository;

import be.smobile.reconciliation.entity.BankTransaction;
import be.smobile.reconciliation.model.enums.BankTransactionStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Client feedback 2026-09-29 ("Date Range Filter Addition") - see {@link BankTransactionSpecifications}'s own javadoc for the precedence rule. */
class BankTransactionSpecificationsTest {

    @SuppressWarnings("unchecked")
    private final Root<BankTransaction> root = mock(Root.class);
    private final CriteriaQuery<?> query = mock(CriteriaQuery.class);
    private final CriteriaBuilder cb = mock(CriteriaBuilder.class);
    @SuppressWarnings("unchecked")
    private final Path<LocalDate> path = mock(Path.class);
    @SuppressWarnings("unchecked")
    private final Path<BankTransactionStatus> statusPath = mock(Path.class);

    @Test
    void startAndEndDate_areUsedInsteadOfYearMonth() {
        when(root.<LocalDate>get("transactionDate")).thenReturn(path);
        when(cb.between(eq(path), any(LocalDate.class), any(LocalDate.class))).thenReturn(mock(Predicate.class));

        LocalDate start = LocalDate.of(2026, 9, 5);
        LocalDate end = LocalDate.of(2026, 10, 12);
        Specification<BankTransaction> spec = BankTransactionSpecifications.withFilters(2026, 1, start, end, null, null);
        spec.toPredicate(root, query, cb);

        verify(cb).between(path, start, end);
        // Never asked for the whole-month range year/month would have produced.
        verify(cb, never()).between(path, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
    }

    @Test
    void onlyOneOfStartEndDate_fallsBackToYearMonth() {
        when(root.<LocalDate>get("transactionDate")).thenReturn(path);
        when(cb.between(eq(path), any(LocalDate.class), any(LocalDate.class))).thenReturn(mock(Predicate.class));

        Specification<BankTransaction> spec = BankTransactionSpecifications.withFilters(2026, 9, LocalDate.of(2026, 9, 5), null, null, null);
        spec.toPredicate(root, query, cb);

        verify(cb).between(path, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
    }

    @Test
    void noDateFilterAtAll_isUnrestricted() {
        assertNull(BankTransactionSpecifications.withFilters(null, null, null, null, null, null).toPredicate(root, query, cb));
    }

    @Test
    void statusAndSearchStillCompose_alongsideAnExplicitDateRange() {
        when(root.<LocalDate>get("transactionDate")).thenReturn(path);
        when(root.<BankTransactionStatus>get("reconciliationStatus")).thenReturn(statusPath);
        when(cb.between(eq(path), any(LocalDate.class), any(LocalDate.class))).thenReturn(mock(Predicate.class));
        when(cb.equal(eq(statusPath), eq(BankTransactionStatus.NEEDS_REVIEW))).thenReturn(mock(Predicate.class));
        when(cb.and(any(), any())).thenReturn(mock(Predicate.class));

        Specification<BankTransaction> spec = BankTransactionSpecifications.withFilters(
                null, null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), BankTransactionStatus.NEEDS_REVIEW, null);

        assertNotNull(spec.toPredicate(root, query, cb));
        verify(cb).between(path, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        verify(cb).equal(statusPath, BankTransactionStatus.NEEDS_REVIEW);
    }
}
