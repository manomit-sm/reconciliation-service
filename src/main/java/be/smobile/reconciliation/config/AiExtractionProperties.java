package be.smobile.reconciliation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code ai-extraction.*} tree, reaching Pilim's AI microservice that turns a bank
 * statement PDF into a {@code BankStatementExtraction} - the same microservice pattern already
 * used for invoice extraction (client-supplied 2026-09-13), via a bank-statement endpoint that
 * is, as of 2026-09-16, still only a <b>proposal</b> handed back to the client (see
 * {@code BankStatementExtraction}'s own javadoc: "a proposal to hand back to the client so
 * their AI microservice team can implement a matching endpoint"). No real base URL has been
 * confirmed yet - {@code url} is left as an unset placeholder (like every other cross-service
 * URL in this project, e.g. {@code JWT_ISSUER_URI}), to be filled in via the
 * {@code AI_EXTRACTION_URL} env var once that endpoint exists.
 *
 * @param url            base URL of the AI extraction microservice
 * @param connectTimeout connect timeout in milliseconds
 * @param readTimeout    read timeout in milliseconds - generous default: OCR/parsing a
 *                       multi-page statement PDF is not a fast call
 */
@ConfigurationProperties(prefix = "ai-extraction")
public record AiExtractionProperties(String url, int connectTimeout, int readTimeout) {

    public AiExtractionProperties {
        if (connectTimeout <= 0) {
            connectTimeout = 5000;
        }
        if (readTimeout <= 0) {
            readTimeout = 30000;
        }
    }
}
