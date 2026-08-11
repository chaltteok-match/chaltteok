package com.chaltteok.disclosure.persistence;

import com.chaltteok.disclosure.model.DisclosureRelation;
import com.chaltteok.disclosure.model.PreprocessResult;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import com.chaltteok.disclosure.persistence.entity.DisclosureRelationEntity;
import com.chaltteok.disclosure.persistence.repository.DisclosureDocumentRepository;
import com.chaltteok.disclosure.persistence.repository.DisclosureRelationRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PreprocessResult} 한 건을 PostgreSQL에 적재한다.
 * 같은 {@code doc_id}가 있으면 관계·하위 행을 포함해 교체(upsert)한다.
 */
@Service
@ConditionalOnProperty(name = "chaltteok.disclosure.persistence.enabled", havingValue = "true")
public class DisclosurePersistenceService {

    private final DisclosureDocumentRepository documentRepository;
    private final DisclosureRelationRepository relationRepository;
    private final PreprocessResultMapper mapper;

    public DisclosurePersistenceService(
            DisclosureDocumentRepository documentRepository,
            DisclosureRelationRepository relationRepository,
            PreprocessResultMapper mapper
    ) {
        this.documentRepository = documentRepository;
        this.relationRepository = relationRepository;
        this.mapper = mapper;
    }

    @Transactional
    public DisclosureDocumentEntity save(PreprocessResult result) {
        String docId = result.document().docId();

        List<DisclosureRelationEntity> existingRelations =
                relationRepository.findByOriginalDocumentDocIdOrRelatedDocumentDocId(docId, docId);
        List<DisclosureRelation> relationsToSave = collectRelationsToSave(docId, result, existingRelations);
        if (!existingRelations.isEmpty()) {
            relationRepository.deleteAll(existingRelations);
            relationRepository.flush();
        }
        documentRepository.findById(docId).ifPresent(existing -> {
            // fact가 chunk를 참조하므로, orphanRemoval이 chunk_id를 null로 바꾸기 전에 fact를 먼저 제거한다.
            existing.getExtractedFacts().clear();
            documentRepository.flush();
            existing.getEvidenceChunks().clear();
            documentRepository.delete(existing);
        });
        documentRepository.flush();

        DisclosureDocumentEntity document = mapper.toDocumentEntity(result);
        DisclosureDocumentEntity saved = documentRepository.save(document);

        List<DisclosureRelationEntity> relations = new ArrayList<>();
        for (DisclosureRelation relation : relationsToSave) {
            relations.add(mapRelation(relation));
        }
        if (!relations.isEmpty()) {
            relationRepository.saveAll(relations);
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<DisclosureDocumentEntity> findByDocId(String docId) {
        return documentRepository.findById(docId);
    }

    @Transactional(readOnly = true)
    public List<DisclosureRelationEntity> findRelationsByDocId(String docId) {
        return relationRepository.findByOriginalDocumentDocIdOrRelatedDocumentDocId(docId, docId);
    }

    private List<DisclosureRelation> collectRelationsToSave(
            String docId,
            PreprocessResult result,
            List<DisclosureRelationEntity> existingRelations
    ) {
        Map<String, DisclosureRelation> relationsById = new LinkedHashMap<>();

        existingRelations.stream()
                .filter(relation -> !docId.equals(relation.getRelatedDocument().getDocId()))
                .map(mapper::toRelationModel)
                .forEach(relation -> relationsById.put(relation.relationId(), relation));

        for (DisclosureRelation relation : result.relations()) {
            if (!docId.equals(relation.relatedDocId())) {
                throw new IllegalArgumentException(
                        "relations.related_doc_id는 현재 전처리 문서와 같아야 합니다: "
                                + relation.relationId());
            }
            relationsById.put(relation.relationId(), relation);
        }

        return new ArrayList<>(relationsById.values());
    }

    private DisclosureRelationEntity mapRelation(DisclosureRelation relation) {
        DisclosureDocumentEntity original = documentRepository.findById(relation.originalDocId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "관계의 original_doc_id 문서가 없습니다: " + relation.originalDocId()));
        DisclosureDocumentEntity related = documentRepository.findById(relation.relatedDocId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "관계의 related_doc_id 문서가 없습니다: " + relation.relatedDocId()));
        return mapper.toRelationEntity(relation, original, related);
    }
}
