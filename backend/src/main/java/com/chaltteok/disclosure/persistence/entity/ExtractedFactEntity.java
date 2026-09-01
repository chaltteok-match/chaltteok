package com.chaltteok.disclosure.persistence.entity;

import com.chaltteok.disclosure.model.enums.ExtractionStatus;
import com.chaltteok.disclosure.model.enums.PeriodBasis;
import com.chaltteok.disclosure.model.enums.Scope;
import com.chaltteok.disclosure.model.enums.ValueType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * 구조화 사실 영속 모델. docs/architecture/disclosure-persistence.md
 */
@Entity
@Table(
        name = "extracted_fact",
        indexes = {
                @Index(name = "idx_extracted_fact_fact_key", columnList = "fact_key"),
                @Index(name = "idx_extracted_fact_doc_id", columnList = "doc_id"),
                @Index(name = "idx_extracted_fact_chunk_id", columnList = "chunk_id")
        }
)
public class ExtractedFactEntity {

    @Id
    @Column(name = "fact_id", nullable = false, length = 256)
    private String factId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doc_id", nullable = false)
    private DisclosureDocumentEntity document;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chunk_id", nullable = false)
    private EvidenceChunkEntity evidenceChunk;

    @Column(name = "fact_key", nullable = false, length = 128)
    private String factKey;

    @Column(name = "label", nullable = false, length = 256)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 32)
    private ValueType valueType;

    @Column(name = "raw_value", nullable = false, columnDefinition = "TEXT")
    private String rawValue;

    @Column(name = "raw_unit", length = 64)
    private String rawUnit;

    @Column(name = "normalized_value", length = 128)
    private String normalizedValue;

    @Column(name = "normalized_unit", length = 64)
    private String normalizedUnit;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "as_of_date")
    private LocalDate asOfDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_basis", nullable = false, length = 32)
    private PeriodBasis periodBasis;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 32)
    private Scope scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_status", nullable = false, length = 32)
    private ExtractionStatus extractionStatus;

    public ExtractedFactEntity() {
    }

    public String getFactId() {
        return factId;
    }

    public void setFactId(String factId) {
        this.factId = factId;
    }

    public DisclosureDocumentEntity getDocument() {
        return document;
    }

    public void setDocument(DisclosureDocumentEntity document) {
        this.document = document;
    }

    public EvidenceChunkEntity getEvidenceChunk() {
        return evidenceChunk;
    }

    public void setEvidenceChunk(EvidenceChunkEntity evidenceChunk) {
        this.evidenceChunk = evidenceChunk;
    }

    public String getFactKey() {
        return factKey;
    }

    public void setFactKey(String factKey) {
        this.factKey = factKey;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public ValueType getValueType() {
        return valueType;
    }

    public void setValueType(ValueType valueType) {
        this.valueType = valueType;
    }

    public String getRawValue() {
        return rawValue;
    }

    public void setRawValue(String rawValue) {
        this.rawValue = rawValue;
    }

    public String getRawUnit() {
        return rawUnit;
    }

    public void setRawUnit(String rawUnit) {
        this.rawUnit = rawUnit;
    }

    public String getNormalizedValue() {
        return normalizedValue;
    }

    public void setNormalizedValue(String normalizedValue) {
        this.normalizedValue = normalizedValue;
    }

    public String getNormalizedUnit() {
        return normalizedUnit;
    }

    public void setNormalizedUnit(String normalizedUnit) {
        this.normalizedUnit = normalizedUnit;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public void setPeriodStart(LocalDate periodStart) {
        this.periodStart = periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public void setPeriodEnd(LocalDate periodEnd) {
        this.periodEnd = periodEnd;
    }

    public LocalDate getAsOfDate() {
        return asOfDate;
    }

    public void setAsOfDate(LocalDate asOfDate) {
        this.asOfDate = asOfDate;
    }

    public PeriodBasis getPeriodBasis() {
        return periodBasis;
    }

    public void setPeriodBasis(PeriodBasis periodBasis) {
        this.periodBasis = periodBasis;
    }

    public Scope getScope() {
        return scope;
    }

    public void setScope(Scope scope) {
        this.scope = scope;
    }

    public ExtractionStatus getExtractionStatus() {
        return extractionStatus;
    }

    public void setExtractionStatus(ExtractionStatus extractionStatus) {
        this.extractionStatus = extractionStatus;
    }
}
