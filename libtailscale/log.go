// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// Gratefully borrowed from Gio UI https://gioui.org/ under MIT license

package libtailscale

/*
#cgo LDFLAGS: -llog

#include <stdlib.h>
#include <android/log.h>
*/
import "C"

import (
	"bufio"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"runtime/debug"
	"syscall"
	"unsafe"
)

// 1024 is the truncation limit from android/log.h, plus a \n.
const logLineLimit = 1024

var ID = filepath.Base(os.Args[0])

var logTag = C.CString(ID)

func initLogging(appCtx AppContext) {
	// Android's logcat already includes timestamps.
	log.SetFlags(log.Flags() &^ log.LstdFlags)
	log.SetOutput(&androidLogWriter{
		appCtx: appCtx,
	})

	// Redirect stdout and stderr to the Android logger.
	logFd(os.Stdout.Fd())
	logFd(os.Stderr.Fd())
}

type androidLogWriter struct {
	appCtx AppContext
}

func (w *androidLogWriter) Write(data []byte) (int, error) {
	n := 0
	for len(data) > 0 {
		msg := data
		// Truncate the buffer
		if len(msg) > logLineLimit {
			msg = msg[:logLineLimit]
		}
		w.appCtx.Log(ID, string(msg))
		n += len(msg)
		data = data[len(msg):]
	}
	return n, nil
}

func logFd(fd uintptr) {
	r, w, err := os.Pipe()
	if err != nil {
		panic(err)
	}
	if err := syscall.Dup3(int(w.Fd()), int(fd), syscall.O_CLOEXEC); err != nil {
		panic(err)
	}
	go func() {
		defer func() {
			if p := recover(); p != nil {
				log.Printf("panic in logFd %s: %s", p, debug.Stack())
				panic(p)
			}
		}()

		lineBuf := bufio.NewReaderSize(r, logLineLimit)
		// The buffer to pass to C, including the terminating '\0'.
		buf := make([]byte, lineBuf.Size()+1)
		cbuf := (*C.char)(unsafe.Pointer(&buf[0]))
		for {
			line, _, err := lineBuf.ReadLine()
			if err != nil {
				break
			}
			copy(buf, line)
			buf[len(line)] = 0
			C.__android_log_write(C.ANDROID_LOG_INFO, logTag, cbuf)
		}
		// The garbage collector doesn't know that w's fd was dup'ed.
		// Avoid finalizing w, and thereby avoid its finalizer closing its fd.
		runtime.KeepAlive(w)
	}()
}
