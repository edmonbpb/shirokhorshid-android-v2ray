// Command xraycli is the external-process bridge over libXray used by the app.
package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"time"

	libXray "github.com/xtls/libxray"
)

const (
	usage         = "usage: xraycli run <configPath> | invoke <requestPath> | convert <inputPath> | version"
	methodRun     = "runXray"
	methodStop    = "stopXray"
	methodVersion = "xrayVersion"
	methodConvert = "convertShareLinksToXrayJson"
)

type invokeRequest struct {
	APIVersion int             `json:"apiVersion"`
	Method     string          `json:"method"`
	Payload    json.RawMessage `json:"payload,omitempty"`
}

type invokeResponse struct {
	Success bool            `json:"success"`
	Data    json.RawMessage `json:"data"`
	Error   string          `json:"error"`
}

func invoke(method string, payload any) (invokeResponse, error) {
	request := invokeRequest{APIVersion: 3, Method: method}
	if payload != nil {
		raw, err := json.Marshal(payload)
		if err != nil {
			return invokeResponse{}, err
		}
		request.Payload = raw
	}
	encoded, err := json.Marshal(request)
	if err != nil {
		return invokeResponse{}, err
	}
	var resp invokeResponse
	if err := json.Unmarshal([]byte(libXray.Invoke(string(encoded))), &resp); err != nil {
		return invokeResponse{}, fmt.Errorf("invalid invoke response: %v", err)
	}
	return resp, nil
}

func (resp invokeResponse) err() error {
	if resp.Error != "" {
		return errors.New(resp.Error)
	}
	return errors.New("request failed without an error message")
}

func fail(code int, message string) int {
	fmt.Fprintln(os.Stderr, message)
	return code
}

func cmdRun(args []string) int {
	if len(args) != 1 {
		return fail(2, usage)
	}
	body, err := os.ReadFile(args[0])
	if err != nil {
		return fail(1, "XRAYCLI_ERROR read config: "+err.Error())
	}
	resp, err := invoke(methodRun, map[string]string{"xrayJson": string(body)})
	if err == nil && !resp.Success {
		err = resp.err()
	}
	if err != nil {
		return fail(1, "XRAYCLI_ERROR "+err.Error())
	}
	version, err := coreVersion()
	if err != nil {
		version = "unknown"
	}
	fmt.Println("XRAYCLI_READY " + version)
	go watchParent()
	_, _ = io.Copy(io.Discard, os.Stdin)
	_, _ = invoke(methodStop, nil)
	return 0
}

// watchParent stops Xray when this process is reparented to init (app died).
func watchParent() {
	for {
		time.Sleep(5 * time.Second)
		if os.Getppid() == 1 {
			_, _ = invoke(methodStop, nil)
			os.Exit(0)
		}
	}
}

func coreVersion() (string, error) {
	resp, err := invoke(methodVersion, nil)
	if err != nil {
		return "", err
	}
	if !resp.Success {
		return "", resp.err()
	}
	var data struct {
		Version string `json:"version"`
	}
	if err := json.Unmarshal(resp.Data, &data); err != nil {
		return "", fmt.Errorf("invalid version response: %v", err)
	}
	if data.Version == "" {
		return "", errors.New("empty version in response")
	}
	return data.Version, nil
}

func cmdInvoke(args []string) int {
	if len(args) != 1 {
		return fail(2, usage)
	}
	request, err := os.ReadFile(args[0])
	if err != nil {
		return fail(1, "read request: "+err.Error())
	}
	fmt.Println(strings.TrimSpace(libXray.Invoke(string(request))))
	return 0
}

func cmdConvert(args []string) int {
	if len(args) != 1 {
		return fail(2, usage)
	}
	text, err := os.ReadFile(args[0])
	if err != nil {
		return fail(1, "read input: "+err.Error())
	}
	resp, err := invoke(methodConvert, map[string]string{"text": string(text)})
	if err != nil {
		return fail(1, err.Error())
	}
	if !resp.Success {
		return fail(1, resp.err().Error())
	}
	fmt.Println(strings.TrimSpace(string(resp.Data)))
	return 0
}

func cmdVersion(args []string) int {
	if len(args) != 0 {
		return fail(2, usage)
	}
	version, err := coreVersion()
	if err != nil {
		return fail(1, err.Error())
	}
	fmt.Println(version)
	return 0
}

func main() {
	commands := map[string]func([]string) int{
		"run": cmdRun, "invoke": cmdInvoke, "convert": cmdConvert, "version": cmdVersion,
	}
	if len(os.Args) < 2 {
		os.Exit(fail(2, usage))
	}
	command, ok := commands[os.Args[1]]
	if !ok {
		os.Exit(fail(2, usage))
	}
	os.Exit(command(os.Args[2:]))
}
