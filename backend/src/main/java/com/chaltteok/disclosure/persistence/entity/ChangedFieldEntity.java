package com.chaltteok.disclosure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 관계에서 변경된 필드 영속 모델. docs/architecture/disclosure-persistence.md
 */
@Entity
@Table(
        name = "changed_field",
        indexes = @Index(name = "idx_changed_field_relation_id", columnList = "relation_id")
)
public class ChangedFieldEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "relation_id", nullable = false)
    private DisclosureRelationEntity relation;

    @Column(name = "fact_key", nullable = false, length = 128)
    private String factKey;

    @Column(name = "before_value", columnDefinition = "TEXT")
    private String beforeValue;

    @Column(name = "after_value", columnDefinition = "TEXT")
    private String afterValue;

    @Column(name = "unit", length = 64)
    private String unit;

    public ChangedFieldEntity() {
    }

    public Long getId() {
        return id;
    }

    public DisclosureRelationEntity getRelation() {
        return relation;
    }

    public void setRelation(DisclosureRelationEntity relation) {
        this.relation = relation;
    }

    public String getFactKey() {
        return factKey;
    }

    public void setFactKey(String factKey) {
        this.factKey = factKey;
    }

    public String getBeforeValue() {
        return beforeValue;
    }

    public void setBeforeValue(String beforeValue) {
        this.beforeValue = beforeValue;
    }

    public String getAfterValue() {
        return afterValue;
    }

    public void setAfterValue(String afterValue) {
        this.afterValue = afterValue;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }
}
