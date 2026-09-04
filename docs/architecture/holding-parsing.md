# 대량보유상황보고서 파싱

- 대상: `doc_group=holding`, 서식명에 `대량보유상황보고서`가 있는 문서
- 구현: `preprocess/chaltteok_etl/ownership/large_holding.py`, `OwnershipAnswerHandler`
- 표 읽기: `preprocess/chaltteok_etl/common/dart_xml.py` (`TE`와 `TU`)

`common/dart_xml.py`와 `periodic/dart_xml.py`는 이름이 같아도 API가 다르다. 전자는 ElementTree로 표 칸을 읽고, 후자는 정규식으로 큰 본문을 자른다. `material/paid_in_capital.py`는 아직 자체 ElementTree를 쓴다. 지금 합치지 않는다. 나중에 유상증자도 공통 모듈로 옮기면 된다.

## 뽑는 항목

코퍼스 1,083건에는 예전에 가정한 `RPROR`·`STKQY`·`STKRT`·`HLD_PURP`가 없다. 답해야 할 값은 아래 칸이다.

| 의미 | `fact_key` | 식별 |
| --- | --- | --- |
| 보고자 | `ownership_holder` | ACODE `RPT_RSP_NM` (라벨 `대표보고자`·`보고자`) |
| 이번 보고서 보유 주식 수 | `ownership_shares` | ACODE `THS_STK_CNT` |
| 이번 보고서 보유비율 | `ownership_ratio` | ACODE `THS_STK_RT` |
| 보고구분 | `ownership_report_type` | `TU` `AUNIT=RPT_DST1` |
| 보유목적 | `ownership_purpose` | `TU` `AUNIT=PUR_OWN` |

보고구분·보유목적은 `<TE>`가 아니라 `<TU>`에 있다. `iter_te_cells`가 둘 다 읽는다.

쓰지 않는 칸:

| 칸 | 이유 |
| --- | --- |
| `BFR_STK_CNT` / `BFR_STK_RT` | 직전 보고서 |
| `SUB_LRT` | 최대주주 지분율. 보고자 보유비율이 아님 |
| 라벨 `보고사유` | 보유목적(`PUR_OWN`, 예: 단순투자)과 다름 |

같은 `fact_key`가 표에 여러 번 있으면 ACODE로 고른 칸을 라벨 폴백보다 우선한다.
라벨만 있을 때는 `직전`·`최대주주` 칸을 건너고, 한 행에 직전·이번이 같이 있으면 `이번` 열을 쓴다.
`"-"`는 사실을 만들지 않는다.

합성 픽스처용으로 `RPROR`·`STKQY`·`HLD_PURP` 매핑은 남겨 둔다. `STKRT`는 코퍼스에서 개별 보유자 비율로 쓰이므로 지분율 키에 넣지 않는다.

## `/answer`

기업명과 공시일(`yyyy년 M월 d일`)로 문서를 특정한다. 같은 날 정정본이 있으면 정정본만 쓴다.
`어떻게 바뀌었나`·`변경된 내역`·`해소`처럼 과정을 물으면 같은 보고자의 신규·변경·해소를 한 사건으로 잇는다.
공시일이 있으면 그 날이 들어 있는 사건만 쓰고, 없으면 사건이 하나일 때만 답한다.
보고자가 다르거나 신규가 다시 나오면 새 사건이다. 보고구분이 없으면 앞 보고에 붙이지 않는다.
주식 수·지분율이 그대로인 단계는 접고, 값이 끝까지 같으면 처음과 마지막만 남긴다.
보유목적 문장만 바뀐 이력은 다루지 않는다.

질문은 `대량보유`, `지분공시`, `보유상황`처럼 서식을 가리키는 표현이 있어야 이 Handler가 받는다.
`지분율`만 있는 정기공시 질문은 받지 않는다. 기업명은 다른 공시 유형과 같이 약칭·음차·종목코드도 받는다.
