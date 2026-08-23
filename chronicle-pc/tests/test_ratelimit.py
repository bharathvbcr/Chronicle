"""AuthRateLimiter: sliding-window blocking with bounded state."""

from __future__ import annotations

from chronicle_pipeline.ratelimit import AuthRateLimiter


class FakeClock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


def test_blocks_after_max_fails_then_expires() -> None:
    clock = FakeClock()
    limiter = AuthRateLimiter(max_fails=3, window_sec=60.0, block_sec=120.0, clock=clock)

    for _ in range(3):
        assert limiter.allow("10.0.0.5")
        limiter.record_fail("10.0.0.5")
    assert not limiter.allow("10.0.0.5")

    clock.now += 121.0
    assert limiter.allow("10.0.0.5")


def test_success_clears_counter() -> None:
    limiter = AuthRateLimiter(max_fails=3, clock=FakeClock())
    limiter.record_fail("10.0.0.9")
    limiter.record_fail("10.0.0.9")
    limiter.record_success("10.0.0.9")
    for _ in range(2):
        assert limiter.allow("10.0.0.9")
        limiter.record_fail("10.0.0.9")  # only 1 effective fail after clear


def test_loopback_exempt() -> None:
    limiter = AuthRateLimiter(max_fails=1, clock=FakeClock())
    for _ in range(50):
        limiter.record_fail("127.0.0.1")
        limiter.record_fail("::1")
        limiter.record_fail("localhost")
    assert limiter.allow("127.0.0.1")
    assert limiter.allow("::1")


def test_window_expiry_of_fails() -> None:
    clock = FakeClock()
    limiter = AuthRateLimiter(max_fails=3, window_sec=30.0, block_sec=60.0, clock=clock)
    limiter.record_fail("10.0.0.7")
    clock.now += 31.0
    limiter.record_fail("10.0.0.7")
    clock.now += 31.0
    limiter.record_fail("10.0.0.7")
    # Never reached 3 fails inside one window
    assert limiter.allow("10.0.0.7")


def test_table_bounded_under_spoofing() -> None:
    clock = FakeClock()
    limiter = AuthRateLimiter(max_fails=1000000, clock=FakeClock())
    del clock
    for i in range(5000):
        limiter.record_fail(f"10.1.{i}. {i}")  # unique-ish keys (typo-safe key string)
    for i in range(5000):
        limiter.record_fail(f"spoofer-{i}")
    assert len(limiter._table) <= 4096
