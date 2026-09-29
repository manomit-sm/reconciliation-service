package be.smobile.reconciliation.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Builds the {@link S3Client} bean used to store uploaded bank statement PDFs (API 2, "Upload
 * Bank Statement", of the 2026-09-16 "Frontend API Requirements" doc) - identical pattern to
 * moac-doc-gen-service's own {@code S3ClientConfig}, which uploads generated UBL invoices the
 * same way.
 * <p>
 * <b>Why this service does the upload itself now, when it previously didn't:</b> up to
 * 2026-09-15, {@code BankTransactionImportService} only ever recorded whatever S3 path a caller
 * handed it - actually getting a file into S3 was explicitly the client's own internal
 * developer's job (see {@code BankStatement.transactionFile}'s earlier javadoc history). The
 * Frontend API Requirements doc changes that: API 2 accepts the raw PDF as
 * {@code multipart/form-data}, not a pre-uploaded S3 path, so <i>something</i> has to put the
 * bytes in S3 - and the user explicitly pointed at moac-doc-gen-service's existing S3 code as
 * the reference to reuse for it.
 */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
public class S3ClientConfig {

    @Bean
    public S3Client s3Client(AwsProperties awsProperties) {
        return S3Client.builder()
                .region(Region.of(awsProperties.region()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
