package com.chaltteok.disclosure.persistence.repository;

import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DisclosureDocumentRepository extends JpaRepository<DisclosureDocumentEntity, String> {

    // TODO: report_nm LIKE 검색은 자금조달 유형이 늘어나면(CB·EB·무상증자 등)
    // 확장성이 떨어짐. 파서가 이미 문서 유형을 판별하고 있으므로,
    // 구조화된 분류 필드(예: doc_subtype 재사용 또는 신규 필드 추가)로
    // 전환하는 걸 팀 논의 안건으로 남김. data-model.md 수정 필요
    // (schema_version 상향 포함), 팀원과 상의 후 결정.
    List<DisclosureDocumentEntity> findByCorpNameAndDocGroupAndReportNmContaining(
            String corpName,
            DocGroup docGroup,
            String keyword
    );

    List<DisclosureDocumentEntity> findByCorpNameAndDocGroupAndReportNmContainingAndRceptDt(
            String corpName,
            DocGroup docGroup,
            String keyword,
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
     * 질문에서 기업명을 식별하기 위한 후보 목록.
     * 코퍼스의 법인명을 코드에 하드코딩하지 않기 위해 적재된 값에서 가져온다.
     */
    @Query("select distinct d.corpName from DisclosureDocumentEntity d where d.docGroup = :docGroup")
    List<String> findDistinctCorpNamesByDocGroup(@Param("docGroup") DocGroup docGroup);
}
