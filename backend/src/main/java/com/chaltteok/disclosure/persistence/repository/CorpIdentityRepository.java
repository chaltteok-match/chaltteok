package com.chaltteok.disclosure.persistence.repository;

import com.chaltteok.answer.CorpIdentity;
import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 적재된 문서에서 법인 식별자만 뽑는다.
 *
 * <p>{@link DisclosureDocumentRepository}와 분리해 둔다. 질문의 기업명을 푸는 일은
 * 문서 검색과 관심사가 다르고, 공시 유형별 Handler가 모두 같은 방식으로 쓴다.
 *
 * <p>Handler마다 자기가 답할 수 있는 공시 유형으로 좁혀서 가져간다. 유형을 넓히면
 * 문서가 없는 기업까지 {@code supports()}가 참이 되어 Handler끼리 질문을 뺏는다.
 */
public interface CorpIdentityRepository extends Repository<DisclosureDocumentEntity, String> {

    /** 해당 공시 유형이 적재된 법인. 문서 수와 무관하게 법인당 한 줄이다. */
    @Query("""
            select distinct new com.chaltteok.answer.CorpIdentity(
                d.corpName, d.listedName, d.stockCode, d.corpCode)
            from DisclosureDocumentEntity d
            where d.docGroup = :docGroup
            """)
    List<CorpIdentity> findByDocGroup(@Param("docGroup") DocGroup docGroup);

    /** 해당 서식이 적재된 법인. 유상증자 질문에 자기주식만 있는 기업을 넣지 않기 위해서다. */
    @Query("""
            select distinct new com.chaltteok.answer.CorpIdentity(
                d.corpName, d.listedName, d.stockCode, d.corpCode)
            from DisclosureDocumentEntity d
            where d.docGroup = :docGroup and d.docSubtype = :docSubtype
            """)
    List<CorpIdentity> findByDocGroupAndDocSubtype(
            @Param("docGroup") DocGroup docGroup,
            @Param("docSubtype") String docSubtype);
}
