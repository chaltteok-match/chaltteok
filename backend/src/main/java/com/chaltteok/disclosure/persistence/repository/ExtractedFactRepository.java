package com.chaltteok.disclosure.persistence.repository;

import com.chaltteok.disclosure.persistence.entity.ExtractedFactEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtractedFactRepository extends JpaRepository<ExtractedFactEntity, String> {

    List<ExtractedFactEntity> findByDocumentDocId(String docId);

    List<ExtractedFactEntity> findByFactKey(String factKey);
}
