package com.chaltteok.disclosure.persistence.repository;

import com.chaltteok.disclosure.persistence.entity.EvidenceChunkEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceChunkRepository extends JpaRepository<EvidenceChunkEntity, String> {

    List<EvidenceChunkEntity> findByDocumentDocIdOrderBySequenceAsc(String docId);
}
