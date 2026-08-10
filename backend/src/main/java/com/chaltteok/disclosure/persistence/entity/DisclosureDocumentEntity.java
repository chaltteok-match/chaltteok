package com.chaltteok.disclosure.persistence.entity;

import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.model.enums.ManifestFileFormat;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 공시 문서 메타데이터 영속 모델. docs/architecture/disclosure-persistence.md
 */
@Entity
@Table(
        name = "disclosure_document",
        indexes = {
                @Index(name = "idx_disclosure_document_corp_code", columnList = "corp_code"),
                @Index(name = "idx_disclosure_document_corp_name", columnList = "corp_name"),
                @Index(name = "idx_disclosure_document_doc_group", columnList = "doc_group"),
                @Index(name = "idx_disclosure_document_rcept_dt", columnList = "rcept_dt")
        }
)
public class DisclosureDocumentEntity {

    @Id
    @Column(name = "doc_id", nullable = false, length = 128)
    private String docId;

    @Column(name = "schema_version", nullable = false, length = 32)
    private String schemaVersion;

    @Column(name = "parser_version", nullable = false, length = 32)
    private String parserVersion;

    @Column(name = "corp_code", nullable = false, length = 8)
    private String corpCode;

    @Column(name = "corp_name", nullable = false, length = 256)
    private String corpName;

    @Column(name = "listed_name", nullable = false, length = 256)
    private String listedName;

    @Column(name = "stock_code", nullable = false, length = 6)
    private String stockCode;

    @Column(name = "industry", nullable = false, length = 128)
    private String industry;

    @Column(name = "sector", nullable = false, length = 128)
    private String sector;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_group", nullable = false, length = 32)
    private DocGroup docGroup;

    @Column(name = "doc_subtype", length = 128)
    private String docSubtype;

    @Column(name = "report_nm", nullable = false, length = 512)
    private String reportNm;

    @Column(name = "rcept_no", nullable = false, length = 14)
    private String rceptNo;

    @Column(name = "rcept_dt", nullable = false)
    private LocalDate rceptDt;

    @Column(name = "flr_nm", nullable = false, length = 256)
    private String flrNm;

    @Column(name = "is_correction", nullable = false)
    private boolean correction;

    @Column(name = "base_year")
    private Integer baseYear;

    @Column(name = "base_month")
    private Integer baseMonth;

    @Column(name = "source_path", nullable = false, length = 1024)
    private String sourcePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "manifest_file_format", nullable = false, length = 32)
    private ManifestFileFormat manifestFileFormat;

    @Column(name = "source_file_count", nullable = false)
    private int sourceFileCount;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EvidenceChunkEntity> evidenceChunks = new ArrayList<>();

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ExtractedFactEntity> extractedFacts = new ArrayList<>();

    public DisclosureDocumentEntity() {
    }

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getParserVersion() {
        return parserVersion;
    }

    public void setParserVersion(String parserVersion) {
        this.parserVersion = parserVersion;
    }

    public String getCorpCode() {
        return corpCode;
    }

    public void setCorpCode(String corpCode) {
        this.corpCode = corpCode;
    }

    public String getCorpName() {
        return corpName;
    }

    public void setCorpName(String corpName) {
        this.corpName = corpName;
    }

    public String getListedName() {
        return listedName;
    }

    public void setListedName(String listedName) {
        this.listedName = listedName;
    }

    public String getStockCode() {
        return stockCode;
    }

    public void setStockCode(String stockCode) {
        this.stockCode = stockCode;
    }

    public String getIndustry() {
        return industry;
    }

    public void setIndustry(String industry) {
        this.industry = industry;
    }

    public String getSector() {
        return sector;
    }

    public void setSector(String sector) {
        this.sector = sector;
    }

    public DocGroup getDocGroup() {
        return docGroup;
    }

    public void setDocGroup(DocGroup docGroup) {
        this.docGroup = docGroup;
    }

    public String getDocSubtype() {
        return docSubtype;
    }

    public void setDocSubtype(String docSubtype) {
        this.docSubtype = docSubtype;
    }

    public String getReportNm() {
        return reportNm;
    }

    public void setReportNm(String reportNm) {
        this.reportNm = reportNm;
    }

    public String getRceptNo() {
        return rceptNo;
    }

    public void setRceptNo(String rceptNo) {
        this.rceptNo = rceptNo;
    }

    public LocalDate getRceptDt() {
        return rceptDt;
    }

    public void setRceptDt(LocalDate rceptDt) {
        this.rceptDt = rceptDt;
    }

    public String getFlrNm() {
        return flrNm;
    }

    public void setFlrNm(String flrNm) {
        this.flrNm = flrNm;
    }

    public boolean isCorrection() {
        return correction;
    }

    public void setCorrection(boolean correction) {
        this.correction = correction;
    }

    public Integer getBaseYear() {
        return baseYear;
    }

    public void setBaseYear(Integer baseYear) {
        this.baseYear = baseYear;
    }

    public Integer getBaseMonth() {
        return baseMonth;
    }

    public void setBaseMonth(Integer baseMonth) {
        this.baseMonth = baseMonth;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public ManifestFileFormat getManifestFileFormat() {
        return manifestFileFormat;
    }

    public void setManifestFileFormat(ManifestFileFormat manifestFileFormat) {
        this.manifestFileFormat = manifestFileFormat;
    }

    public int getSourceFileCount() {
        return sourceFileCount;
    }

    public void setSourceFileCount(int sourceFileCount) {
        this.sourceFileCount = sourceFileCount;
    }

    public List<EvidenceChunkEntity> getEvidenceChunks() {
        return evidenceChunks;
    }

    public List<ExtractedFactEntity> getExtractedFacts() {
        return extractedFacts;
    }

    public void addEvidenceChunk(EvidenceChunkEntity chunk) {
        evidenceChunks.add(chunk);
        chunk.setDocument(this);
    }

    public void addExtractedFact(ExtractedFactEntity fact) {
        extractedFacts.add(fact);
        fact.setDocument(this);
    }
}
