package com.chaltteok.disclosure.persistence.repository;

import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DisclosureDocumentRepository extends JpaRepository<DisclosureDocumentEntity, String> {

    /**
     * 기업·공시유형·서식명으로 문서를 찾는다.
     *
     * <p>{@code report_nm}은 절반가량에 {@code [기재정정]} 접두가 붙고
     * {@code 자기주식취득결정}과 {@code 자기주식처분결정}처럼 이름이 겹쳐서 검색 키로 쓰기 어렵다.
     * 전처리가 공시명에서 뽑아 둔 {@code doc_subtype}으로 찾는다(docs/data-model.md §3).
     */
    List<DisclosureDocumentEntity> findByCorpNameAndDocGroupAndDocSubtype(
            String corpName,
            DocGroup docGroup,
            String docSubtype
    );

    /** 같은 서식이 여러 날 제출되므로 공시일까지 좁힌다. */
    List<DisclosureDocumentEntity> findByCorpNameAndDocGroupAndDocSubtypeAndRceptDt(
            String corpName,
            DocGroup docGroup,
            String docSubtype,
            LocalDate rceptDt
    );

    /**
     * 기업·공시유형·기준연도로 정기공시를 찾는다.
     *
     * <p>같은 기준연도에 사업·반기·분기보고서와 기재정정본이 함께 존재할 수 있으므로
     * 접수일·접수번호 역순으로 정렬해 최신 공시를 먼저 돌려준다. 어느 문서를 쓸지는
     * 호출하는 쪽이 정한다.
     */
    List<DisclosureDocumentEntity> findByCorpNameAndDocGroupAndBaseYearOrderByRceptDtDescRceptNoDesc(
            String corpName, DocGroup docGroup, Integer baseYear);

    /** 기준연월까지 지정해 특정 보고서를 찾는다. */
    List<DisclosureDocumentEntity> findByCorpNameAndDocGroupAndBaseYearAndBaseMonthOrderByRceptDtDescRceptNoDesc(
            String corpName, DocGroup docGroup, Integer baseYear, Integer baseMonth);

    /**
     * 기업의 거래소공시를 접수일 역순으로 찾는다.
     *
     * <p>거래소공시는 사건 공시라 기준연도가 없고, 한 기업이 같은 서식을 수백 건 낸다
     * (대우건설 104건). 어느 문서를 쓸지는 호출하는 쪽이 질문의 공시일로 좁힌다.
     */
    List<DisclosureDocumentEntity> findByCorpNameAndDocGroupOrderByRceptDtDescRceptNoDesc(
            String corpName, DocGroup docGroup);

    /** 같은 날 여러 건이 접수될 수 있어 목록으로 돌려준다. */
    List<DisclosureDocumentEntity> findByCorpNameAndDocGroupAndRceptDtOrderByRceptNoDesc(
            String corpName, DocGroup docGroup, LocalDate rceptDt);
}
