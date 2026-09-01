package com.chaltteok.disclosure.persistence.repository;

import com.chaltteok.disclosure.model.enums.Scope;
import com.chaltteok.disclosure.persistence.entity.ExtractedFactEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtractedFactRepository extends JpaRepository<ExtractedFactEntity, String> {

    List<ExtractedFactEntity> findByDocumentDocId(String docId);

    List<ExtractedFactEntity> findByFactKey(String factKey);

    /**
     * 답변 근거 조립용 조회. 근거 문장과 공시 메타데이터를 함께 읽어야 하므로
     * chunk와 document를 fetch join 한다.
     */
    @Query("""
            select f from ExtractedFactEntity f
            join fetch f.evidenceChunk
            join fetch f.document
            where f.document.docId = :docId
              and f.factKey = :factKey
              and f.scope = :scope
            """)
    List<ExtractedFactEntity> findForAnswer(
            @Param("docId") String docId,
            @Param("factKey") String factKey,
            @Param("scope") Scope scope);
}
