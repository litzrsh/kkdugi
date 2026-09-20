package service

import (
	"strings"
	"testing"
)

func TestValidateName(t *testing.T) {
	for _, v := range []string{"kkdugi-runner", "1_worker", strings.Repeat("a", 64)} {
		if ValidateName(v) != nil {
			t.Fatal(v)
		}
	}
	for _, v := range []string{"", "-runner", "x y", "x/y", "x;whoami", "한글", strings.Repeat("a", 65)} {
		if ValidateName(v) == nil {
			t.Fatal("unsafe name accepted")
		}
	}
}
