package be.smobile.reconciliation.repository;

import be.smobile.reconciliation.entity.BankStatement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** {@code BankStatement}'s {@code @Id} is a {@link UUID} (see {@link BankStatement}), not a {@code Long}. */
public interface BankStatementRepository extends JpaRepository<BankStatement, UUID> {

    /**
     * Exact re-upload check, moved here from {@code BankTransactionRepository} 2026-09-15 along
     * with {@code file_md5} itself - client feedback: "we check MD5 to decide if it is
     * duplicated. We reject it in case it is exactly the same file."
     */
    boolean existsByFileMd5(String fileMd5);
}
