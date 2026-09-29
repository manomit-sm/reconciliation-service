package be.smobile.reconciliation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code aws.*} configuration tree - same shape as moac-doc-gen-service's own
 * {@code AwsProperties}, for consistency across Pilim services sharing one AWS account/region.
 *
 * @param s3     S3-specific settings
 * @param region the AWS region used for all AWS SDK clients
 */
@ConfigurationProperties(prefix = "aws")
public record AwsProperties(S3 s3, String region) {

    public record S3(String bucket) {
    }
}
