package be.smobile.reconciliation.imports;

import be.smobile.reconciliation.config.AwsProperties;
import be.smobile.reconciliation.model.enums.TransactionDirection;
import be.smobile.reconciliation.repository.BankStatementRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers API 2 ("Upload Bank Statement") - the multipart/S3/MD5/AI-extraction wiring in front of {@link BankTransactionImportService}. */
@ExtendWith(MockitoExtension.class)
class BankStatementUploadServiceTest {

    private static final byte[] PDF_BYTES = "not a real pdf".getBytes();
    // Precomputed MD5 of PDF_BYTES (verified via `md5sum`), so the "already imported" branch can
    // be tested without duplicating MessageDigest logic from the class under test.
    private static final String PDF_MD5 = "b48c63c0f060815310d6f2adde79be4b";

    @Mock
    private S3Client s3Client;
    @Mock
    private BankStatementRepository bankStatementRepository;
    @Mock
    private BankStatementExtractionClient extractionClient;
    @Mock
    private BankTransactionImportService bankTransactionImportService;

    private final AwsProperties awsProperties = new AwsProperties(new AwsProperties.S3("pilim-test-bucket"), "eu-west-1");

    private BankStatementUploadService service() {
        return new BankStatementUploadService(s3Client, awsProperties, bankStatementRepository, extractionClient, bankTransactionImportService);
    }

    @Test
    void duplicateFile_rejectedOutright_withoutUploadingOrCallingExtraction() {
        when(bankStatementRepository.existsByFileMd5(anyString())).thenReturn(true);
        MockMultipartFile file = new MockMultipartFile("file", "statement.pdf", "application/pdf", PDF_BYTES);

        ImportResult result = service().upload(UUID.randomUUID(), file);

        assertTrue(result.duplicateFile());
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(extractionClient, never()).extract(anyString(), any());
        verify(bankTransactionImportService, never()).importStatement(any(), anyString(), anyString(), any());
    }

    @Test
    void cleanUpload_uploadsToS3ThenExtractsThenDelegatesToImportService() {
        UUID accountId = UUID.randomUUID();
        when(bankStatementRepository.existsByFileMd5(anyString())).thenReturn(false);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        BankStatementExtraction extraction = new BankStatementExtraction("Belfius", "BE00", "EUR",
                List.of(new ExtractedTransaction(LocalDate.of(2026, 9, 5), "Customer ABC", "INV-001", new BigDecimal("250.00"), TransactionDirection.CREDIT)));
        when(extractionClient.extract(eq("statement.pdf"), any())).thenReturn(extraction);
        ImportResult expected = new ImportResult(List.of(), List.of(), false);
        when(bankTransactionImportService.importStatement(eq(accountId), anyString(), anyString(), eq(extraction))).thenReturn(expected);

        MockMultipartFile file = new MockMultipartFile("file", "statement.pdf", "application/pdf", PDF_BYTES);
        ImportResult result = service().upload(accountId, file);

        assertEquals(expected, result);
        verify(s3Client).putObject(ArgumentMatchers.argThat((PutObjectRequest req) ->
                "pilim-test-bucket".equals(req.bucket())
                        && req.key().startsWith("bank-statements/" + accountId + "/")
                        && req.key().endsWith("-statement.pdf")
                        && "application/pdf".equals(req.contentType())), any(RequestBody.class));
        // The S3 key handed to the import service must be exactly the one just uploaded under.
        verify(bankTransactionImportService).importStatement(eq(accountId), eq(PDF_MD5),
                ArgumentMatchers.argThat(key -> key.startsWith("bank-statements/" + accountId + "/")), eq(extraction));
    }
}
