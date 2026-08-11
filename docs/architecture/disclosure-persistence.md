# 공시 전처리 결과 영속화 설계

- 문서 상태: Draft v0.1
- 적용 범위: `PreprocessResult` → PostgreSQL 저장·조회
- 관련 문서: `docs/data-model.md`
- 최종 수정일: 2026-08-10

## 1. 목적

Python 전처리 출력(`PreprocessResult`)을 Spring Data JPA로 PostgreSQL에 저장하고, 이후 검색·답변 흐름에서 다시 조회할 수 있게 한다.

논리 모델(`disclosure/model`)과 JPA Entity(`disclosure/persistence/entity`)는 분리한다. Jackson 역직렬화용 record에 `@Entity`를 붙이지 않는다.

## 2. 테이블명과 기본키

| 논리 모델 | 테이블명 | 기본키 | 비고 |
| --- | --- | --- | --- |
| `DisclosureDocument` (+ `schema_version`, `parser_version`) | `disclosure_document` | `doc_id` (자연키, VARCHAR) | 전처리 `doc_id`를 PK로 사용 |
| `EvidenceChunk` | `evidence_chunk` | `chunk_id` (자연키) | |
| `ExtractedFact` | `extracted_fact` | `fact_id` (자연키) | |
| `DisclosureRelation` | `disclosure_relation` | `relation_id` (자연키) | |
| `ChangedField` | `changed_field` | `id` (BIGINT, IDENTITY) | 모델에 식별자가 없어 대리키 사용 |
| 관계의 `evidence_chunk_ids` | `disclosure_relation_evidence_chunk` | `(relation_id, chunk_id)` | `@ElementCollection` 조인 테이블 |

테이블명은 snake_case 단수형으로 통일한다. JSON 필드명과 동일한 의미를 유지하되, DB 식별자는 PostgreSQL 관례에 맞춘다.

## 3. 외래키 관계

```text
disclosure_document (doc_id)
        ↑
        │ ManyToOne
evidence_chunk (doc_id → disclosure_document.doc_id)

disclosure_document (doc_id)
        ↑
        │ ManyToOne
extracted_fact (doc_id → disclosure_document.doc_id)
        │ ManyToOne
        ↓
evidence_chunk (chunk_id)

disclosure_document (doc_id)  ←── original_doc_id ──┐
disclosure_document (doc_id)  ←── related_doc_id ───┤
                                                    │
                                    disclosure_relation
                                            ↑
                                            │ OneToMany / ManyToOne
                                    changed_field (relation_id)

disclosure_relation
        │ ElementCollection
        ↓
disclosure_relation_evidence_chunk (relation_id, chunk_id)
```

요약:

- `EvidenceChunk` → `DisclosureDocument` (ManyToOne, 필수)
- `ExtractedFact` → `DisclosureDocument` (ManyToOne, 필수)
- `ExtractedFact` → `EvidenceChunk` (ManyToOne, 필수)
- `DisclosureRelation` → 최초 공시(`original_doc_id`), 후속 공시(`related_doc_id`) 각각 `DisclosureDocument` 참조 (ManyToOne, 필수)
- `ChangedField` → `DisclosureRelation` (ManyToOne, 필수)
- 관계의 근거 chunk ID 목록은 문자열 컬렉션으로 저장한다. chunk 행이 아직 없거나 교차 문서 근거일 수 있어 FK는 강제하지 않는다.

문서 삭제 시 `evidence_chunk`·`extracted_fact`는 orphanRemoval로 함께 제거한다. `disclosure_relation`은 두 문서를 가리키므로 문서 upsert 전에 해당 `doc_id`를 참조하는 관계를 임시로 삭제한 뒤 아래 재처리 정책에 따라 복구한다.

한 `PreprocessResult`의 `relations`는 해당 전처리 문서가 정정·변경·해지·후속 공시인 관계만 소유한다. 따라서 각 관계의 `related_doc_id`는 `document.doc_id`와 같아야 한다.

## 4. Enum 저장 방식

모든 Enum 컬럼은 `@Enumerated(EnumType.STRING)`으로 저장한다.

| 컬럼 | Java 타입 | 저장 예 |
| --- | --- | --- |
| `doc_group` | `DocGroup` | `PERIODIC` |
| `manifest_file_format` | `ManifestFileFormat` | `XML` |
| `content_type` | `ContentType` | `TABLE` |
| `source_format` | `SourceFormat` | `DART_XML` |
| `value_type` | `ValueType` | `NUMBER` |
| `period_basis` | `PeriodBasis` | `POINT_IN_TIME` |
| `scope` | `Scope` | `CONSOLIDATED` |
| `relation_type` | `RelationType` | `CORRECTION` |

JSON 직렬화 값(소문자 `periodic` 등)과 DB 저장 값(Enum 상수명 `PERIODIC`)은 다를 수 있다. DB에는 Java Enum 상수명을 그대로 두어 가독성과 Hibernate 기본 매핑을 맞춘다.

## 5. 삭제·재처리 정책

**같은 `doc_id` 재저장 시: upsert(교체).**

동작:

1. 해당 `doc_id`가 `original_doc_id`인 기존 관계는 다른 후속 공시가 소유한 관계이므로 복구 대상으로 보존한다.
2. 해당 `doc_id`가 `related_doc_id`인 기존 관계는 현재 전처리 결과가 소유하므로 새 `relations`로 교체한다.
3. FK 제약을 지키기 위해 해당 문서를 참조하는 관계를 임시로 삭제한다.
4. 기존 `disclosure_document`를 삭제한다. cascade·orphanRemoval로 chunk·fact가 함께 제거된다.
5. 새 `PreprocessResult`를 Entity로 변환해 다시 insert한다.
6. 1번에서 보존한 관계와 새 `relations`를 다시 저장한다.

신규 insert만 하지 않는 이유:

- `doc_id`는 코퍼스 문서의 안정적인 자연키다. 재처리마다 새 행을 만들면 동일 공시가 중복되고 검색·이력 판단이 깨진다.
- 파서 버전·chunk 분할이 바뀌면 하위 행 일부만 갱신하기보다 문서 단위로 교체하는 편이 일관되다.
- PK를 `doc_id`로 쓰므로 단순 insert는 두 번째 적재에서 충돌한다.
- 원본 공시를 재처리하더라도 이미 저장된 정정·후속 관계는 다른 문서의 결과이므로 삭제하지 않는다.

관계 저장 전제:

- `original_doc_id`·`related_doc_id`가 가리키는 문서 행이 이미 있어야 FK가 성공한다.
- 적재 순서는 원본 공시를 먼저 저장한 뒤 정정·후속 공시를 저장한다.

## 6. 기본 인덱스

| 테이블 | 인덱스 | 컬럼 | 목적 |
| --- | --- | --- | --- |
| `disclosure_document` | `idx_disclosure_document_corp_code` | `corp_code` | 기업 코드 검색 |
| `disclosure_document` | `idx_disclosure_document_corp_name` | `corp_name` | 기업명 검색 |
| `disclosure_document` | `idx_disclosure_document_doc_group` | `doc_group` | 공시 유형 필터 |
| `disclosure_document` | `idx_disclosure_document_rcept_dt` | `rcept_dt` | 공시일 범위·정렬 |
| `evidence_chunk` | `idx_evidence_chunk_doc_id` | `doc_id` | 문서별 근거 조회 |
| `extracted_fact` | `idx_extracted_fact_fact_key` | `fact_key` | 구조화 사실 키 조회 |
| `extracted_fact` | `idx_extracted_fact_doc_id` | `doc_id` | 문서별 사실 조회 |
| `extracted_fact` | `idx_extracted_fact_chunk_id` | `chunk_id` | 근거별 사실 조회 |
| `disclosure_relation` | `idx_disclosure_relation_event_key` | `event_key` | 이벤트 단위 이력 조회 |
| `disclosure_relation` | `idx_disclosure_relation_original_doc_id` | `original_doc_id` | 원본 공시의 후속 관계 조회 |
| `disclosure_relation` | `idx_disclosure_relation_related_doc_id` | `related_doc_id` | 후속 공시의 관계 조회 |
| `changed_field` | `idx_changed_field_relation_id` | `relation_id` | 관계별 변경 필드 조회 |
| `disclosure_relation_evidence_chunk` | `idx_disclosure_relation_evidence_relation_id` | `relation_id` | 관계별 근거 ID 조회 |

PostgreSQL은 참조하는 쪽의 FK 인덱스를 자동 생성하지 않으므로, 현재 저장·조회 경로에서 사용하는 FK 컬럼에는 위 인덱스를 명시적으로 생성한다.

## 7. 패키지·계층

```text
disclosure/
  model/                 # PreprocessResult 등 Jackson 순수 모델 (기존)
  persistence/
    entity/              # JPA Entity
    repository/          # Spring Data JPA Repository
    DisclosurePersistenceService  # PreprocessResult → Entity 변환·저장
    PreprocessResultMapper        # 모델 ↔ Entity 매핑
```

적재 진입점은 `DisclosurePersistenceService.save(PreprocessResult)`다. 저장 후 Repository로 문서·근거·사실·관계를 다시 조회할 수 있어야 한다.

## 8. 스키마 생성·빈 활성화

- 로컬: `application-local.yml`에서 `spring.jpa.hibernate.ddl-auto=update` 권장(예시 파일에 기록).
- 통합 테스트: Testcontainers PostgreSQL + `ddl-auto=create`.
- `chaltteok.disclosure.persistence.enabled=true`일 때만 `DisclosurePersistenceService` 빈을 등록한다. DB 없는 단위 테스트는 `false`로 둔다.
- 운영용 Flyway/Liquibase 마이그레이션은 이후 단계에서 도입한다. H2 등 인메모리 DB는 사용하지 않는다.

## 9. 이번 단계에서 하지 않는 것

- 공시 유형별 검색 조건·질문 분석
- 계산·비교·HCX·`/answer` 연결
- 실제 XML·HTML·PDF 파싱
- pgvector 임베딩 컬럼
