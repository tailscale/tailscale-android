package libtailscale

import (
	"math/big"
	"net/netip"
	"sort"
)

// Internal representation of an IP range [Start, End] (inclusive)
type ipRange struct {
	Start netip.Addr
	End   netip.Addr
}

// space describes the address space (32 for IPv4, 128 for IPv6)
type space struct {
	bits uint
}

// ---------- netip.Addr <-> big.Int ----------
func (s space) addrToInt(a netip.Addr) *big.Int {
	if s.bits == 32 {
		b := a.As4()
		return new(big.Int).SetBytes(b[:])
	}
	b := a.As16()
	return new(big.Int).SetBytes(b[:])
}

func (s space) intToAddr(i *big.Int) netip.Addr {
	b := i.FillBytes(make([]byte, s.bits/8))
	if s.bits == 32 {
		var a [4]byte
		copy(a[:], b)
		return netip.AddrFrom4(a)
	}
	var a [16]byte
	copy(a[:], b)
	return netip.AddrFrom16(a)
}

// ---------- merge overlapping ranges ----------
func (s space) mergeRanges(ranges []ipRange) []ipRange {
	if len(ranges) == 0 {
		return nil
	}
	sort.Slice(ranges, func(i, j int) bool {
		return ranges[i].Start.Compare(ranges[j].Start) < 0
	})
	merged := []ipRange{ranges[0]}
	one := big.NewInt(1)
	for _, r := range ranges[1:] {
		last := &merged[len(merged)-1]
		lastEnd := s.addrToInt(last.End)
		curStart := s.addrToInt(r.Start)
		if curStart.Cmp(new(big.Int).Add(lastEnd, one)) <= 0 {
			if r.End.Compare(last.End) > 0 {
				last.End = r.End
			}
		} else {
			merged = append(merged, r)
		}
	}
	return merged
}

// ---------- range -> minimal number of CIDRs ----------
// Every IP range defined by a start and end address can be represented
// by one or more CIDR prefixes. This function calculates the minimal set of CIDR
// prefixes that cover the given range.
func (s space) rangeToCIDRs(r ipRange) []netip.Prefix {
	var result []netip.Prefix
	cur := s.addrToInt(r.Start)
	last := s.addrToInt(r.End)
	one := big.NewInt(1)

	for cur.Cmp(last) <= 0 {
		// Find the largest power-of-2 block starting at cur
		var maxSize uint
		for size := uint(0); size <= s.bits; size++ {
			block := new(big.Int).Lsh(one, size)
			if new(big.Int).And(cur, new(big.Int).Sub(block, one)).Cmp(big.NewInt(0)) != 0 {
				break
			}
			maxSize = size
		}

		// Shrink maxSize if it would go past last
		for {
			block := new(big.Int).Lsh(one, maxSize)
			lastAddr := new(big.Int).Add(cur, new(big.Int).Sub(block, one))
			if lastAddr.Cmp(last) <= 0 {
				break
			}
			if maxSize == 0 {
				break
			}
			maxSize--
		}

		prefixLen := int(s.bits - maxSize)
		result = append(result, netip.PrefixFrom(s.intToAddr(cur), prefixLen))
		cur = cur.Add(cur, new(big.Int).Lsh(one, maxSize))
	}

	return result
}

// ---------- CIDR -> range ----------
// prefixToRange converts a netip.Prefix to an ipRange with Start and End addresses.
// Start is the network address and End is the broadcast address.
func (s space) prefixToRange(p netip.Prefix) ipRange {
	start := s.addrToInt(p.Addr())
	hostBits := int(s.bits) - p.Bits()
	size := new(big.Int).Lsh(big.NewInt(1), uint(hostBits))
	size.Sub(size, big.NewInt(1))
	end := new(big.Int).Add(start, size)
	return ipRange{Start: p.Addr(), End: s.intToAddr(end)}
}

// ---------- helper: subtract disallowed from allowed ----------
func (s space) subtractRanges(allowed []ipRange, disallowed []ipRange) []ipRange {
	if len(allowed) == 0 {
		return nil
	}
	if len(disallowed) == 0 {
		return allowed
	}

	var result []ipRange
	for _, a := range allowed {
		cur := []ipRange{a}
		for _, d := range disallowed {
			cur2 := []ipRange{}
			for _, r := range cur {
				cur2 = append(cur2, s.subtractOneRange(r, d)...)
			}
			cur = cur2
			if len(cur) == 0 {
				break
			}
		}
		result = append(result, cur...)
	}
	return s.mergeRanges(result)
}

// subtractOneRange subtracts a single disallowed range from a single allowed range
func (s space) subtractOneRange(allowed ipRange, disallowed ipRange) []ipRange {
	aStart := s.addrToInt(allowed.Start)
	aEnd := s.addrToInt(allowed.End)
	dStart := s.addrToInt(disallowed.Start)
	dEnd := s.addrToInt(disallowed.End)
	one := big.NewInt(1)

	// No overlap
	if aEnd.Cmp(dStart) < 0 || aStart.Cmp(dEnd) > 0 {
		return []ipRange{allowed}
	}

	var result []ipRange

	// left side
	if aStart.Cmp(dStart) < 0 {
		result = append(result, ipRange{
			Start: allowed.Start,
			End:   s.intToAddr(new(big.Int).Sub(dStart, one)),
		})
	}

	// right side
	if aEnd.Cmp(dEnd) > 0 {
		result = append(result, ipRange{
			Start: s.intToAddr(new(big.Int).Add(dEnd, one)),
			End:   allowed.End,
		})
	}

	return result
}

// rangesCalc performs the calculation: Routes (allowed) minus LocalRoutes (disallowed)
type rangesCalc struct {
	allowed    []netip.Prefix
	disallowed []netip.Prefix
}

func newRangesCalc(routes, localRoutes []netip.Prefix) *rangesCalc {
	return &rangesCalc{allowed: routes, disallowed: localRoutes}
}

func (rc *rangesCalc) calculate() (ipv4 []netip.Prefix, ipv6 []netip.Prefix) {
	var out4 []netip.Prefix
	var out6 []netip.Prefix

	// Collect IPv4 and IPv6 separately
	var allowed4 []ipRange
	var disallowed4 []ipRange
	var allowed6 []ipRange
	var disallowed6 []ipRange

	for _, p := range rc.allowed {
		if p.Addr().Is4() {
			s := space{bits: 32}
			r := s.prefixToRange(p)
			allowed4 = append(allowed4, r)
		} else {
			s := space{bits: 128}
			r := s.prefixToRange(p)
			allowed6 = append(allowed6, r)
		}
	}

	for _, p := range rc.disallowed {
		if p.Addr().Is4() {
			s := space{bits: 32}
			r := s.prefixToRange(p)
			disallowed4 = append(disallowed4, r)
		} else {
			s := space{bits: 128}
			r := s.prefixToRange(p)
			disallowed6 = append(disallowed6, r)
		}
	}

	// Process IPv4
	if len(allowed4) > 0 {
		s := space{bits: 32}
		mergedAllowed := s.mergeRanges(allowed4)
		mergedDisallowed := s.mergeRanges(disallowed4)
		finalAllowed := s.subtractRanges(mergedAllowed, mergedDisallowed)
		for _, r := range finalAllowed {
			for _, pref := range s.rangeToCIDRs(r) {
				out4 = append(out4, pref)
			}
		}
	}

	// Process IPv6
	if len(allowed6) > 0 {
		s := space{bits: 128}
		mergedAllowed := s.mergeRanges(allowed6)
		mergedDisallowed := s.mergeRanges(disallowed6)
		finalAllowed := s.subtractRanges(mergedAllowed, mergedDisallowed)
		for _, r := range finalAllowed {
			for _, pref := range s.rangeToCIDRs(r) {
				out6 = append(out6, pref)
			}
		}
	}

	return out4, out6
}
