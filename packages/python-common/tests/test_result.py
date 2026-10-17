import json
import re
from contextlib import nullcontext

import pytest
from pydantic import BaseModel, ValidationError

from rag_common import ClientError, RemoteError, Result, ServiceError, get_request_id
from rag_common.context import request_id_context


class Item(BaseModel):
    name: str


def test_result_exact_wire_fields_and_generic_model():
    result = Result[Item].success(Item(name="知识"), request_id="explicit")
    assert result.model_dump(mode="json") == {
        "code": "0",
        "message": "success",
        "data": {"name": "知识"},
        "requestId": "explicit",
    }
    assert json.loads(result.model_dump_json()) == result.model_dump(mode="json")
    assert isinstance(Result[Item].model_validate(result.model_dump()).data, Item)
    assert result.model_dump(by_alias=False)["request_id"] == "explicit"


def test_request_id_precedence_and_nested_context_reset():
    assert get_request_id() is None
    generated = Result.success().request_id
    assert re.fullmatch(r"[a-f0-9]{32}", generated)
    assert Result.success().request_id != generated
    with request_id_context("outer"):
        assert Result.success().request_id == "outer"
        assert Result.success(request_id="explicit").request_id == "explicit"
        assert Result(code="0", message="success").request_id == "outer"
        with request_id_context("inner"):
            assert get_request_id() == "inner"
        assert get_request_id() == "outer"
    assert get_request_id() is None


@pytest.mark.parametrize("request_id", [None, "", " ", "\t"])
def test_blank_explicit_request_ids_fall_back(request_id):
    with request_id_context("outer"):
        assert Result.success(request_id=request_id).request_id == "outer"
        assert (
            Result.failure("A000001", "失败", request_id=request_id).request_id
            == "outer"
        )
    assert re.fullmatch(
        r"[a-f0-9]{32}", Result.success(request_id=request_id).request_id
    )


@pytest.mark.parametrize("context_id", [None, "current"])
@pytest.mark.parametrize(
    "request_fields",
    [
        {},
        {"request_id": None},
        {"request_id": ""},
        {"request_id": " \t"},
        {"requestId": None},
        {"requestId": ""},
        {"requestId": " \t"},
    ],
)
def test_constructor_and_validation_resolve_missing_or_blank_ids(
    context_id, request_fields
):
    payload = {"code": "0", "message": "success", **request_fields}
    with request_id_context(context_id) if context_id else nullcontext():
        for result in (Result(**payload), Result.model_validate(payload)):
            if context_id is None:
                assert re.fullmatch(r"[a-f0-9]{32}", result.request_id)
            else:
                assert result.request_id == context_id
            assert result.model_dump() == {
                "code": "0",
                "message": "success",
                "data": None,
                "requestId": result.request_id,
            }
    assert get_request_id() is None


@pytest.mark.parametrize("field", ["request_id", "requestId"])
def test_constructor_and_validation_preserve_explicit_ids(field):
    payload = {"code": "0", "message": "success", field: " explicit "}
    with request_id_context("current"):
        assert Result(**payload).request_id == " explicit "
        assert Result.model_validate(payload).request_id == " explicit "


@pytest.mark.parametrize("value", [42, False, [], {}])
def test_constructor_and_validation_reject_invalid_id_types(value):
    payload = {"code": "0", "message": "success", "requestId": value}
    with pytest.raises(ValidationError):
        Result(**payload)
    with pytest.raises(ValidationError):
        Result.model_validate(payload)


def test_context_resets_after_exception():
    try:
        with request_id_context("temporary"):
            raise RuntimeError("expected")
    except RuntimeError:
        pass
    assert get_request_id() is None


def test_failure_has_null_data_and_alias_input_works():
    result = Result.failure("A123456", "失败", request_id="failure")
    assert result.model_dump() == {
        "code": "A123456",
        "message": "失败",
        "data": None,
        "requestId": "failure",
    }
    assert Result.model_validate(result.model_dump()).request_id == "failure"


def test_error_defaults_and_overrides():
    for error, code, message, status in [
        (ClientError, "A000001", "客户端请求错误", 400),
        (ServiceError, "B000001", "系统执行错误", 500),
        (RemoteError, "C000001", "远程服务调用错误", 502),
    ]:
        instance = error()
        assert (instance.code, instance.message, instance.status_code) == (
            code,
            message,
            status,
        )
        custom = error("自定义消息", code="CUSTOM")
        assert (custom.code, str(custom)) == ("CUSTOM", "自定义消息")
