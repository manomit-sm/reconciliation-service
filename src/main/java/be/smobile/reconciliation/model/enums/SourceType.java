package be.smobile.reconciliation.model.enums;

import java.util.Locale;

/**
 * Where a {@code BankTransaction} row came from - client-supplied schema update 2026-09-15
 * ("Tables_Of_Reconciliation_feature_v02.sql", Gdrive folder
 * 20260915_Milestone2_documents_and_files): "We need to support both PDF imports and
 * Ponto/Open Banking API... Supports PDF, Ponto, manual entries, and future providers."
 * <p>
 * Only {@link #PDF} is actually produced by this milestone's import flow
 * ({@code BankTransactionImportService}) - {@link #PONTO} and {@link #MANUAL} exist so the
 * schema/entity are ready for those without guessing at their (not yet confirmed) ingestion
 * logic. A plain string would have worked too, but this is exactly the kind of small, closed,
 * code-controlled vocabulary an enum is for - unlike e.g. {@code bankName}, which is an
 * open-ended value the bank itself supplies.
 * <p>
 * <b>Wire value, 2026-09-16:</b> the "Frontend API Requirements" doc's own {@code source_type}
 * example lists a different vocabulary entirely ({@code api}/{@code upload}/{@code manual}),
 * not a case-fold of this enum's names. Deliberately not adopted: {@code api} is generic enough
 * to cover any future non-file provider, but this service's schema already names the real one
 * concretely ({@code ponto}, confirmed with the client by that exact name in the 2026-09-15
 * schema conversation) - reporting the honest, specific value is more useful than a vaguer
 * placeholder the doc itself only offered as an illustrative example, and the doc's own cover
 * note says the backend can adjust the contract as long as the underlying behavior/data holds.
 * {@link #wireValue()} therefore lowercases this enum's own name ({@code pdf}/{@code ponto}/
 * {@code manual}) rather than translating to {@code upload}/{@code api}/{@code manual}.
 */
public enum SourceType {
    PDF,
    PONTO,
    MANUAL;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
