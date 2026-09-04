# 자기주식취득결정 파싱

- 대상: `doc_group=major`, 서식명에 `자기주식취득결정`(공백 포함 `자기주식 취득 결정` 포함)이 있는 문서. ACODE `11332`(구 서식 `11310`)
- 구현: `preprocess/chaltteok_etl/material/treasury_acquisition.py`, `TreasuryStockAnswerHandler`
- 처분·신탁 서식은 파싱하지 않는다

## 뽑는 항목

| 의미 | `fact_key` | 식별 |
| --- | --- | --- |
| 취득예정 보통주식 수 | `treasury_planned_common_shares` | ACODE `ACQ_OSTK`·`AQPLN_STK_OSTK` 또는 라벨 `취득예정주식`(기타주식 제외) |
| 취득예정금액 | `treasury_planned_common_amount` | ACODE `ACQ_OSTK_PRC`·`AQPLN_PRC_OSTK` 또는 라벨 `취득예정금액` |

같은 `fact_key`가 표에 여러 번 있으면 처음 값만 남긴다. `"-"`는 사실을 만들지 않는다.

## `/answer`

기업명과 공시일(`yyyy년 M월 d일`)로 문서를 특정한다. 같은 날 정정본이 있으면 정정본만 쓴다.
질문은 `자기주식`과 `취득`이 함께 있어야 이 Handler가 받는다. `처분`·`신탁`이 있으면 거절한다.
`취득 예정 금액`과 `취득예정금액`, `취득 예정 주식 수`와 `취득예정주식`은 같은 항목이다. 공백을 접어 읽는다.
항목을 안 적은 질문은 예정 주식 수와 금액을 함께 본다.
기업명은 다른 공시 유형과 같이 약칭·음차·종목코드도 받는다.
