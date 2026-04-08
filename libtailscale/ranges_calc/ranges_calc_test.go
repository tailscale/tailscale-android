package ranges_calc

import (
    "fmt"
    "net/netip"
    "testing"
)

func TestCalculate_NoDisallowed(t *testing.T) {
    allowed := []netip.Prefix{}
    p, _ := netip.ParsePrefix("10.0.0.0/8")
    allowed = append(allowed, p)

    v4, v6, err := Calculate(allowed, nil)
    if err != nil {
        t.Fatalf("unexpected error: %v", err)
    }
    if len(v6) != 0 {
        t.Fatalf("expected no IPv6 prefixes, got %d", len(v6))
    }
    if len(v4) == 0 {
        t.Fatalf("expected some IPv4 prefixes, got none")
    }
}

func TestCalculate_LoopbackIgnored(t *testing.T) {
    allowed := []netip.Prefix{}
    a, _ := netip.ParsePrefix("127.0.0.0/8")
    allowed = append(allowed, a)

    // disallowed contains a loopback address which should be ignored.
    d := []netip.Prefix{}
    lp, _ := netip.ParsePrefix("127.0.0.1/32")
    d = append(d, lp)

    v4a, _, err := Calculate(allowed, nil)
    if err != nil {
        t.Fatalf("unexpected error: %v", err)
    }

    v4b, _, err := Calculate(allowed, d)
    if err != nil {
        t.Fatalf("unexpected error: %v", err)
    }

    // Results should be identical because loopback in disallowed is skipped.
    if len(v4a) != len(v4b) {
        t.Fatalf("loopback disallowed altered result: before=%d after=%d", len(v4a), len(v4b))
    }
}

func TestCalculate_CapExceeded(t *testing.T) {
    // Create more than maxCalculatedRoutes separate /32 prefixes.
    want := maxCalculatedRoutes + 1
    allowed := make([]netip.Prefix, 0, want)
    for i := 0; i < want; i++ {
        // Generate addresses 10.X.Y.1 where X = i/256, Y = i%256
        x := (i / 256) % 256
        y := i % 256
        s := fmt.Sprintf("10.%d.%d.1/32", x, y)
        p, err := netip.ParsePrefix(s)
        if err != nil {
            t.Fatalf("parse prefix %q: %v", s, err)
        }
        allowed = append(allowed, p)
    }

    _, _, err := Calculate(allowed, nil)
    if err == nil {
        t.Fatalf("expected error when exceeding cap (%d), got nil", maxCalculatedRoutes)
    }
}
