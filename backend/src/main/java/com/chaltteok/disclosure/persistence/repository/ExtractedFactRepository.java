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

    /**
     * 여러 문서에서 같은 항목을 한 번에 읽는다.
     *
     * <p>거래소공시처럼 문서 한 건이 사건 한 건인 유형은, 최댓값을 찾거나 기간을 훑을 때
     * 문서 수만큼 조회하면 질의가 수백 번 나간다.
     */
    @Query("""
            select f from ExtractedFactEntity f
            join fetch f.evidenceChunk
            join fetch f.document
            where f.document.docId in :docIds
              and f.factKey = :factKey
            """)
    List<ExtractedFactEntity> findForAnswerByDocIds(
            @Param("docIds") List<String> docIds,
            @Param("factKey") String factKey);
}
