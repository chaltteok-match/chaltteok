"""거래소공시 정정본을 원본과 잇는다.

파서는 문서 한 건만 보므로 원본이 어느 문서인지 알 수 없다. 정정본이 가진 것은
`2. 정정관련 공시서류제출일`이라는 **날짜**뿐이고, 이를 문서로 바꾸려면 같은 기업의
다른 공시를 함께 봐야 한다. 그래서 전량을 읽은 뒤 이 단계에서 관계를 만든다.

관계를 확정할 수 없으면 추측해서 잇지 않는다(docs/data-model.md §6).
정정본 563건 중 242건은 원본이 2023-01-01 이전이라 코퍼스에 없다. **정상이다.**
"""

from __future__ import annotations

from collections import defaultdict
from datetime import date

from chaltteok_etl.common.models import (
    ChangedField,
    DisclosureRelation,
    PreprocessResult,
    RelationType,
)

from .krx_forms import resolve_form

TARGET_FILING_DATE_KEY = "correction_target_filing_date"
COUNTERPARTY_KEYS = ("contract_counterparty", "termination_counterparty")

# 관계의 변경 목록에서 뺀다. 정정 머리 항목은 계약 내용이 아니라 정정 자체의 정보다.
_CORRECTION_META_PREFIX = "correction_"


def link_corrections(results: list[PreprocessResult]) -> list[PreprocessResult]:
    """정정본에 원본과의 관계를 붙이고, 원본이 먼저 오도록 정렬해 돌려준다.

    적재는 관계의 두 문서가 이미 저장돼 있어야 하므로(``DisclosurePersistenceService``),
    `(rcept_dt, rcept_no)` 순으로 내보낸다. 정정본은 원본보다 뒤에 있다(§6).
    """
    by_corp_and_date: dict[tuple[str, date], list[PreprocessResult]] = defaultdict(list)
    for result in results:
        document = result.document
        by_corp_and_date[(document.corp_name, document.rcept_dt)].append(result)

    # 앞선 문서부터 처리해야 사슬의 뿌리를 알 수 있다.
    ordered = sorted(results, key=lambda item: (item.document.rcept_dt, item.document.rcept_no))

    # doc_id → 사건 키. 정정의 정정이면 맨 처음 원본을 사건 키로 쓴다.
    event_keys: dict[str, str] = {}
    linked: list[PreprocessResult] = []
    for result in ordered:
        original = _find_original(result, by_corp_and_date)
        if original is None:
            linked.append(result)
            continue
        original_id = original.document.doc_id
        event_key = event_keys.get(original_id, original_id)
        event_keys[result.document.doc_id] = event_key
        linked.append(
            result.model_copy(
                update={"relations": [_relation(original, result, event_key)]}
            )
        )
    return linked


def _find_original(
    correction: PreprocessResult,
    by_corp_and_date: dict[tuple[str, date], list[PreprocessResult]],
) -> PreprocessResult | None:
    """정정본이 가리키는 원본을 찾는다. 확정하지 못하면 ``None``."""
    if not correction.document.is_correction:
        return None
    target_date = _fact_date(correction, TARGET_FILING_DATE_KEY)
    if target_date is None:
        return None

    candidates = [
        candidate
        for candidate in by_corp_and_date.get((correction.document.corp_name, target_date), [])
        if candidate.document.doc_id != correction.document.doc_id
        and _same_form(candidate, correction)
        and _is_before(candidate, correction)
    ]
    if len(candidates) > 1:
        candidates = _narrow(candidates, correction)
    return candidates[0] if len(candidates) == 1 else None


def _narrow(
    candidates: list[PreprocessResult], correction: PreprocessResult
) -> list[PreprocessResult]:
    """같은 날 여러 건이면 후보를 좁힌다. 그래도 남으면 잇지 않는다."""
    # 그날 새로 맺은 계약을 먼저 본다. 같은 날 접수된 정정본은 앞선 다른 계약을 고친 것이라
    # 그날의 계약이 아니다. 대우건설 2023-06-30에는 새 계약과 2023-03-10 계약의 정정본이
    # 함께 있고, 2023-06-30을 가리키는 정정은 새 계약을 고친 것이다.
    #
    # 정정의 정정은 예외다. 직전 정정본만 있는 날을 가리키므로 원본만 남기면 사슬이 끊긴다.
    originals = [item for item in candidates if not item.document.is_correction]
    if originals:
        candidates = originals

    if len(candidates) > 1:
        counterparty = _counterparty(correction)
        if counterparty is not None:
            candidates = [
                item for item in candidates if _counterparty(item) == counterparty
            ]
    return candidates


def _relation(
    original: PreprocessResult, correction: PreprocessResult, event_key: str
) -> DisclosureRelation:
    original_id = original.document.doc_id
    related_id = correction.document.doc_id
    return DisclosureRelation(
        relation_id=f"{original_id}:{related_id}:{RelationType.CORRECTION.value}",
        # 같은 사건을 묶는 키. 사슬의 맨 처음 원본이다.
        # 정정의 정정(현대건설 20230213 → 20230411 → 20230414)을 한 사건으로 묶으려면
        # 직전 문서가 아니라 뿌리를 써야 한다.
        event_key=event_key,
        original_doc_id=original_id,
        related_doc_id=related_id,
        relation_type=RelationType.CORRECTION,
        # 계약 정정에는 추적할 상태가 없다. 지분공시처럼 보고 의무가 바뀌는 경우에만 쓴다.
        previous_status=None,
        current_status=None,
        changed_fields=_changed_fields(original, correction),
        evidence_chunk_ids=_evidence_chunk_ids(correction),
    )


def _changed_fields(
    original: PreprocessResult, correction: PreprocessResult
) -> list[ChangedField]:
    """두 문서에 모두 있고 값이 달라진 항목만 남긴다."""
    before = {fact.fact_key: fact for fact in original.extracted_facts}
    changed: list[ChangedField] = []
    for fact in correction.extracted_facts:
        if fact.fact_key.startswith(_CORRECTION_META_PREFIX):
            continue
        previous = before.get(fact.fact_key)
        if previous is None:
            continue
        before_value = previous.normalized_value or previous.raw_value
        after_value = fact.normalized_value or fact.raw_value
        if before_value == after_value:
            continue
        changed.append(
            ChangedField(
                fact_key=fact.fact_key,
                before_value=before_value,
                after_value=after_value,
                # 정규화 단위가 서로 다르면 공통 단위가 없다.
                unit=fact.normalized_unit
                if fact.normalized_unit == previous.normalized_unit
                else None,
            )
        )
    return changed


def _evidence_chunk_ids(correction: PreprocessResult) -> list[str]:
    """관계 판단에 쓴 근거. 정정 머리 항목이 없으면 첫 chunk라도 남긴다(최소 1개)."""
    by_chunk = {fact.chunk_id: fact for fact in correction.extracted_facts}
    ids = [
        chunk.chunk_id
        for chunk in correction.evidence_chunks
        if chunk.chunk_id in by_chunk
        and by_chunk[chunk.chunk_id].fact_key.startswith(_CORRECTION_META_PREFIX)
    ]
    return ids or [correction.evidence_chunks[0].chunk_id]


def _fact_date(result: PreprocessResult, fact_key: str) -> date | None:
    for fact in result.extracted_facts:
        if fact.fact_key == fact_key and fact.normalized_value:
            return date.fromisoformat(fact.normalized_value)
    return None


def _counterparty(result: PreprocessResult) -> str | None:
    for fact in result.extracted_facts:
        if fact.fact_key in COUNTERPARTY_KEYS:
            return fact.raw_value
    return None


def _same_form(left: PreprocessResult, right: PreprocessResult) -> bool:
    return (
        resolve_form(left.document.report_nm).name
        == resolve_form(right.document.report_nm).name
    )


def _is_before(candidate: PreprocessResult, correction: PreprocessResult) -> bool:
    """원본은 정정본보다 `(rcept_dt, rcept_no)` 순서상 앞에 있어야 한다(§6)."""
    return (candidate.document.rcept_dt, candidate.document.rcept_no) < (
        correction.document.rcept_dt,
        correction.document.rcept_no,
    )
