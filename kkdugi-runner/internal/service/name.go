package service

import (
	"errors"
	"regexp"
)

var nameRegex = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$`)

func ValidateName(name string) error {
	if !nameRegex.MatchString(name) {
		return errors.New("invalid service name")
	}
	return nil
}
