package be.smobile.reconciliation.imports;

import be.smobile.reconciliation.config.AwsProperties;
import be.smobile.reconciliation.repository.BankStatementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * API 2 ("Upload Bank Statement") of the 2026-09-16 "Frontend API Requirements" doc: accepts the
 * raw PDF as {@code multipart/form-data}, uploads it to S3, sends it to Pilim's AI extraction
 * microservice (see {@link BankStatementExtractionClient}), then hands the parsed result to the
 * already-built {@link BankTransactionImportService}. Kept separate from that class: this one
 * owns the HTTP-upload-specific concerns (bytes/MD5/S3/AI-call), while
 * {@link BankTransactionImportService} stays testable as a pure "given an extraction, do the
 * right thing" service with no multipart/S3/HTTP awareness at all.
 * <p>
 * <b>{@code accountId}:</b> the doc's own API 2 request has no such parameter - "backend
 * implementation can be adjusted as needed" per the doc's own cover note, and there is no other
 * way to know which of Pilim's internal bank accounts a statement belongs to (no bank-accounts
 * table exists in this service to resolve it from the extracted IBAN - see
 * {@code BankStatementExtraction.accountNumber()}'s own javadoc), so the controller requires it
 * as an explicit request parameter alongside the file.
 * <p>
 * <b>S3 upload, 2026-09-16:</b> previously explicitly out of this service's scope ("the actual
 * S3 upload is the client's internal developer's job" - see this project's earlier history).
 * The Frontend API Requirements doc changes that: the frontend now posts the raw file to this
 * service instead of a pre-uploaded S3 path, so something here has to put it in S3 - the user
 * explicitly pointed at moac-doc-gen-service's existing {@code S3Client}/{@code PutObjectRequest}
 * pattern to reuse, which this class does (see {@code S3ClientConfig}).
 */
@Service
@RequiredArgsConstructor
public class BankStatementUploadService {

    private final S3Client s3Client;
    private final AwsProperties awsProperties;
    private final BankStatementRepository bankStatementRepository;
    private final BankStatementExtractionClient extractionClient;
    private final BankTransactionImportService bankTransactionImportService;

    public ImportResult upload(UUID accountId, MultipartFile file) {
        byte[] bytes = readBytes(file);
        String md5 = md5Hex(bytes);

        // Short-circuit before spending an S3 upload + an AI-extraction call on a file this
        // service already knows it's going to reject - BankTransactionImportService checks
        // this same condition again itself (the real safety net, e.g. against a concurrent
        // duplicate upload), so this is purely a cost/latency optimization, not a correctness
        // requirement.
        if (bankStatementRepository.existsByFileMd5(md5)) {
            return ImportResult.forDuplicateFile();
        }

        String s3Key = putToS3(accountId, file, bytes);
        BankStatementExtraction extraction = extractionClient.extract(file.getOriginalFilename(), bytes);
        return bankTransactionImportService.importStatement(accountId, md5, s3Key, extraction);
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded bank statement file", e);
        }
    }

    private String md5Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // MD5 is guaranteed available on every standard JDK - see MessageDigest's own javadoc.
            throw new IllegalStateException("MD5 MessageDigest unavailable", e);
        }
    }

    private String putToS3(UUID accountId, MultipartFile file, byte[] bytes) {
        String bucket = java.util.Objects.requireNonNull(awsProperties.s3().bucket(), "aws.s3.bucket must be configured");
        String key = "bank-statements/%s/%s-%s".formatted(accountId, UUID.randomUUID(), sanitizeFileName(file.getOriginalFilename()));
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType("application/pdf")
                .build();
        s3Client.putObject(request, RequestBody.fromBytes(bytes));
        return key;
    }

    private String sanitizeFileName(String originalFilename) {
        String name = originalFilename == null ? "statement.pdf" : originalFilename;
        // Strip anything that isn't safe in an S3 key / URL - keeps the original name readable
        // (client feedback 2026-09-13: "If a user clicks on the link on UI, it will open the
        // file") without letting path separators or odd characters through.
        return name.replaceAll("[^A-Za-z0-9._-]", "_").toLowerCase(Locale.ROOT);
    }
}
