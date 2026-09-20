"""Evaluation score -> win probability.

An engine score is *not* a win rate.  This layer converts centipawns into
an estimated probability with a logistic model

    P(win) = 1 / (1 + exp(-s / K))

The default K is an uncalibrated prior (a 200 cp = one pawn advantage maps
to roughly 60 %).  ``fit`` re-estimates K from (score, result) pairs, e.g.
collected from self-play, by maximum likelihood; the UI labels the output
"Estimated Win Rate" until a calibrated model is loaded.

The ordered-logit extension with a draw margin ``c`` gives a W/D/L split:

    P(loss) = sigma((-s - c) / K),  P(win) = sigma((s - c) / K),
    P(draw) = 1 - P(win) - P(loss)

``c = 0`` collapses to the plain two-outcome model.
"""
from __future__ import annotations

import json
import math
from dataclasses import asdict, dataclass

DEFAULT_K = 500.0


def sigmoid(x: float) -> float:
    if x >= 0:
        z = math.exp(-x)
        return 1.0 / (1.0 + z)
    z = math.exp(x)
    return z / (1.0 + z)


@dataclass
class Calibrator:
    k: float = DEFAULT_K
    draw_margin: float = 0.0
    calibrated: bool = False
    samples: int = 0
    source: str = "default prior (uncalibrated)"

    # -------------------------------------------------------------- inference
    def win_probability(self, score_cp: float) -> float:
        return sigmoid(score_cp / self.k)

    def wdl(self, score_cp: float) -> tuple[float, float, float]:
        c = self.draw_margin
        if c <= 0:
            w = self.win_probability(score_cp)
            return w, 0.0, 1.0 - w
        w = sigmoid((score_cp - c) / self.k)
        l = sigmoid((-score_cp - c) / self.k)
        d = max(0.0, 1.0 - w - l)
        return w, d, l

    def score_for_probability(self, p: float) -> float:
        p = min(max(p, 1e-9), 1 - 1e-9)
        return self.k * math.log(p / (1 - p))

    # -------------------------------------------------------------- fitting
    def fit(self, samples: list[tuple[float, float]], iterations: int = 100) -> float:
        """Maximum-likelihood K from (score_cp, result) pairs where result is
        1 (win for the side the score is for), 0 (loss) or 0.5 (draw).

        Uses Newton's method on the log-likelihood in theta = 1/K.
        """
        if len(samples) < 10:
            raise ValueError("need at least 10 samples to fit")
        theta = 1.0 / self.k
        for _ in range(iterations):
            g = 0.0
            h = 0.0
            for s, y in samples:
                p = sigmoid(theta * s)
                g += (y - p) * s
                h -= p * (1 - p) * s * s
            if abs(h) < 1e-12:
                break
            step = g / h
            new_theta = theta - step
            if new_theta <= 1e-9:
                new_theta = theta / 2
            if abs(new_theta - theta) < 1e-12:
                theta = new_theta
                break
            theta = new_theta
        self.k = 1.0 / theta
        self.calibrated = True
        self.samples = len(samples)
        self.source = f"fitted from {len(samples)} game results"
        return self.k

    # -------------------------------------------------------------- persistence
    def to_json(self) -> str:
        return json.dumps(asdict(self), indent=2)

    @classmethod
    def from_json(cls, text: str) -> "Calibrator":
        return cls(**json.loads(text))

    def save(self, path: str) -> None:
        with open(path, "w", encoding="utf-8") as f:
            f.write(self.to_json())

    @classmethod
    def load(cls, path: str) -> "Calibrator":
        with open(path, encoding="utf-8") as f:
            return cls.from_json(f.read())

    def describe(self) -> dict:
        return {
            "model": "logistic" if self.draw_margin <= 0 else "ordered-logit",
            "k": self.k,
            "draw_margin": self.draw_margin,
            "calibrated": self.calibrated,
            "samples": self.samples,
            "source": self.source,
            "label": "Win Rate" if self.calibrated else "Estimated Win Rate",
        }
