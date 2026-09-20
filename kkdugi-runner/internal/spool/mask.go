package spool

import (
	"bytes"
	"unicode/utf8"
)

// Keep potentially matching suffixes until the next read. Mark every overlapping
// match before consuming bytes, so overlapping secret values cannot leak tails.
type masker struct {
	secrets [][]byte
	pending []byte
	marked  []bool
	longest int
}

func newMasker(values []string) *masker {
	m := &masker{longest: 1}
	for _, v := range values {
		if v != "" {
			m.secrets = append(m.secrets, []byte(v))
			m.longest = max(m.longest, len(v))
		}
	}
	return m
}
func (m *masker) feed(b []byte, eof bool) string {
	m.pending = append(m.pending, b...)
	m.marked = append(m.marked, make([]bool, len(b))...)
	for _, secret := range m.secrets {
		for at := 0; at <= len(m.pending)-len(secret); {
			i := bytes.Index(m.pending[at:], secret)
			if i < 0 {
				break
			}
			i += at
			for j := i; j < i+len(secret); j++ {
				m.marked[j] = true
			}
			at = i + 1
		}
	}
	n := len(m.pending)
	if !eof {
		n = max(0, n-m.longest+1)
	}
	masked := append([]byte(nil), m.pending...)
	for i, v := range m.marked {
		if v {
			masked[i] = '*'
		}
	}
	out := []byte{}
	used := 0
	for used < n {
		if !eof && !utf8.FullRune(masked[used:]) {
			break
		}
		r, size := utf8.DecodeRune(masked[used:])
		if used+size > n && !eof {
			break
		}
		out = utf8.AppendRune(out, r)
		used += size
	}
	m.pending = append(m.pending[:0], m.pending[used:]...)
	m.marked = append(m.marked[:0], m.marked[used:]...)
	return string(out)
}
