package be.smobile.reconciliation.imports;

import be.smobile.reconciliation.config.AiExtractionProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Calls Pilim's AI extraction microservice to turn one bank statement PDF into a
 * {@link BankStatementExtraction} - same {@link RestClient}-backed-component pattern as
 * moac-doc-gen-service's {@code FileServiceClient}. See {@link AiExtractionProperties}'s
 * javadoc for why the target endpoint is still unconfirmed as of 2026-09-16, and
 * {@code BankStatementExtraction}'s own javadoc for the exact request/response contract this
 * class assumes (posted here as {@code multipart/form-data}, mirroring how the PDF itself
 * arrives at this service's own {@code POST /bank-statements} - see the "Frontend API
 * Requirements" doc's API 2).
 */
@Component
public class BankStatementExtractionClient {

    private final RestClient restClient;

    public BankStatementExtractionClient(AiExtractionProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeout()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.readTimeout()));

        this.restClient = RestClient.builder()
                .baseUrl(properties.url())
                .requestFactory(requestFactory)
                .build();
    }

    public BankStatementExtraction extract(String fileName, byte[] fileBytes) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", fileBytes).filename(fileName).contentType(MediaType.APPLICATION_PDF);

        return restClient.post()
                .uri("/extract/bank-statement")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body.build())
                .retrieve()
                .body(BankStatementExtraction.class);
    }
}
