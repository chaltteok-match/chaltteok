package com.chaltteok.disclosure.persistence.entity;

import com.chaltteok.disclosure.model.enums.RelationType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/**
 * 정정·후속공시 관계 영속 모델. docs/architecture/disclosure-persistence.md
 */
@Entity
@Table(
        name = "disclosure_relation",
        indexes = {
                @Index(name = "idx_disclosure_relation_event_key", columnList = "event_key")
        }
)
public class DisclosureRelationEntity {

    @Id
    @Column(name = "relation_id", nullable = false, length = 512)
    private String relationId;

    @Column(name = "event_key", nullable = false, length = 512)
    private String eventKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "original_doc_id", nullable = false)
    private DisclosureDocumentEntity originalDocument;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "related_doc_id", nullable = false)
    private DisclosureDocumentEntity relatedDocument;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", nullable = false, length = 32)
    private RelationType relationType;

    @Column(name = "previous_status", length = 128)
    private String previousStatus;

    @Column(name = "current_status", length = 128)
    private String currentStatus;

    @OneToMany(mappedBy = "relation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ChangedFieldEntity> changedFields = new ArrayList<>();

    @ElementCollection
    @CollectionTable(
            name = "disclosure_relation_evidence_chunk",
            joinColumns = @JoinColumn(name = "relation_id")
    )
    @Column(name = "chunk_id", nullable = false, length = 256)
    @OrderColumn(name = "chunk_order")
    private List<String> evidenceChunkIds = new ArrayList<>();

    public DisclosureRelationEntity() {
    }

    public String getRelationId() {
        return relationId;
    }

    public void setRelationId(String relationId) {
        this.relationId = relationId;
    }

    public String getEventKey() {
        return eventKey;
    }

    public void setEventKey(String eventKey) {
        this.eventKey = eventKey;
    }

    public DisclosureDocumentEntity getOriginalDocument() {
        return originalDocument;
    }

    public void setOriginalDocument(DisclosureDocumentEntity originalDocument) {
        this.originalDocument = originalDocument;
    }

    public DisclosureDocumentEntity getRelatedDocument() {
        return relatedDocument;
    }

    public void setRelatedDocument(DisclosureDocumentEntity relatedDocument) {
        this.relatedDocument = relatedDocument;
    }

    public RelationType getRelationType() {
        return relationType;
    }

    public void setRelationType(RelationType relationType) {
        this.relationType = relationType;
    }

    public String getPreviousStatus() {
        return previousStatus;
    }

    public void setPreviousStatus(String previousStatus) {
        this.previousStatus = previousStatus;
    }

    public String getCurrentStatus() {
        return currentStatus;
    }

    public void setCurrentStatus(String currentStatus) {
        this.currentStatus = currentStatus;
    }

    public List<ChangedFieldEntity> getChangedFields() {
        return changedFields;
    }

    public List<String> getEvidenceChunkIds() {
        return evidenceChunkIds;
    }

    public void addChangedField(ChangedFieldEntity field) {
        changedFields.add(field);
        field.setRelation(this);
    }

    public void setEvidenceChunkIds(List<String> evidenceChunkIds) {
        this.evidenceChunkIds = evidenceChunkIds;
    }
}
