package com.chaltteok.disclosure.persistence.repository;

import com.chaltteok.disclosure.persistence.entity.DisclosureRelationEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisclosureRelationRepository extends JpaRepository<DisclosureRelationEntity, String> {

    List<DisclosureRelationEntity> findByEventKey(String eventKey);

    List<DisclosureRelationEntity> findByOriginalDocumentDocIdOrRelatedDocumentDocId(
            String originalDocId,
            String relatedDocId
    );
}
