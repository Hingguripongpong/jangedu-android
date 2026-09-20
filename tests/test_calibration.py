"""Calibration tests: logistic prior, MLE fit recovers K, WDL sums to one, JSON round trip."""
import math
import random

import pytest

from engine.calibration import Calibrator, sigmoid


def test_default_prior_is_uncalibrated_and_labelled():
    c = Calibrator()
    d = c.describe()
    assert d["calibrated"] is False
    assert d["label"] == "Estimated Win Rate"
    assert abs(c.win_probability(0) - 0.5) < 1e-12
    assert c.win_probability(300) > 0.6 > c.win_probability(100)
    assert c.win_probability(-500) == pytest.approx(1 - c.win_probability(500))


def test_probability_inverse():
    c = Calibrator(k=420)
    for p in (0.1, 0.35, 0.5, 0.77, 0.99):
        assert c.win_probability(c.score_for_probability(p)) == pytest.approx(p, abs=1e-9)


def test_fit_recovers_synthetic_k():
    rng = random.Random(7)
    true_k = 450.0
    samples = []
    for _ in range(6000):
        s = rng.uniform(-1500, 1500)
        y = 1.0 if rng.random() < sigmoid(s / true_k) else 0.0
        samples.append((s, y))
    c = Calibrator()
    k = c.fit(samples)
    assert abs(k - true_k) / true_k < 0.10
    assert c.calibrated and c.samples == 6000
    assert c.describe()["label"] == "Win Rate"


def test_fit_needs_data():
    with pytest.raises(ValueError):
        Calibrator().fit([(0.0, 1.0)] * 5)


def test_wdl_sums_to_one_and_is_symmetric():
    c = Calibrator(k=500, draw_margin=120)
    for s in (-900, -300, 0, 40, 300, 900):
        w, d, l = c.wdl(s)
        assert w + d + l == pytest.approx(1.0)
        w2, d2, l2 = c.wdl(-s)
        assert w == pytest.approx(l2) and l == pytest.approx(w2) and d == pytest.approx(d2)
    assert c.wdl(0)[1] > 0.1  # a draw margin gives real draw mass near zero
    assert Calibrator(k=500).wdl(0)[1] == 0.0


def test_json_roundtrip(tmp_path):
    c = Calibrator(k=333.3, draw_margin=50, calibrated=True, samples=1234, source="unit test")
    path = tmp_path / "calib.json"
    c.save(str(path))
    again = Calibrator.load(str(path))
    assert again == c
    assert Calibrator.from_json(c.to_json()) == c
