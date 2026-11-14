package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"time"
)

// config is what the app passes to start (JSON). Nothing of it is visible outside the app's process.
type config struct {
	Dir        string   `json:"dir"`
	Hostname   string   `json:"hostname"`
	Port       string   `json:"port"`
	Target     string   `json:"target"`
	Secret     string   `json:"secret"`
	AuthKey    string   `json:"auth_key"`
	ControlURL string   `json:"control_url"`
	DNS        []string `json:"dns"`
}

var (
	lifeMu   sync.Mutex
	running  bool
	quitCh   chan struct{}
	commands = make(chan string, 8)
	lines    = make(chan string, 512)
)

// put queues one line for the app. When the app does not read (it is busy, or nobody listens), the oldest line goes.
func put(l string) {
	for {
		select {
		case lines <- l:
			return
		default:
			select {
			case <-lines:
			default:
			}
		}
	}
}

func logln(l string)          { put("L " + l) }
func logf(f string, a ...any) { logln(fmt.Sprintf(f, a...)) }

// tsStart starts the node unless one is running. 0 = started, 1 = one is already running, 2 = bad config.
//
//export tsStart
func tsStart(cfg *C.char) C.int {
	var c config
	if err := json.Unmarshal([]byte(C.GoString(cfg)), &c); err != nil {
		logln("bad config: " + err.Error())
		return 2
	}
	lifeMu.Lock()
	defer lifeMu.Unlock()
	if running {
		return 1
	}
	running = true
	q := make(chan struct{})
	quitCh = q
	// Commands meant for an earlier node are dropped.
	for len(commands) > 0 {
		<-commands
	}
	go func() {
		code := 1
		func() {
			defer func() {
				if r := recover(); r != nil {
					logf("node crashed: %v", r)
					emit(status{State: "Error", Error: fmt.Sprint("crashed: ", r)})
				}
			}()
			code = run(c, q)
		}()
		// "X" is queued before running turns false: a reader that saw running == false and emptied the queue
		// cannot get this node's "X" afterwards and take it for the next node's.
		put(fmt.Sprintf("X %d", code))
		lifeMu.Lock()
		running = false
		if quitCh == q {
			quitCh = nil
		}
		lifeMu.Unlock()
	}()
	return 0
}

// tsCommand: "login", "logout", or "quit" (ends the node; returns at once, "X" follows when it has closed).
//
//export tsCommand
func tsCommand(cmd *C.char) {
	s := strings.TrimSpace(C.GoString(cmd))
	if s == "quit" {
		lifeMu.Lock()
		if quitCh != nil {
			logln("quit: asked by the app")
			close(quitCh)
			quitCh = nil
		}
		lifeMu.Unlock()
		return
	}
	select {
	case commands <- s:
	default:
	}
}

// tsRunning: whether a node is running (or still closing).
//
//export tsRunning
func tsRunning() C.int {
	lifeMu.Lock()
	defer lifeMu.Unlock()
	if running {
		return 1
	}
	return 0
}

// tsNext waits up to [ms] for the next line. It returns a malloc'd UTF-8 string and its length in *n (the caller
// frees it), or nil when nothing came.
//
//export tsNext
func tsNext(ms C.int, n *C.int) *C.char {
	select {
	case l := <-lines:
		*n = C.int(len(l))
		return (*C.char)(C.CBytes([]byte(l)))
	case <-time.After(time.Duration(ms) * time.Millisecond):
		*n = 0
		return nil
	}
}

// A c-shared library still needs a main package with a main function; it is never called.
func main() {}
