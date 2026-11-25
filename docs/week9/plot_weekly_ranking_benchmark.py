import argparse
import csv
from collections import defaultdict
from pathlib import Path
from statistics import median


METHODS = ("A", "B", "C_CURSOR", "C_PAGING", "D")
METRICS = (
    ("ScoreStep (s)", lambda row: float(row["score_ns"]) / 1_000_000_000),
    ("Observed heap (MiB)", lambda row: float(row["heap_peak_sample_bytes"]) / 1_048_576),
    ("MySQL score statements", lambda row: int(row["mysql_scoring_selects"]) + int(row["mysql_scoring_writes"])),
)


def read_runs(directory):
    with (directory / "runs.csv").open(encoding="utf-8", newline="") as source:
        return list(csv.DictReader(source))


def measured_groups(rows):
    groups = defaultdict(list)
    for row in rows:
        if row["warmup"] == "false" and valid_run(row):
            groups[(row["scenario"], row["method"])].append(row)
    return dict(groups)


def valid_run(row):
    return (
        row["status"] == "COMPLETED"
        and row["result_mismatches"] == "0"
        and row["products"] == row["result_rows"]
    )


def metric_value(read, row):
    try:
        return read(row)
    except (KeyError, ValueError):
        return None


def write_graphs(directory, groups):
    import matplotlib

    matplotlib.use("Agg")
    from matplotlib import pyplot as plt

    for fixed_column, fixed_value, x_column in (("purchases", 1_000_000, "products"), ("products", 1_000, "purchases")):
        for distribution in ("uniform", "skewed"):
            for order in ("grouped", "shuffled"):
                title = f"{fixed_column} = {fixed_value:,} | {distribution} / {order}"
                suffix = f"-{distribution}-{order}"
                matching = {
                    key: runs for key, runs in groups.items()
                    if key[0].endswith(suffix) and int(runs[0][fixed_column]) == fixed_value
                }
                if not matching:
                    continue

                figure, axes = plt.subplots(len(METRICS), 1, sharex=True, figsize=(8, 8), layout="constrained")
                figure.suptitle(title)
                for axis, (label, read) in zip(axes, METRICS):
                    for method in METHODS:
                        points = []
                        for (_, measured_method), runs in matching.items():
                            if measured_method == method:
                                values = [value for row in runs if (value := metric_value(read, row)) is not None]
                                if values:
                                    points.append((int(runs[0][x_column]), median(values), min(values), max(values)))
                        if points:
                            x, center, low, high = zip(*sorted(points))
                            (line,) = axis.plot(x, center, marker="o", label=method)
                            axis.fill_between(x, low, high, alpha=0.12, color=line.get_color())
                    axis.set_ylabel(label)
                    axis.set_xscale("log")
                    axis.grid(axis="y", alpha=0.2)
                axes[0].legend(ncol=len(METHODS))
                axes[-1].set_xlabel(x_column)
                figure.savefig(directory / f"{x_column}-{distribution}-{order}.svg")
                plt.close(figure)


def write_analysis(directory, rows, groups):
    lines = [
        "# 주간 랭킹 실측 요약",
        "",
        "예열과 실패 회차를 제외한 실측이다. 괄호 안의 시간은 최소·최대다.",
        "",
        "| 조건 | 방식 | 회차 | ScoreStep (ms) | 최대 관측 heap (MiB) | 집계 SQL 수 |",
        "| --- | --- | ---: | ---: | ---: | ---: |",
    ]
    for (scenario, method), runs in sorted(groups.items()):
        time_ms = [int(run["score_ns"]) / 1_000_000 for run in runs]
        heap_mib = median(int(run["heap_peak_sample_bytes"]) for run in runs) / 1_048_576
        sql_counts = [value for run in runs if (value := metric_value(METRICS[2][1], run)) is not None]
        sql_count = f"{median(sql_counts):g}" if sql_counts else "미계측"
        lines.append(
            f"| {scenario} | {method} | {len(runs)} | "
            f"{median(time_ms):.2f} ({min(time_ms):.2f}~{max(time_ms):.2f}) | {heap_mib:.2f} | {sql_count} |"
        )

    incomplete = [row for row in rows if not valid_run(row)]
    if incomplete:
        lines += ["", "## 실패한 실행", ""]
        lines += [
            f"- {row['scenario']} / {row['method']} / 회차 {row['round']} / {row['status']} "
            f"/ 결과 {row['result_rows']}행 / 불일치 {row['result_mismatches'] or '미검증'}" for row in incomplete
        ]
    lines += [
        "",
        "## 분석에 함께 볼 지표",
        "",
        "- 규모 효과는 구매 수 고정과 상품 수 고정 그래프에서 각각 확인한다.",
        "- A의 분포·PK 순서에 따른 반복 쓰기 차이는 `sql.csv`의 upsert 횟수와 반환 행 수로 확인한다.",
        "- B와 C, D의 시간 차이는 조회 및 메타데이터 SQL, 커밋 수, 실행 계획과 함께 해석한다.",
        "- C의 Reader 차이는 `explain/`에 기록한 첫 페이지와 후속 페이지의 탐색량을 비교한다.",
    ]
    (directory / "analysis.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description="주간 랭킹 벤치마크 CSV로 실측 그래프와 Markdown을 생성합니다.")
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    rows = read_runs(args.directory)
    groups = measured_groups(rows)
    if not groups:
        parser.error("완료된 측정 회차가 없습니다.")
    write_analysis(args.directory, rows, groups)
    write_graphs(args.directory, groups)


if __name__ == "__main__":
    main()
