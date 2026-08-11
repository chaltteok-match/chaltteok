import importlib

import pytest
from pydantic import BaseModel, VERSION


@pytest.mark.parametrize(
    "package_name",
    [
        "chaltteok_etl",
        "chaltteok_etl.common",
        "chaltteok_etl.exchange",
        "chaltteok_etl.material",
        "chaltteok_etl.ownership",
        "chaltteok_etl.periodic",
    ],
)
def test_preprocessing_package_is_importable(package_name: str) -> None:
    assert importlib.import_module(package_name) is not None


def test_pydantic_v2_is_available_for_output_models() -> None:
    class SampleOutput(BaseModel):
        doc_id: str

    assert VERSION.startswith("2.")
    assert SampleOutput(doc_id="periodic_20250311000001").model_dump() == {
        "doc_id": "periodic_20250311000001"
    }
