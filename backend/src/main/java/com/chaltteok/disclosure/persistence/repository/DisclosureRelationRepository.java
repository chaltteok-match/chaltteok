package com.chaltteok.disclosure.persistence.repository;

import com.chaltteok.disclosure.persistence.entity.DisclosureRelationEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DisclosureRelationRepository extends JpaRepository<DisclosureRelationEntity, String> {

    List<DisclosureRelationEntity> findByEventKey(String eventKey);

    List<DisclosureRelationEntity> findByOriginalDocumentDocIdOrRelatedDocumentDocId(
            String originalDocId,
            String relatedDocId
    );

    /**
     * 여러 문서에 걸린 관계를 한 번에 읽는다.
     *
     * <p>어느 문서가 뒤에 정정됐는지 가리려면 후보 전체의 관계가 필요하다. 문서마다
     * 조회하면 거래소공시처럼 후보가 수백 건인 유형에서 질의가 그만큼 나간다.
     */
    @Query("""
            select r from DisclosureRelationEntity r
            join fetch r.originalDocument
            join fetch r.relatedDocument
            where r.originalDocument.docId in :docIds
               or r.relatedDocument.docId in :docIds
            """)
    List<DisclosureRelationEntity> findByDocIds(@Param("docIds") List<String> docIds);

    /** 한 사건의 정정 사슬 전체. 사건 키는 사슬의 맨 처음 원본이다. */
    @Query("""
            select r from DisclosureRelationEntity r
            join fetch r.originalDocument
            join fetch r.relatedDocument
            where r.eventKey = :eventKey
            order by r.relatedDocument.rceptDt asc, r.relatedDocument.rceptNo asc
            """)
    List<DisclosureRelationEntity> findChainByEventKey(@Param("eventKey") String eventKey);
}
