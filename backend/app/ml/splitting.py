from dataclasses import dataclass
from typing import List, Sequence, Tuple


@dataclass(frozen=True)
class ChronologicalSplitResult:
    train_indices: List[int]
    val_indices: List[int]
    test_indices: List[int]
    train_end_ts: int
    val_start_ts: int
    val_end_ts: int
    test_start_ts: int


@dataclass(frozen=True)
class WalkForwardFold:
    fold_index: int
    train_indices: List[int]
    val_indices: List[int]
    test_indices: List[int]
    train_end_ts: int
    val_start_ts: int
    val_end_ts: int
    test_start_ts: int


class ChronologicalSplitter:
    """Time-ordered train/validation/test splitter preventing data leakage and random shuffling."""

    @staticmethod
    def _validate_monotonic(timestamps: Sequence[int]) -> None:
        if len(timestamps) < 30:
            raise ValueError("At least 30 chronological samples required for splitting.")
        for i in range(1, len(timestamps)):
            if timestamps[i] <= timestamps[i - 1]:
                raise ValueError(f"Timestamps must be strictly increasing (index {i}).")

    @classmethod
    def split_dataset(
        cls,
        timestamps: Sequence[int],
        train_ratio: float = 0.60,
        val_ratio: float = 0.20,
    ) -> ChronologicalSplitResult:
        cls._validate_monotonic(timestamps)
        if not (0.3 <= train_ratio <= 0.8) or not (0.1 <= val_ratio <= 0.4):
            raise ValueError("Invalid chronological split ratios.")
        if train_ratio + val_ratio >= 0.95:
            raise ValueError("train_ratio + val_ratio must leave room for out-of-sample test fold.")

        n = len(timestamps)
        train_end = max(10, int(n * train_ratio))
        val_end = max(train_end + 8, int(n * (train_ratio + val_ratio)))
        if val_end >= n - 5:
            raise ValueError("Insufficient samples for chronological test fold.")

        train_idx = list(range(0, train_end))
        val_idx = list(range(train_end, val_end))
        test_idx = list(range(val_end, n))

        return ChronologicalSplitResult(
            train_indices=train_idx,
            val_indices=val_idx,
            test_indices=test_idx,
            train_end_ts=timestamps[train_idx[-1]],
            val_start_ts=timestamps[val_idx[0]],
            val_end_ts=timestamps[val_idx[-1]],
            test_start_ts=timestamps[test_idx[0]],
        )

    @classmethod
    def generate_walk_forward_folds(
        cls,
        timestamps: Sequence[int],
        n_splits: int = 3,
        min_train_size: int = 120,
    ) -> List[WalkForwardFold]:
        cls._validate_monotonic(timestamps)
        n = len(timestamps)
        if n_splits < 2:
            raise ValueError("n_splits must be >= 2.")

        effective_min_train = min(min_train_size, max(30, int(n * 0.45)))
        remaining = n - effective_min_train
        block_size = remaining // (n_splits + 1)
        if block_size < 12:
            raise ValueError(f"Insufficient samples ({n}) for {n_splits} walk-forward folds.")

        folds: List[WalkForwardFold] = []
        for k in range(n_splits):
            train_end = effective_min_train + k * block_size
            val_size = max(8, block_size // 2)
            val_end = train_end + val_size
            test_end = min(n, val_end + max(8, block_size - val_size))
            if test_end <= val_end or val_end <= train_end:
                continue

            train_idx = list(range(0, train_end))
            val_idx = list(range(train_end, val_end))
            test_idx = list(range(val_end, test_end))
            folds.append(
                WalkForwardFold(
                    fold_index=k,
                    train_indices=train_idx,
                    val_indices=val_idx,
                    test_indices=test_idx,
                    train_end_ts=timestamps[train_idx[-1]],
                    val_start_ts=timestamps[val_idx[0]],
                    val_end_ts=timestamps[val_idx[-1]],
                    test_start_ts=timestamps[test_idx[0]],
                )
            )

        if not folds:
            raise ValueError("Unable to construct valid walk-forward folds.")
        return folds
