package main

import (
	"flag"
	"fmt"
	"io"
	"os"
	"runtime"

	"kkdugi-runner/internal/config"
)

var version = "0.1.0-dev"

func main() { os.Exit(run(os.Args[1:], os.Stdout, os.Stderr)) }

func run(args []string, stdout, stderr io.Writer) int {
	if len(args) == 1 && args[0] == "version" {
		fmt.Fprintf(stdout, "kkdugi-runner %s %s/%s protocol=1\n", version, runtime.GOOS, runtime.GOARCH)
		return 0
	}
	if len(args) == 0 || args[0] != "verify" {
		fmt.Fprintln(stderr, "usage: kkdugi-runner version | verify --config <path>")
		return 2
	}
	flags := flag.NewFlagSet("verify", flag.ContinueOnError)
	flags.SetOutput(stderr)
	path := flags.String("config", "", "TOML config path")
	if err := flags.Parse(args[1:]); err != nil {
		return 2
	}
	if *path == "" || flags.NArg() != 0 {
		fmt.Fprintln(stderr, "verify requires --config <path>")
		return 2
	}
	cfg, err := config.Load(*path)
	if err == nil {
		err = cfg.Verify()
	}
	if err != nil {
		fmt.Fprintf(stderr, "verify failed: %v\n", err)
		return 1
	}
	fmt.Fprintf(stdout, "verified %d program(s), capacity=%d\n", len(cfg.Programs), cfg.Capacity)
	return 0
}
