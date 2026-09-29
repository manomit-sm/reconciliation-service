package be.smobile.reconciliation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Invoices, expenses and credit/debit notes all live in this one table - per the client's
 * 2026-09-12 feedback on the design doc, this is the existing shared entity (owned by
 * account-service, table {@code supplier_documents_record}) that the reconciliation module
 * reuses instead of a bespoke {@code Invoice} entity, to avoid duplicating a table another
 * service already owns and keep this module consistent with the rest of Pilim's backend.
 * <p>
 * Lives in each account's own schema ({@code tenant_<accountId>}) - see the
 * {@code be.smobile.reconciliation.multitenancy} package - so, like account-service's own
 * version of this entity, there is deliberately no {@code schema} on {@link Table}: which
 * schema a query hits is decided per-request by {@code search_path}, not a hardcoded name.
 * <p>
 * <b>Field-by-field port of {@code be.smobile.mobilacc.entities.SupplierDocumentsRecord}</b>
 * (the file the client uploaded), cross-checked against the real column list from their
 * {@code pg_dump} of {@code tenant_9000000002.supplier_documents_record} - with these
 * deliberate differences:
 * <ul>
 *     <li>Numeric types match the actual Postgres column type exactly ({@code smallint} ->
 *     {@link Short}, {@code integer} -> {@link Integer}, {@code numeric} -> {@link BigDecimal})
 *     rather than the reference entity's {@code int}/{@code Long} - that entity runs under
 *     {@code hibernate.ddl-auto: update} (see account-service's application.yml), which doesn't
 *     enforce exact column-type matching the way this service's {@code ddl-auto: validate}
 *     does; copying its looser typing here would very likely fail schema validation at
 *     startup.</li>
 *     <li>{@code date}/{@code timestamp} columns use {@link LocalDate}/{@link LocalDateTime}
 *     instead of {@code java.sql.Date}/{@code java.sql.Timestamp} - both map correctly to the
 *     same Postgres columns; the java.time types are what the rest of this module (the status
 *     algorithm) already works with.</li>
 *     <li>{@code categoryType} is a plain {@link String}, not the real
 *     {@code be.smobile.mobilacc.enums.CategoryType} enum - that enum isn't available to this
 *     service, and guessing at an incomplete copy of its values would risk a deserialization
 *     failure on any row whose value wasn't in the guess. Same reasoning applies to
 *     {@code status}/{@code paidStatus}: kept as plain numbers rather than mapped to this
 *     module's own {@code InvoiceStatus}, since the only documented mapping is the inline
 *     comment on the reference entity's {@code status} field (0/8 = Draft, 1/2 = Unsent,
 *     3/4 = Sent, 5 = Overdue, 6 = Paid) - deliberately not encoded as an enum here so an
 *     incomplete guess doesn't silently misclassify a code this comment doesn't cover.</li>
 *     <li>Omits the reference entity's {@code @Transient items}/{@code client} fields and the
 *     {@code invoice_bank_details} {@code @ManyToMany} to {@code CompanyBankDetailsInformation} -
 *     those reference entity types this service doesn't have and aren't needed for
 *     reconciliation.</li>
 *     <li>Adds {@code createdAt}/{@code updatedAt}/{@code documentType} - real columns on the
 *     table that the reference Java file didn't map at all.</li>
 * </ul>
 * <b>Not written to by this service.</b> account-service owns this table; the reconciliation
 * module only reads it (see {@link be.smobile.reconciliation.repository.SupplierDocumentsRecordRepository}).
 */
@Entity
@Table(name = "supplier_documents_record")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupplierDocumentsRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate date;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "mobile_number")
    private String mobileNumber;

    @Column(nullable = false)
    private String category;

    private Double amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "local_value")
    private Double localValue;

    @Column(name = "invoice_pdf_file")
    private String invoicePdfFile;

    @Column(name = "invoice_xml_file")
    private String invoiceXmlFile;

    @Column(name = "invoice_csv_file")
    private String invoiceCsvFile;

    @Column(name = "invoice_csv_file_path", nullable = false)
    private String invoiceCsvFilePath;

    /**
     * 0/8 = Draft, 1/2 = Unsent, 3/4 = Sent, 5 = Overdue, 6 = Paid - per the reference entity's
     * own inline comment. Deliberately not mapped to an enum - see class javadoc.
     */
    private Short status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @Column(name = "document_type")
    private Short documentType;

    @Column(name = "load_type")
    private String loadType;

    @Column(nullable = false)
    private Long clientid;

    @Column(name = "client_email")
    private String clientEmail;

    /** Credit/debit indicator - exact value set not confirmed. */
    private String crdr;

    private Double discount;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "invoice_number")
    private String invoiceNumber;

    @Column(name = "sub_total")
    private Double subTotal;

    @Column(name = "total_due")
    private Double totalDue;

    @Column(name = "total_vat")
    private Double totalVat;

    @Column(name = "business_name")
    private String businessName;

    @Column(name = "business_phone_number")
    private String businessPhoneNumber;

    @Column(name = "address_line1")
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    @Column(name = "address_line3")
    private String addressLine3;

    @Column(name = "address_line4")
    private String addressLine4;

    @Column(name = "business_email")
    private String businessEmail;

    @Column(name = "accountant_email")
    private String accountantEmail;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "sent_to_client")
    private Short sentToClient;

    @Column(name = "sent_to_client_date_time")
    private LocalDateTime sentToClientDateTime;

    @Column(name = "sent_to_client_message_id")
    private String sentToClientMessageId;

    @Column(name = "sent_to_accountant")
    private Short sentToAccountant;

    @Column(name = "sent_to_accountant_date_time")
    private LocalDateTime sentToAccountantDateTime;

    @Column(name = "sent_to_accountant_message_id")
    private String sentToAccountantMessageId;

    @Column(name = "destination_type")
    private Short destinationType;

    @Column(name = "paid_status", nullable = false)
    private Integer paidStatus;

    @Column(name = "payment_term", nullable = false)
    private Integer paymentTerm;

    @Column(name = "order_number")
    private String orderNumber;

    @Column(name = "client_currency")
    private String clientCurrency;

    @Column(name = "credit_note_description")
    private String creditNoteDescription;

    @Column(name = "credit_note_reason")
    private String creditNoteReason;

    @Column(name = "credit_note_reference_invoice")
    private String creditNoteReferenceInvoice;

    @Column(name = "client_name")
    private String clientName;

    @Column(name = "category_id")
    private Integer categoryId;

    @Column(name = "product_type")
    private String productType;

    @Column(name = "destination_country_iso2")
    private String destinationCountryIso2;

    @Column(name = "invoice_type")
    private String invoiceType;

    @Column(name = "income_service")
    private String incomeService;

    @Column(name = "signed_pdf_file")
    private String signedPdfFile;

    private String about;

    @Column(name = "supplier_name")
    private String supplierName;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "add_to_postponed_accounting", nullable = false)
    private Boolean addToPostponedAccounting;

    private String description;

    private String reference;

    @Column(name = "additional_information_a")
    private String additionalInformationA;

    @Column(name = "additional_information_b")
    private String additionalInformationB;

    @Column(name = "contact_person")
    private String contactPerson;

    @Column(name = "user_identifier")
    private String userIdentifier;

    private String etag;

    @Column(name = "supplier_id")
    private Long supplierId;

    @Column(name = "original_vat_amount")
    private BigDecimal originalVatAmount;

    @Column(name = "vat_percentage")
    private BigDecimal vatPercentage;

    @Column(name = "sent_to_accounting_system")
    private Boolean sentToAccountingSystem;

    @Column(name = "accounting_system_reference")
    private String accountingSystemReference;

    @Column(name = "debit_note_reference_invoice")
    private String debitNoteReferenceInvoice;

    @Column(name = "debit_note_reason")
    private String debitNoteReason;

    @Column(name = "debit_note_description")
    private String debitNoteDescription;

    @Column(name = "debit_note_supporting_pdf_file")
    private String debitNoteSupportingPdfFile;

    /** See class javadoc - kept as String, not the real CategoryType enum. */
    @Column(name = "category_type", length = 100)
    private String categoryType;

    @Column(name = "peppol_order_id", length = 40)
    private String peppolOrderId;

    @Column(name = "peppol_status", length = 50)
    private String peppolStatus;

    @Column(name = "peppol_status_info", length = 500)
    private String peppolStatusInfo;

    @Column(name = "peppol_status_updated_at")
    private LocalDateTime peppolStatusUpdatedAt;

    @Column(name = "send_transport_type", length = 50)
    private String sendTransportType;

    @Column(name = "receive_transport_type", length = 50)
    private String receiveTransportType;

    @Column(name = "provider_status", length = 50)
    private String providerStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_fields", columnDefinition = "jsonb")
    private Map<String, Object> customFields;

    @Column(name = "peppol_provider", length = 100)
    private String peppolProvider;

    @Column(name = "draft_invoice")
    private Boolean draftInvoice;

    @Column(name = "dokapi_external_reference", length = 255)
    private String dokapiExternalReference;

    @Column(name = "dokapi_sent_event_ulid", length = 255)
    private String dokapiSentEventUlid;

    @Column(name = "dokapi_received_event_ulid", length = 255)
    private String dokapiReceivedEventUlid;

    @Column(name = "dokapi_refused")
    private Boolean dokapiRefused;
}
