package com.chaltteok.disclosure.persistence.repository;

import com.chaltteok.answer.CorpIdentity;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * 적재된 문서에서 법인 식별자만 뽑는다.
 *
 * <p>{@link DisclosureDocumentRepository}와 분리해 둔다. 질문의 기업명을 푸는 일은
 * 문서 검색과 관심사가 다르고, 공시 유형별 Handler가 모두 같은 목록을 쓴다.
 */
public interface CorpIdentityRepository extends Repository<DisclosureDocumentEntity, String> {

    /** 적재된 모든 법인. 문서 수와 무관하게 법인당 한 줄이다. */
    @Query("""
            select distinct new com.chaltteok.answer.CorpIdentity(
                d.corpName, d.listedName, d.stockCode, d.corpCode)
            from DisclosureDocumentEntity d
            """)
    List<CorpIdentity> findAllIdentities();
}
