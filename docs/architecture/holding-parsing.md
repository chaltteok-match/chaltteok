# 대량보유상황보고서 파싱

- 대상: `doc_group=holding`, 서식명에 `대량보유상황보고서`가 있는 문서
- 구현: `preprocess/chaltteok_etl/ownership/large_holding.py`, `OwnershipAnswerHandler`

## 뽑는 항목

| 의미 | `fact_key` | 식별 |
| --- | --- | --- |
| 대표보고자 | `ownership_holder` | ACODE `RPROR` 또는 라벨 `대표보고자`·`보고자` |
| 보유 주식 수 | `ownership_shares` | ACODE `STKQY` 또는 라벨 `보유주식` |
| 지분율 | `ownership_ratio` | ACODE `STKRT` 또는 라벨 `보유비율`·`지분율` |
| 보고구분 | `ownership_report_type` | 신규·변경·해소 등 |
| 보유목적·보고사유 | `ownership_purpose` | 텍스트 |

같은 `fact_key`가 표에 여러 번 있으면 처음 값만 남긴다. `"-"`는 사실을 만들지 않는다.

## `/answer`

기업명과 공시일(`yyyy년 M월 d일`)로 문서를 특정한다. 같은 날 정정본이 있으면 정정본만 쓴다.
신규·변경·해소를 사건 키로 잇는 이력 추적은 하지 않는다.

질문은 `대량보유`, `지분공시`, `보유상황`처럼 서식을 가리키는 표현이 있어야 이 Handler가 받는다.
`지분율`만 있는 정기공시 질문은 받지 않는다.
