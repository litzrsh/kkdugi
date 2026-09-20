package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"runtime"
	"syscall"

	"kkdugi-runner/internal/agent"
	"kkdugi-runner/internal/config"
	"kkdugi-runner/internal/credential"
	"kkdugi-runner/internal/registration"
	"kkdugi-runner/internal/service"
)

var version = "0.1.0-dev"

func main() { os.Exit(run(os.Args[1:], os.Stdin, os.Stdout, os.Stderr)) }

func run(args []string, stdin io.Reader, stdout, stderr io.Writer) int {
	if len(args) == 1 && args[0] == "version" {
		fmt.Fprintf(stdout, "kkdugi-runner %s %s/%s protocol=1\n", version, runtime.GOOS, runtime.GOARCH)
		return 0
	}
	if len(args) == 0 || (args[0] != "verify" && args[0] != "register" && args[0] != "run" && args[0] != "service") {
		fmt.Fprintln(stderr, "usage: kkdugi-runner version | verify --config <path> | register --config <path> (token on stdin) | run --config <path> | service --name <name> --config <path> (Windows SCM)")
		return 2
	}
	flags := flag.NewFlagSet(args[0], flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	path := flags.String("config", "", "TOML config path")
	var serviceName *string
	if args[0] == "service" {
		serviceName = flags.String("name", "kkdugi-runner", "Windows service name")
	}
	if err := flags.Parse(args[1:]); err != nil {
		fmt.Fprintln(stderr, "invalid CLI options")
		return 2
	}
	if *path == "" || flags.NArg() != 0 {
		fmt.Fprintln(stderr, "command requires --config <path>")
		return 2
	}
	cfg, err := config.Load(*path)
	if args[0] == "service" {
		if err == nil {
			err = service.Run(*serviceName, func(ctx context.Context) error { return agent.RunConfigured(ctx, cfg, version) })
		}
		if err != nil {
			fmt.Fprintf(stderr, "service failed: %v\n", err)
			return 1
		}
		return 0
	}
	if args[0] == "run" {
		ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
		defer stop()
		if err == nil {
			err = agent.RunConfigured(ctx, cfg, version)
		}
		if err != nil {
			fmt.Fprintf(stderr, "run stopped: %v\n", err)
			return 1
		}
		return 0
	}
	if args[0] == "register" {
		if err == nil {
			err = credential.EnsureDirectory(cfg.DataDir)
		}
		if err == nil {
			err = registration.Run(context.Background(), cfg.Admin, version, stdin)
		}
		if err != nil {
			fmt.Fprintf(stderr, "register failed: %v\n", err)
			return 1
		}
		fmt.Fprintln(stdout, "runner registered; credential saved")
		return 0
	}
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
