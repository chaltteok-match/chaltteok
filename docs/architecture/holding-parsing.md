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
`어떻게 바뀌었나`·`변경된 내역`처럼 과정을 물으면 같은 보고자의 신규·변동·변경을 한 사건으로 잇는다.
코퍼스 보고구분 `변동`은 보유 비율이 1%p 이상 바뀐 보고라 `변경`과 같이 사슬에 넣는다. DART 대량보유상황보고서에 `해소` 값은 없다.
공시일이 있으면 그 날이 들어 있는 사건만 쓰고, 없으면 사건이 하나일 때만 답한다.
보고자가 다르거나 신규가 다시 나오면 새 사건이다. 보고구분이 없으면 앞 보고에 붙이지 않는다.
주식 수·지분율이 그대로인 단계는 접고, 값이 끝까지 같으면 처음과 마지막만 남긴다.
접고 난 뒤에도 단계가 15개를 넘으면 처음·마지막과 지분율 변동이 큰 단계만 남긴다.
`보유목적이 어떻게 바뀌었나`는 같은 사슬에서 보유목적 문장이 바뀐 단계를 남긴다. 목적이 끝까지 같으면 처음과 마지막만 남긴다. 지분율·주식 수 이력은 수량 기준으로 접는다. 보고사유 칸은 보유목적으로 쓰지 않는다. `/answer`에서 `보고사유`를 물으면 보유목적 fact로 바꾸지 않고, 해당 칸을 뽑지 않으므로 답변 불가다.

질문은 `대량보유`, `지분공시`, `보유상황`처럼 서식을 가리키는 표현이 있어야 이 Handler가 받는다.
`지분율`만 있는 정기공시 질문은 받지 않는다. 기업명은 다른 공시 유형과 같이 약칭·음차·종목코드도 받는다.

## 전처리와 적재

파서는 문서마다 `relations`를 비운다. 신규·변경 사슬은 JSONL이 아니라 Handler가 잇는다.
그래서 적재 줄 순서 때문에 원본이 없다고 실패하지는 않는다.

```bash
cd preprocess
python -m chaltteok_etl --corpus ../corpus --out ../corpus/preprocessed_holding.jsonl --doc-group holding
cd ../backend
./gradlew bootRun --args='--spring.profiles.active=local,load --chaltteok.load.path=/절대/경로/corpus/preprocessed_holding.jsonl'
```

산출물 JSONL은 Git에 올리지 않는다. 명령과 HOLDING만 지우는 SQL은 `backend/README.md`에 있다.
코퍼스 대량보유는 70개사 1,083건이다. 로컬에서 실패 없이 전처리·적재하면 문서 1,083건이 된다.
