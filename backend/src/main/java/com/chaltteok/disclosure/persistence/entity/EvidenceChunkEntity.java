package com.chaltteok.disclosure.persistence.entity;

import com.chaltteok.disclosure.model.enums.ContentType;
import com.chaltteok.disclosure.model.enums.SourceFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 검색·답변 근거 조각 영속 모델. docs/architecture/disclosure-persistence.md
 */
@Entity
@Table(name = "evidence_chunk")
public class EvidenceChunkEntity {

    @Id
    @Column(name = "chunk_id", nullable = false, length = 256)
    private String chunkId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doc_id", nullable = false)
    private DisclosureDocumentEntity document;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 32)
    private ContentType contentType;

    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "source_file", nullable = false, length = 1024)
    private String sourceFile;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_format", nullable = false, length = 32)
    private SourceFormat sourceFormat;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "section_title", length = 512)
    private String sectionTitle;

    @Column(name = "table_title", length = 512)
    private String tableTitle;

    @Column(name = "row_label", length = 512)
    private String rowLabel;

    @Column(name = "column_label", length = 512)
    private String columnLabel;

    public EvidenceChunkEntity() {
    }

    public String getChunkId() {
        return chunkId;
    }

    public void setChunkId(String chunkId) {
        this.chunkId = chunkId;
    }

    public DisclosureDocumentEntity getDocument() {
        return document;
    }

    public void setDocument(DisclosureDocumentEntity document) {
        this.document = document;
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public ContentType getContentType() {
        return contentType;
    }

    public void setContentType(ContentType contentType) {
        this.contentType = contentType;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(String sourceFile) {
        this.sourceFile = sourceFile;
    }

    public SourceFormat getSourceFormat() {
        return sourceFormat;
    }

    public void setSourceFormat(SourceFormat sourceFormat) {
        this.sourceFormat = sourceFormat;
    }

    public Integer getPageNumber() {
        return pageNumber;
    }

    public void setPageNumber(Integer pageNumber) {
        this.pageNumber = pageNumber;
    }

    public String getSectionTitle() {
        return sectionTitle;
    }

    public void setSectionTitle(String sectionTitle) {
        this.sectionTitle = sectionTitle;
    }

    public String getTableTitle() {
        return tableTitle;
    }

    public void setTableTitle(String tableTitle) {
        this.tableTitle = tableTitle;
    }

    public String getRowLabel() {
        return rowLabel;
    }

    public void setRowLabel(String rowLabel) {
        this.rowLabel = rowLabel;
    }

    public String getColumnLabel() {
        return columnLabel;
    }

    public void setColumnLabel(String columnLabel) {
        this.columnLabel = columnLabel;
    }
}
