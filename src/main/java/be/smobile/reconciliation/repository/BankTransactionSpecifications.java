package be.smobile.reconciliation.repository;

import be.smobile.reconciliation.entity.BankTransaction;
import be.smobile.reconciliation.model.enums.BankTransactionStatus;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Builds the optional/combinatorial filters for API 3 ("Get Bank Transactions") - year, month,
 * status, and a free-text search across description/reference/bankName. A derived query method
 * can't express "apply this filter only when the caller actually supplied it" without one method
 * per combination (16 for 4 independent optional filters) - {@link Specification} composes them
 * instead, each returning {@code null} (meaning "no restriction") when its filter is absent.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BankTransactionSpecifications {

    public static Specification<BankTransaction> withFilters(Integer year, Integer month, BankTransactionStatus status, String search) {
        return withFilters(year, month, null, null, status, search);
    }

    /**
     * {@code startDate}/{@code endDate} - client feedback 2026-09-29 ("Date Range Filter
     * Addition"): {@code year}/{@code month} alone can only ever select one whole calendar
     * month, so a caller wanting e.g. "the last 10 days" or a range spanning two months had no
     * way to ask for it. When both are supplied they replace the year/month restriction
     * entirely rather than being intersected with it - the doc's own example URL shows every
     * parameter at once, but a caller specific enough to give an exact range is asking for that
     * range, not that range further cut down to whichever month year/month also happens to
     * name. Same all-or-nothing rule as year/month itself: one without the other has no
     * well-defined range, so it's treated as absent (falls back to year/month, if given).
     */
    public static Specification<BankTransaction> withFilters(Integer year, Integer month, LocalDate startDate, LocalDate endDate,
                                                               BankTransactionStatus status, String search) {
        Specification<BankTransaction> dateFilter = dateRange(startDate, endDate);
        if (dateFilter == null) {
            dateFilter = yearMonth(year, month);
        }
        List<Specification<BankTransaction>> specs = Stream.of(dateFilter, hasStatus(status), matchesSearch(search))
                .filter(Objects::nonNull)
                .toList();
        return specs.isEmpty() ? Specification.unrestricted() : Specification.allOf(specs);
    }

    private static Specification<BankTransaction> dateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            return null;
        }
        return (root, query, cb) -> cb.between(root.get("transactionDate"), startDate, endDate);
    }

    private static Specification<BankTransaction> yearMonth(Integer year, Integer month) {
        if (year == null && month == null) {
            return null;
        }
        // Month without a year (or vice versa) has no well-defined single range - a search
        // that specific is treated as "no date filter" rather than guessing which year/every
        // year, per API 3's own "year/month: Optional/Required" (left ambiguous by the doc).
        if (year == null || month == null) {
            return null;
        }
        YearMonth ym = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        return (root, query, cb) -> cb.between(root.get("transactionDate"), start, end);
    }

    private static Specification<BankTransaction> hasStatus(BankTransactionStatus status) {
        if (status == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("reconciliationStatus"), status);
    }

    private static Specification<BankTransaction> matchesSearch(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String pattern = "%" + search.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("description")), pattern),
                cb.like(cb.lower(root.get("reference")), pattern),
                cb.like(cb.lower(root.get("bankName")), pattern));
    }
}
