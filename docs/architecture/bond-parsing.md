# 전환·교환사채권 발행결정 파싱

- 대상: `doc_group=major`, 서식명 `전환사채권발행결정`(ACODE `11324`), `교환사채권발행결정`(ACODE `11326`). 공시명에 공백이 있으면(`전환사채권 발행결정`) 검색 키는 공백을 뺀다.
- 구현: `preprocess/chaltteok_etl/material/bond_issuance.py`, `BondIssuanceAnswerHandler`
- 표 읽기: `preprocess/chaltteok_etl/common/dart_xml.py`

코퍼스 기준으로 신주인수권부사채(BW) 서식은 0건이다. 주요사항보고서 시설투자 서식도 0건이다. 거래소공시 `신규시설투자등`은 이 파서가 다루지 않는다. 자기전환사채매도·제3자 전환권 행사 서식은 거절한다.

정정·조건 변경 이력은 잇지 않는다.

## 뽑는 항목

같은 TE ACODE가 전환·교환에 같이 쓰인다. 값이 `"-"`이거나 `해당사항 없음`이면 사실을 만들지 않는다.

| 의미 | `fact_key` | 식별 |
| --- | --- | --- |
| 권면총액 | `bond_face_amount` | ACODE `DNM_SUM` 또는 라벨 `권면`+`총액`(해외·한도 제외) |
| 표면이자율 | `bond_coupon_rate` | ACODE `PRFT_RATE` 또는 라벨 `표면이자율` |
| 사채만기일 | `bond_maturity_date` | ACODE `EXP_DT` 또는 라벨 `사채만기일`. 텍스트로 두고 정규화하지 않는다 |
| 전환가액·교환가액 | `bond_exercise_price` | ACODE `EXE_PRC` 또는 라벨 `전환가액`/`교환가액`(결정방법·조정 제외) |
| 대상 주식 수 | `bond_exercise_shares` | ACODE `STK_CNT` 또는 라벨 `주식수`(대비·총수 제외) |

같은 `fact_key`가 표에 여러 번 있으면 처음 값만 남긴다.

## `/answer`

기업명과 공시일(`yyyy년 M월 d일`)로 문서를 특정한다. 같은 날 정정본이 있으면 정정본만 쓴다.
질문은 `전환사채` 또는 `교환사채`가 있어야 이 Handler가 받는다. `유상증자`·`자기주식`·`신규시설투자`·`공급계약`·`신주인수권`·자기전환·제3자 전환은 받지 않는다.
항목을 특정하지 않으면 권면총액과 전환/교환가액을 함께 근거로 둔다.
