package com.chaltteok.disclosure.persistence.repository;

import com.chaltteok.disclosure.model.enums.DocGroup;
import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
