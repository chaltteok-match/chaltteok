package com.chaltteok.disclosure.persistence;

import com.chaltteok.disclosure.model.ChangedField;
import com.chaltteok.disclosure.model.DisclosureDocument;
import com.chaltteok.disclosure.model.DisclosureRelation;
import com.chaltteok.disclosure.model.EvidenceChunk;
import com.chaltteok.disclosure.model.ExtractedFact;
import com.chaltteok.disclosure.model.PreprocessResult;
import com.chaltteok.disclosure.persistence.entity.ChangedFieldEntity;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import com.chaltteok.disclosure.persistence.entity.DisclosureRelationEntity;
import com.chaltteok.disclosure.persistence.entity.EvidenceChunkEntity;
import com.chaltteok.disclosure.persistence.entity.ExtractedFactEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@link PreprocessResult} 순수 모델과 JPA Entity 변환.
 */
@Component
public class PreprocessResultMapper {

    public DisclosureDocumentEntity toDocumentEntity(PreprocessResult result) {
        DisclosureDocument document = result.document();
        DisclosureDocumentEntity entity = new DisclosureDocumentEntity();
        entity.setDocId(document.docId());
        entity.setSchemaVersion(result.schemaVersion());
        entity.setParserVersion(result.parserVersion());
        entity.setCorpCode(document.corpCode());
        entity.setCorpName(document.corpName());
        entity.setListedName(document.listedName());
        entity.setStockCode(document.stockCode());
        entity.setIndustry(document.industry());
        entity.setSector(document.sector());
        entity.setDocGroup(document.docGroup());
        entity.setDocSubtype(document.docSubtype());
        entity.setReportNm(document.reportNm());
        entity.setRceptNo(document.rceptNo());
        entity.setRceptDt(document.rceptDt());
        entity.setFlrNm(document.flrNm());
        entity.setCorrection(Boolean.TRUE.equals(document.isCorrection()));
        entity.setBaseYear(document.baseYear());
        entity.setBaseMonth(document.baseMonth());
        entity.setSourcePath(document.sourcePath());
        entity.setManifestFileFormat(document.manifestFileFormat());
        entity.setSourceFileCount(document.sourceFileCount());

        Map<String, EvidenceChunkEntity> chunksById = new HashMap<>();
        for (EvidenceChunk chunk : result.evidenceChunks()) {
            EvidenceChunkEntity chunkEntity = toChunkEntity(chunk);
            entity.addEvidenceChunk(chunkEntity);
            chunksById.put(chunkEntity.getChunkId(), chunkEntity);
        }

        for (ExtractedFact fact : result.extractedFacts()) {
            EvidenceChunkEntity chunkEntity = chunksById.get(fact.chunkId());
            if (chunkEntity == null) {
                throw new IllegalArgumentException(
                        "extracted_fact.chunk_id에 해당하는 evidence_chunk가 없습니다: " + fact.chunkId());
            }
            entity.addExtractedFact(toFactEntity(fact, chunkEntity));
        }

        return entity;
    }

    public DisclosureRelationEntity toRelationEntity(
            DisclosureRelation relation,
            DisclosureDocumentEntity originalDocument,
            DisclosureDocumentEntity relatedDocument
    ) {
        DisclosureRelationEntity entity = new DisclosureRelationEntity();
        entity.setRelationId(relation.relationId());
        entity.setEventKey(relation.eventKey());
        entity.setOriginalDocument(originalDocument);
        entity.setRelatedDocument(relatedDocument);
        entity.setRelationType(relation.relationType());
        entity.setPreviousStatus(relation.previousStatus());
        entity.setCurrentStatus(relation.currentStatus());
        entity.setEvidenceChunkIds(new ArrayList<>(relation.evidenceChunkIds()));

        for (ChangedField field : relation.changedFields()) {
            entity.addChangedField(toChangedFieldEntity(field));
        }
        return entity;
    }

    public DisclosureRelation toRelationModel(DisclosureRelationEntity entity) {
        return new DisclosureRelation(
                entity.getRelationId(),
                entity.getEventKey(),
                entity.getOriginalDocument().getDocId(),
                entity.getRelatedDocument().getDocId(),
                entity.getRelationType(),
                entity.getPreviousStatus(),
                entity.getCurrentStatus(),
                entity.getChangedFields().stream()
                        .map(field -> new ChangedField(
                                field.getFactKey(),
                                field.getBeforeValue(),
                                field.getAfterValue(),
                                field.getUnit()
                        ))
                        .toList(),
                new ArrayList<>(entity.getEvidenceChunkIds())
        );
    }

    private EvidenceChunkEntity toChunkEntity(EvidenceChunk chunk) {
        EvidenceChunkEntity entity = new EvidenceChunkEntity();
        entity.setChunkId(chunk.chunkId());
        entity.setSequence(chunk.sequence());
        entity.setContentType(chunk.contentType());
        entity.setText(chunk.text());
        entity.setSourceFile(chunk.sourceFile());
        entity.setSourceFormat(chunk.sourceFormat());
        entity.setPageNumber(chunk.pageNumber());
        entity.setSectionTitle(chunk.sectionTitle());
        entity.setTableTitle(chunk.tableTitle());
        entity.setRowLabel(chunk.rowLabel());
        entity.setColumnLabel(chunk.columnLabel());
        return entity;
    }

    private ExtractedFactEntity toFactEntity(ExtractedFact fact, EvidenceChunkEntity chunkEntity) {
        ExtractedFactEntity entity = new ExtractedFactEntity();
        entity.setFactId(fact.factId());
        entity.setEvidenceChunk(chunkEntity);
        entity.setFactKey(fact.factKey());
        entity.setLabel(fact.label());
        entity.setValueType(fact.valueType());
        entity.setRawValue(fact.rawValue());
        entity.setRawUnit(fact.rawUnit());
        entity.setNormalizedValue(fact.normalizedValue());
        entity.setNormalizedUnit(fact.normalizedUnit());
        entity.setPeriodStart(fact.periodStart());
        entity.setPeriodEnd(fact.periodEnd());
        entity.setAsOfDate(fact.asOfDate());
        entity.setPeriodBasis(fact.periodBasis());
        entity.setScope(fact.scope());
        entity.setExtractionStatus(fact.extractionStatus());
        return entity;
    }

    private ChangedFieldEntity toChangedFieldEntity(ChangedField field) {
        ChangedFieldEntity entity = new ChangedFieldEntity();
        entity.setFactKey(field.factKey());
        entity.setBeforeValue(field.beforeValue());
        entity.setAfterValue(field.afterValue());
        entity.setUnit(field.unit());
        return entity;
    }
}
