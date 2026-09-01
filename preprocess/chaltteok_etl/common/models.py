"""Pydantic models for the Python preprocessing output contract.

The JSON representation of these models follows ``docs/data-model.md`` and is
consumed by the Spring Boot ``PreprocessResult`` model.
"""

from __future__ import annotations

import re
import unicodedata
from datetime import date
from decimal import Decimal, InvalidOperation
from enum import StrEnum
from pathlib import PurePosixPath
from typing import Annotated, Literal, Self

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StrictBool,
    StrictInt,
    StrictStr,
    field_validator,
    model_validator,
)


NonBlankStr = Annotated[StrictStr, Field(min_length=1)]
NonNegativeInt = Annotated[StrictInt, Field(ge=0)]
PositiveInt = Annotated[StrictInt, Field(ge=1)]
Month = Annotated[StrictInt, Field(ge=1, le=12)]

_DECIMAL_PATTERN = re.compile(r"^-?(?:0|[1-9]\d*)(?:\.\d+)?$")
_FACT_ID_SUFFIX_PATTERN = re.compile(r"^\d{4,}$")


class DocGroup(StrEnum):
    """Manifest disclosure group."""

    PERIODIC = "periodic"
    MAJOR = "major"
    EXCHANGE = "exchange"
    HOLDING = "holding"


class ManifestFileFormat(StrEnum):
    """File format value preserved from the manifest."""

    XML = "xml"
    PDF_HTML = "pdf+html"


class ContentType(StrEnum):
    """Structure in which an evidence chunk appeared in the source."""

    PARAGRAPH = "paragraph"
    TABLE = "table"
    LIST = "list"


class SourceFormat(StrEnum):
    """Source format detected from the actual file contents."""

    DART_XML = "dart_xml"
    HTML = "html"
    PDF = "pdf"


class ValueType(StrEnum):
    """Data type of an extracted fact."""

    NUMBER = "number"
    TEXT = "text"
    DATE = "date"
    BOOLEAN = "boolean"


class PeriodBasis(StrEnum):
    """Time basis used by an extracted fact."""

    CUMULATIVE = "cumulative"
    QUARTER_ONLY = "quarter_only"
    POINT_IN_TIME = "point_in_time"
    NOT_APPLICABLE = "not_applicable"


class Scope(StrEnum):
    """Consolidated or separate financial statement scope."""

    CONSOLIDATED = "consolidated"
    SEPARATE = "separate"
    NOT_APPLICABLE = "not_applicable"
    UNKNOWN = "unknown"


class ExtractionStatus(StrEnum):
    """Outcome of normalizing one extracted value.

    ``normalized_value`` can be empty for more than one reason. Without a status
    a parser defect is indistinguishable from a genuine gap in the disclosure,
    and the answer would disguise the defect as an information limit
    (docs/data-model.md section 9).

    Adding a state makes existing output unreadable, so bump ``schema_version``.
    """

    OK = "ok"
    UNIT_MISSING = "unit_missing"


class RelationType(StrEnum):
    """Type of correction or follow-up relationship."""

    CORRECTION = "correction"
    AMENDMENT = "amendment"
    TERMINATION = "termination"
    FOLLOW_UP = "follow_up"


class ContractModel(BaseModel):
    """Base configuration shared by all output-contract models."""

    model_config = ConfigDict(extra="forbid")


def _require_non_blank(value: str, field_name: str) -> str:
    if not value.strip():
        raise ValueError(f"{field_name} must not be blank")
    return value


def _normalize_nfc(value: str) -> str:
    return unicodedata.normalize("NFC", value)


def _normalize_logical_path(value: str, field_name: str) -> str:
    """Return an NFC POSIX path after rejecting host-dependent paths."""

    value = _require_non_blank(value, field_name)
    if "\\" in value:
        raise ValueError(f"{field_name} must use '/' as the path separator")

    normalized = _normalize_nfc(value)
    path = PurePosixPath(normalized)
    if path.is_absolute():
        raise ValueError(f"{field_name} must be relative to the corpus root")
    if any(part in {"", ".", ".."} for part in normalized.split("/")):
        raise ValueError(f"{field_name} must be a canonical relative POSIX path")
    if str(path) != normalized:
        raise ValueError(f"{field_name} must be a canonical relative POSIX path")
    return normalized


class DisclosureDocument(ContractModel):
    """Common metadata for one disclosure document."""

    doc_id: NonBlankStr
    corp_code: Annotated[StrictStr, Field(pattern=r"^\d{8}$")]
    corp_name: NonBlankStr
    listed_name: NonBlankStr
    stock_code: Annotated[StrictStr, Field(pattern=r"^\d{6}$")]
    industry: NonBlankStr
    sector: NonBlankStr
    doc_group: DocGroup
    doc_subtype: StrictStr | None
    report_nm: NonBlankStr
    rcept_no: Annotated[StrictStr, Field(pattern=r"^\d{14}$")]
    rcept_dt: date
    flr_nm: NonBlankStr
    is_correction: StrictBool
    base_year: StrictInt | None
    base_month: Month | None
    source_path: NonBlankStr
    manifest_file_format: ManifestFileFormat
    source_file_count: PositiveInt

    @field_validator(
        "doc_id",
        "corp_name",
        "listed_name",
        "industry",
        "sector",
        "report_nm",
        "flr_nm",
    )
    @classmethod
    def required_strings_must_not_be_blank(cls, value: str, info) -> str:
        return _require_non_blank(value, info.field_name)

    @field_validator("corp_name", "listed_name")
    @classmethod
    def normalize_identifying_names(cls, value: str) -> str:
        return _normalize_nfc(value)

    @field_validator("source_path")
    @classmethod
    def normalize_source_path(cls, value: str) -> str:
        return _normalize_logical_path(value, "source_path")

    @model_validator(mode="after")
    def validate_base_period(self) -> Self:
        has_year = self.base_year is not None
        has_month = self.base_month is not None
        if self.doc_group is DocGroup.PERIODIC and not (has_year and has_month):
            raise ValueError(
                "base_year and base_month are required when doc_group is periodic"
            )
        if self.doc_group is not DocGroup.PERIODIC and (has_year or has_month):
            raise ValueError(
                "base_year and base_month must be null when doc_group is not periodic"
            )
        return self


class EvidenceChunk(ContractModel):
    """Minimum source-text unit used for retrieval and answer evidence."""

    chunk_id: NonBlankStr
    doc_id: NonBlankStr
    sequence: NonNegativeInt
    content_type: ContentType
    text: NonBlankStr
    source_file: NonBlankStr
    source_format: SourceFormat
    page_number: PositiveInt | None
    section_title: StrictStr | None
    table_title: StrictStr | None
    row_label: StrictStr | None
    column_label: StrictStr | None

    @field_validator("chunk_id", "doc_id", "text")
    @classmethod
    def required_strings_must_not_be_blank(cls, value: str, info) -> str:
        # The original evidence text is checked but never stripped or normalized.
        return _require_non_blank(value, info.field_name)

    @field_validator("source_file")
    @classmethod
    def normalize_source_file(cls, value: str) -> str:
        return _normalize_logical_path(value, "source_file")

    @model_validator(mode="after")
    def validate_source_location_and_id(self) -> Self:
        expected_chunk_id = f"{self.doc_id}:chunk:{self.sequence:04d}"
        if self.chunk_id != expected_chunk_id:
            raise ValueError(f"chunk_id must be {expected_chunk_id}")

        if self.source_format is SourceFormat.PDF and self.page_number is None:
            raise ValueError("page_number is required when source_format is pdf")
        if self.source_format is not SourceFormat.PDF and self.page_number is not None:
            raise ValueError(
                "page_number must be null when source_format is not pdf"
            )
        return self


class ExtractedFact(ContractModel):
    """Structured fact used for comparison, calculation, and state tracking."""

    fact_id: NonBlankStr
    doc_id: NonBlankStr
    chunk_id: NonBlankStr
    fact_key: NonBlankStr
    label: NonBlankStr
    value_type: ValueType
    raw_value: NonBlankStr
    raw_unit: StrictStr | None
    normalized_value: StrictStr | None
    normalized_unit: StrictStr | None
    period_start: date | None
    period_end: date | None
    as_of_date: date | None
    period_basis: PeriodBasis
    scope: Scope
    extraction_status: ExtractionStatus

    @field_validator(
        "fact_id", "doc_id", "chunk_id", "fact_key", "label", "raw_value"
    )
    @classmethod
    def required_strings_must_not_be_blank(cls, value: str, info) -> str:
        # raw_value is checked but deliberately returned without normalization.
        return _require_non_blank(value, info.field_name)

    @model_validator(mode="after")
    def validate_id_and_normalized_value(self) -> Self:
        expected_prefix = f"{self.doc_id}:fact:"
        suffix = self.fact_id.removeprefix(expected_prefix)
        if (
            not self.fact_id.startswith(expected_prefix)
            or not _FACT_ID_SUFFIX_PATTERN.fullmatch(suffix)
        ):
            raise ValueError(
                "fact_id must use the format {doc_id}:fact:{four-digit sequence}"
            )

        if self.normalized_value is not None:
            if self.value_type is ValueType.NUMBER:
                if not _DECIMAL_PATTERN.fullmatch(self.normalized_value):
                    raise ValueError(
                        "normalized_value for a number must be a decimal string"
                    )
                try:
                    number = Decimal(self.normalized_value)
                except InvalidOperation as error:
                    raise ValueError(
                        "normalized_value for a number must be a decimal string"
                    ) from error
                if not number.is_finite():
                    raise ValueError("normalized numeric values must be finite")
            elif self.value_type is ValueType.DATE:
                try:
                    normalized_date = date.fromisoformat(self.normalized_value)
                except ValueError as error:
                    raise ValueError(
                        "normalized_value for a date must use YYYY-MM-DD"
                    ) from error
                if normalized_date.isoformat() != self.normalized_value:
                    raise ValueError(
                        "normalized_value for a date must use YYYY-MM-DD"
                    )

        if (self.period_start is None) != (self.period_end is None):
            raise ValueError("period_start and period_end must be provided together")
        if (
            self.period_start is not None
            and self.period_end is not None
            and self.period_start > self.period_end
        ):
            raise ValueError("period_start must not be after period_end")
        return self


class ChangedField(ContractModel):
    """One normalized value changed by a disclosure relationship."""

    fact_key: NonBlankStr
    before_value: StrictStr | None
    after_value: StrictStr | None
    unit: StrictStr | None

    @field_validator("fact_key")
    @classmethod
    def fact_key_must_not_be_blank(cls, value: str) -> str:
        return _require_non_blank(value, "fact_key")


class DisclosureRelation(ContractModel):
    """Correction or follow-up relationship between disclosure documents."""

    relation_id: NonBlankStr
    event_key: NonBlankStr
    original_doc_id: NonBlankStr
    related_doc_id: NonBlankStr
    relation_type: RelationType
    previous_status: StrictStr | None
    current_status: StrictStr | None
    changed_fields: list[ChangedField]
    evidence_chunk_ids: Annotated[list[NonBlankStr], Field(min_length=1)]

    @field_validator(
        "relation_id", "event_key", "original_doc_id", "related_doc_id"
    )
    @classmethod
    def required_strings_must_not_be_blank(cls, value: str, info) -> str:
        return _require_non_blank(value, info.field_name)

    @field_validator("evidence_chunk_ids")
    @classmethod
    def evidence_ids_must_be_unique(cls, value: list[str]) -> list[str]:
        for chunk_id in value:
            _require_non_blank(chunk_id, "evidence_chunk_ids")
        if len(value) != len(set(value)):
            raise ValueError("evidence_chunk_ids must not contain duplicates")
        return value

    @model_validator(mode="after")
    def validate_relation_identity(self) -> Self:
        if self.original_doc_id == self.related_doc_id:
            raise ValueError("original_doc_id and related_doc_id must be different")
        expected_relation_id = (
            f"{self.original_doc_id}:{self.related_doc_id}:{self.relation_type.value}"
        )
        if self.relation_id != expected_relation_id:
            raise ValueError(f"relation_id must be {expected_relation_id}")
        return self


class PreprocessResult(ContractModel):
    """Validated output for one manifest disclosure document."""

    schema_version: Literal["0.2"]
    parser_version: Annotated[
        StrictStr, Field(pattern=r"^(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)$")
    ]
    document: DisclosureDocument
    evidence_chunks: Annotated[list[EvidenceChunk], Field(min_length=1)]
    extracted_facts: list[ExtractedFact]
    relations: list[DisclosureRelation]

    @model_validator(mode="after")
    def validate_internal_references(self) -> Self:
        doc_id = self.document.doc_id

        chunk_ids = [chunk.chunk_id for chunk in self.evidence_chunks]
        sequences = [chunk.sequence for chunk in self.evidence_chunks]
        if len(chunk_ids) != len(set(chunk_ids)):
            raise ValueError("evidence chunk IDs must be unique within a document")
        if len(sequences) != len(set(sequences)):
            raise ValueError("evidence chunk sequences must be unique within a document")
        if sorted(sequences) != list(range(len(sequences))):
            raise ValueError("evidence chunk sequences must be contiguous from zero")
        for chunk in self.evidence_chunks:
            if chunk.doc_id != doc_id:
                raise ValueError("every evidence chunk must belong to document.doc_id")

        known_chunk_ids = set(chunk_ids)
        fact_ids = [fact.fact_id for fact in self.extracted_facts]
        if len(fact_ids) != len(set(fact_ids)):
            raise ValueError("fact IDs must be unique within a document")
        for fact in self.extracted_facts:
            if fact.doc_id != doc_id:
                raise ValueError("every extracted fact must belong to document.doc_id")
            if fact.chunk_id not in known_chunk_ids:
                raise ValueError("every extracted fact must reference an existing chunk")

        relation_ids = [relation.relation_id for relation in self.relations]
        if len(relation_ids) != len(set(relation_ids)):
            raise ValueError("relation IDs must be unique within a document")
        for relation in self.relations:
            # The Java persistence service stores relations with the later/current
            # document, so its related_doc_id must identify this result's document.
            if relation.related_doc_id != doc_id:
                raise ValueError(
                    "every relation.related_doc_id must equal document.doc_id"
                )
            unknown_evidence = set(relation.evidence_chunk_ids) - known_chunk_ids
            if unknown_evidence:
                raise ValueError(
                    "every relation evidence_chunk_id must reference an existing chunk"
                )
        return self
