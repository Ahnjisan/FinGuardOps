from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from finguardops_ai.main import app

EXPECTED_HEALTH = {"status": "UP", "service": "ai-service"}


@pytest.fixture
def client() -> Iterator[TestClient]:
    with TestClient(app) as test_client:
        yield test_client


def test_health_contract(client: TestClient) -> None:
    response = client.get("/api/health")

    assert response.status_code == 200
    assert response.headers["content-type"] == "application/json"
    assert response.json() == EXPECTED_HEALTH
    assert set(response.json()) == {"status", "service"}


def test_health_response_is_deterministic(client: TestClient) -> None:
    first_response = client.get("/api/health")
    second_response = client.get("/api/health")

    assert first_response.status_code == second_response.status_code == 200
    assert first_response.content == second_response.content
    assert first_response.json() == second_response.json() == EXPECTED_HEALTH
