import pathlib

import pandas as pd

from model import load_model


def write_order_lines(path: pathlib.Path) -> None:
    df = pd.DataFrame(
        {
            "orderId": ["O1", "O2", "O3"],
            "orderDate": ["2024-01-01", "2024-01-02", "2024-01-03"],
            "productId": ["P1", "P1", "P2"],
            "quantity": [10, 20, 5],
            "facilityId": ["F1", "F1", "F2"],
        }
    )
    df.to_csv(path, index=False)


def test_predict_basic(tmp_path: pathlib.Path):
    base = tmp_path / "ofbiz-framework" / "runtime" / "data" / "export"
    base.mkdir(parents=True)
    order_lines = base / "order_lines.csv"
    write_order_lines(order_lines)

    model = load_model(tmp_path / "ofbiz-framework", window=2, model_version="test")
    result = model.predict("P1", 7)

    assert result["avg_daily"] > 0
    assert result["total"] > 0
    assert result["history_days"] == 2
