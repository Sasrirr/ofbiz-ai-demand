import importlib
import os
import pathlib

import pandas as pd
from fastapi.testclient import TestClient


def write_order_lines(path: pathlib.Path) -> None:
    df = pd.DataFrame(
        {
            "orderId": ["O1", "O2"],
            "orderDate": ["2024-01-01", "2024-01-02"],
            "productId": ["P1", "P1"],
            "quantity": [10, 20],
            "facilityId": ["F1", "F1"],
        }
    )
    df.to_csv(path, index=False)


def test_predict_endpoint(tmp_path: pathlib.Path, monkeypatch):
    export_dir = tmp_path / "ofbiz-framework" / "runtime" / "data" / "export"
    export_dir.mkdir(parents=True)
    write_order_lines(export_dir / "order_lines.csv")

    monkeypatch.setenv("AI_OFBIZ_BASE_DIR", str(tmp_path / "ofbiz-framework"))
    monkeypatch.setenv("AI_API_KEY", "test-key")

    import main

    importlib.reload(main)
    client = TestClient(main.app)

    resp = client.post(
        "/predict-demand",
        json={"product_id": "P1", "horizon_days": 7},
        headers={"X-API-Key": "test-key"},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["total"] > 0
