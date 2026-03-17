from __future__ import annotations

import pathlib
from dataclasses import dataclass
import pandas as pd

@dataclass
class DemandModel:
    df_daily: pd.DataFrame
    window: int = 30  # days for rolling mean

    @classmethod
    def from_csv(cls, order_lines_path: pathlib.Path, window: int = 30) -> "DemandModel":
        """
        Load order_lines CSV robustly:
        - If headers include expected names, use them.
        - Otherwise assume headerless and assign default names.
        """
        df_try = pd.read_csv(order_lines_path)
        expected = {"orderId", "orderDate", "productId", "quantity"}
        if expected.issubset(set(df_try.columns)):
            df = df_try
        else:
            df = pd.read_csv(
                order_lines_path,
                header=None,
                names=["orderId", "orderDate", "productId", "quantity", "facilityId"],
            )

        df["orderDate"] = pd.to_datetime(df["orderDate"])
        df["quantity"] = pd.to_numeric(df["quantity"], errors="coerce").fillna(0)
        if "facilityId" not in df.columns:
            df["facilityId"] = ""

        daily = (
            df.groupby([pd.Grouper(key="orderDate", freq="D"), "productId"])["quantity"]
            .sum()
            .reset_index()
        )
        return cls(df_daily=daily, window=window)

    def predict(self, product_id: str, horizon_days: int = 14) -> dict:
        prod = self.df_daily[self.df_daily["productId"] == product_id].copy()
        if prod.empty:
            return {"product_id": product_id, "avg_daily": 0.0, "total": 0.0, "horizon_days": horizon_days}

        prod = prod.sort_values("orderDate")
        prod.set_index("orderDate", inplace=True)
        avg_daily = prod["quantity"].rolling(self.window, min_periods=1).mean().iloc[-1]
        total = float(avg_daily * horizon_days)
        return {
            "product_id": product_id,
            "avg_daily": float(avg_daily),
            "total": total,
            "horizon_days": horizon_days,
        }


def load_model(base_dir: pathlib.Path, window: int = 30) -> DemandModel:
    order_lines = base_dir / "runtime" / "data" / "export" / "order_lines.csv"
    return DemandModel.from_csv(order_lines, window=window)