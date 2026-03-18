from __future__ import annotations

import hashlib
import pathlib
from dataclasses import dataclass
from datetime import datetime
from typing import Iterable

import numpy as np
import pandas as pd

DEFAULT_ORDER_COLS = ["orderId", "orderDate", "productId", "quantity", "facilityId"]
DEFAULT_PRODUCT_COLS = ["productId", "internalName", "productTypeId", "introductionDate", "salesDiscontinuationDate"]
DEFAULT_PRODUCT_FACILITY_COLS = ["productId", "facilityId", "minimumStock", "reorderQuantity", "lastInventoryCountDate"]
DEFAULT_INVENTORY_COLS = ["inventoryItemId", "productId", "facilityId", "quantityOnHandTotal", "availableToPromiseTotal"]


def _file_hash(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _safe_read_csv(path: pathlib.Path, expected_cols: Iterable[str]) -> pd.DataFrame:
    if not path.exists():
        raise FileNotFoundError(f"Missing required data file: {path}")
    df_try = pd.read_csv(path)
    expected = set(expected_cols)
    if expected.issubset(set(df_try.columns)):
        return df_try
    return pd.read_csv(path, header=None, names=list(expected_cols))


def _safe_read_csv_optional(path: pathlib.Path, expected_cols: Iterable[str]) -> pd.DataFrame | None:
    if not path.exists():
        return None
    return _safe_read_csv(path, expected_cols)


@dataclass
class ModelMetadata:
    model_version: str
    data_version: str
    data_start: str
    data_end: str
    order_rows: int
    product_count: int
    window_days: int
    created_at: str


@dataclass
class DemandModel:
    df_daily: pd.DataFrame
    window: int = 30
    metadata: ModelMetadata | None = None
    product_index: set[str] | None = None
    inventory_summary: pd.DataFrame | None = None
    facility_summary: pd.DataFrame | None = None

    @classmethod
    def from_exports(
        cls,
        order_lines_path: pathlib.Path,
        products_path: pathlib.Path | None = None,
        product_facility_path: pathlib.Path | None = None,
        inventory_items_path: pathlib.Path | None = None,
        window: int = 30,
        model_version: str = "0.1.0",
    ) -> "DemandModel":
        df = _safe_read_csv(order_lines_path, DEFAULT_ORDER_COLS)
        df["orderDate"] = pd.to_datetime(df["orderDate"], errors="coerce")
        df["quantity"] = pd.to_numeric(df["quantity"], errors="coerce").fillna(0)
        df.loc[df["quantity"] < 0, "quantity"] = 0
        df = df.dropna(subset=["orderDate", "productId"])
        if "facilityId" not in df.columns:
            df["facilityId"] = ""

        daily = (
            df.groupby([pd.Grouper(key="orderDate", freq="D"), "productId"])["quantity"]
            .sum()
            .reset_index()
        )

        products_df = _safe_read_csv_optional(products_path, DEFAULT_PRODUCT_COLS) if products_path else None
        if products_df is not None and "productId" in products_df.columns:
            product_index = set(products_df["productId"].dropna().unique().tolist())
        else:
            product_index = set(daily["productId"].unique().tolist())

        inventory_df = _safe_read_csv_optional(inventory_items_path, DEFAULT_INVENTORY_COLS) if inventory_items_path else None
        if inventory_df is not None:
            inventory_df["quantityOnHandTotal"] = pd.to_numeric(inventory_df["quantityOnHandTotal"], errors="coerce").fillna(0)
            inventory_df["availableToPromiseTotal"] = pd.to_numeric(inventory_df["availableToPromiseTotal"], errors="coerce").fillna(0)
            inventory_summary = (
                inventory_df.groupby("productId")[["quantityOnHandTotal", "availableToPromiseTotal"]]
                .sum()
                .reset_index()
            )
        else:
            inventory_summary = None

        facility_df = _safe_read_csv_optional(product_facility_path, DEFAULT_PRODUCT_FACILITY_COLS) if product_facility_path else None
        if facility_df is not None:
            facility_df["minimumStock"] = pd.to_numeric(facility_df["minimumStock"], errors="coerce").fillna(0)
            facility_df["reorderQuantity"] = pd.to_numeric(facility_df["reorderQuantity"], errors="coerce").fillna(0)
            facility_summary = (
                facility_df.groupby("productId")[["minimumStock", "reorderQuantity"]]
                .max()
                .reset_index()
            )
        else:
            facility_summary = None

        data_start = daily["orderDate"].min()
        data_end = daily["orderDate"].max()
        data_version = _file_hash(order_lines_path)
        metadata = ModelMetadata(
            model_version=model_version,
            data_version=data_version,
            data_start=data_start.strftime("%Y-%m-%d") if pd.notnull(data_start) else "",
            data_end=data_end.strftime("%Y-%m-%d") if pd.notnull(data_end) else "",
            order_rows=len(df),
            product_count=len(product_index),
            window_days=window,
            created_at=datetime.utcnow().isoformat(timespec="seconds") + "Z",
        )
        return cls(
            df_daily=daily,
            window=window,
            metadata=metadata,
            product_index=product_index,
            inventory_summary=inventory_summary,
            facility_summary=facility_summary,
        )

    def predict(self, product_id: str, horizon_days: int = 14) -> dict:
        prod = self.df_daily[self.df_daily["productId"] == product_id].copy()
        if prod.empty:
            return {
                "product_id": product_id,
                "avg_daily": 0.0,
                "total": 0.0,
                "horizon_days": horizon_days,
                "interval_low": 0.0,
                "interval_high": 0.0,
                "history_days": 0,
                "model_version": self.metadata.model_version if self.metadata else "",
                "data_start": self.metadata.data_start if self.metadata else "",
                "data_end": self.metadata.data_end if self.metadata else "",
            }

        prod = prod.sort_values("orderDate")
        prod.set_index("orderDate", inplace=True)
        qty = prod["quantity"]
        roll = qty.rolling(self.window, min_periods=1)
        avg_daily = roll.mean().iloc[-1]
        std_daily = roll.std().iloc[-1]
        method = "rolling_mean"
        if len(qty) >= self.window * 2:
            try:
                x = np.arange(len(qty))
                coeffs = np.polyfit(x, qty.values, 1)
                future_x = np.arange(len(qty), len(qty) + horizon_days)
                preds = coeffs[0] * future_x + coeffs[1]
                avg_daily = float(np.mean(np.maximum(preds, 0)))
                std_daily = float(np.std(qty.values[-self.window:]))
                method = "linear_trend"
            except Exception:
                method = "rolling_mean"
        n = min(len(qty), self.window)
        if np.isnan(std_daily):
            std_daily = 0.0
        # 95% confidence interval for mean
        margin = 1.96 * (std_daily / np.sqrt(max(n, 1)))
        low = max(avg_daily - margin, 0.0)
        high = max(avg_daily + margin, 0.0)
        total = float(avg_daily * horizon_days)
        inventory = self._inventory_for(product_id)
        facility = self._facility_for(product_id)
        stock_gap = max((facility.get("minimumStock", 0) - inventory.get("quantityOnHandTotal", 0)), 0)
        return {
            "product_id": product_id,
            "avg_daily": float(avg_daily),
            "total": total,
            "horizon_days": horizon_days,
            "interval_low": float(low * horizon_days),
            "interval_high": float(high * horizon_days),
            "history_days": int(len(qty)),
            "model_version": self.metadata.model_version if self.metadata else "",
            "data_start": self.metadata.data_start if self.metadata else "",
            "data_end": self.metadata.data_end if self.metadata else "",
            "on_hand": float(inventory.get("quantityOnHandTotal", 0)),
            "available_to_promise": float(inventory.get("availableToPromiseTotal", 0)),
            "min_stock": float(facility.get("minimumStock", 0)),
            "reorder_qty": float(facility.get("reorderQuantity", 0)),
            "stock_gap": float(stock_gap),
            "forecast_method": method,
        }

    def predict_batch(self, product_ids: Iterable[str], horizon_days: int = 14) -> list[dict]:
        return [self.predict(pid, horizon_days) for pid in product_ids]

    def _inventory_for(self, product_id: str) -> dict:
        return _lookup_row(self.inventory_summary, product_id)

    def _facility_for(self, product_id: str) -> dict:
        return _lookup_row(self.facility_summary, product_id)

    def evaluate(self, eval_days: int = 30) -> dict:
        if self.df_daily.empty:
            return {"mae": 0.0, "mape": 0.0, "samples": 0}
        df = self.df_daily.sort_values("orderDate")
        mae_list = []
        mape_list = []
        samples = 0

        for product_id, group in df.groupby("productId"):
            series = group.set_index("orderDate")["quantity"].asfreq("D", fill_value=0)
            if len(series) < self.window + eval_days:
                continue
            tail = series.iloc[-(eval_days + self.window):]
            for i in range(self.window, len(tail)):
                history = tail.iloc[i - self.window:i]
                actual = tail.iloc[i]
                pred = history.mean()
                mae_list.append(abs(actual - pred))
                if actual > 0:
                    mape_list.append(abs(actual - pred) / actual)
                samples += 1

        if samples == 0:
            return {"mae": 0.0, "mape": 0.0, "samples": 0}
        mae = float(np.mean(mae_list))
        mape = float(np.mean(mape_list)) if mape_list else 0.0
        return {"mae": mae, "mape": mape, "samples": samples}


def load_model(base_dir: pathlib.Path, window: int = 30, model_version: str = "0.1.0") -> DemandModel:
    export_dir = base_dir / "runtime" / "data" / "export"
    order_lines = export_dir / "order_lines.csv"
    products = export_dir / "products.csv"
    product_facility = export_dir / "product_facility.csv"
    inventory_items = export_dir / "inventory_items.csv"
    return DemandModel.from_exports(
        order_lines,
        products_path=products,
        product_facility_path=product_facility,
        inventory_items_path=inventory_items,
        window=window,
        model_version=model_version,
    )

def _lookup_row(df: pd.DataFrame | None, product_id: str) -> dict:
    if df is None:
        return {}
    match = df[df["productId"] == product_id]
    if match.empty:
        return {}
    return match.iloc[0].to_dict()
