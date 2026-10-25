//go:build windows

package desktop

// The Windows notification-area ("tray") icon: Shell_NotifyIconW with a hidden window that
// receives its clicks, and a menu Open / Start with Windows / Quit. All window calls happen
// on one locked OS thread running the message loop.
//
// The window is a hidden top-level window rather than a message-only (HWND_MESSAGE) one:
// message-only windows don't receive the "TaskbarCreated" broadcast that Explorer sends after
// it restarts (the icon must be added again then), and TrackPopupMenu needs a window that can
// become the foreground window so the menu closes when the user clicks elsewhere. It is never
// shown and has no taskbar button.

import (
	_ "embed"
	"errors"
	"fmt"
	"runtime"
	"sync"
	"syscall"
	"unsafe"
)

//go:embed tray.ico
var trayIcon []byte

var (
	procGetModuleHandleW         = modkernel32.NewProc("GetModuleHandleW")
	procGetConsoleProcessList    = modkernel32.NewProc("GetConsoleProcessList")
	procFreeConsole              = modkernel32.NewProc("FreeConsole")
	procRegisterClassExW         = moduser32.NewProc("RegisterClassExW")
	procCreateWindowExW          = moduser32.NewProc("CreateWindowExW")
	procDestroyWindow            = moduser32.NewProc("DestroyWindow")
	procDefWindowProcW           = moduser32.NewProc("DefWindowProcW")
	procGetMessageW              = moduser32.NewProc("GetMessageW")
	procTranslateMessage         = moduser32.NewProc("TranslateMessage")
	procDispatchMessageW         = moduser32.NewProc("DispatchMessageW")
	procPostMessageW             = moduser32.NewProc("PostMessageW")
	procPostQuitMessage          = moduser32.NewProc("PostQuitMessage")
	procRegisterWindowMessageW   = moduser32.NewProc("RegisterWindowMessageW")
	procCreatePopupMenu          = moduser32.NewProc("CreatePopupMenu")
	procAppendMenuW              = moduser32.NewProc("AppendMenuW")
	procTrackPopupMenu           = moduser32.NewProc("TrackPopupMenu")
	procDestroyMenu              = moduser32.NewProc("DestroyMenu")
	procSetForegroundWindow      = moduser32.NewProc("SetForegroundWindow")
	procGetCursorPos             = moduser32.NewProc("GetCursorPos")
	procGetSystemMetrics         = moduser32.NewProc("GetSystemMetrics")
	procCreateIconFromResourceEx = moduser32.NewProc("CreateIconFromResourceEx")
	procDestroyIcon              = moduser32.NewProc("DestroyIcon")
	procShellNotifyIconW         = modshell32.NewProc("Shell_NotifyIconW")
)

const (
	wmNull        = 0x0000
	wmDestroy     = 0x0002
	wmClose       = 0x0010
	wmCommand     = 0x0111
	wmLButtonUp   = 0x0202
	wmLButtonDbl  = 0x0203
	wmRButtonUp   = 0x0205
	wmContextMenu = 0x007B
	wmApp         = 0x8000
	wmTrayCB      = wmApp + 1 // icon callback
	wmTrayTip     = wmApp + 2 // tooltip changed

	nimAdd     = 0
	nimModify  = 1
	nimDelete  = 2
	nifMessage = 0x1
	nifIcon    = 0x2
	nifTip     = 0x4

	mfString    = 0x0
	mfChecked   = 0x8
	mfSeparator = 0x800

	tpmRightButton = 0x0002
	tpmNoNotify    = 0x0080
	tpmReturnCmd   = 0x0100

	smCxSmIcon = 49
	smCySmIcon = 50

	cmdOpen  = 1
	cmdLogin = 2
	cmdQuit  = 3

	trayID = 1
)

// notifyIconData is NOTIFYICONDATAW (shellapi.h); Go's field alignment matches the C layout
// on 386, amd64 and arm64.
type notifyIconData struct {
	CbSize           uint32
	HWnd             uintptr
	UID              uint32
	UFlags           uint32
	UCallbackMessage uint32
	HIcon            uintptr
	SzTip            [128]uint16
	DwState          uint32
	DwStateMask      uint32
	SzInfo           [256]uint16
	UVersion         uint32
	SzInfoTitle      [64]uint16
	DwInfoFlags      uint32
	GuidItem         guid
	HBalloonIcon     uintptr
}

type wndClassExW struct {
	CbSize        uint32
	Style         uint32
	LpfnWndProc   uintptr
	CbClsExtra    int32
	CbWndExtra    int32
	HInstance     uintptr
	HIcon         uintptr
	HCursor       uintptr
	HbrBackground uintptr
	LpszMenuName  *uint16
	LpszClassName *uint16
	HIconSm       uintptr
}

type point struct{ X, Y int32 }

type winMsg struct {
	HWnd    uintptr
	Message uint32
	WParam  uintptr
	LParam  uintptr
	Time    uint32
	Pt      point
}

// Tray is the running notification-area icon.
type Tray struct {
	opts          TrayOptions
	hwnd          uintptr
	icon          uintptr
	taskbarCreate uint32

	mu   sync.Mutex
	tip  string
	done chan struct{}
}

var (
	trayMu      sync.Mutex
	currentTray *Tray // one icon per process; the window procedure finds it here
	wndProcCB   uintptr
	wndProcOnce sync.Once
)

// StartTray shows the icon. It returns after the icon was added (or failed).
func StartTray(o TrayOptions) (*Tray, error) {
	if !procsAvailable(procShellNotifyIconW, procRegisterClassExW, procCreateWindowExW, procGetMessageW,
		procCreateIconFromResourceEx, procTrackPopupMenu, procGetModuleHandleW) {
		return nil, ErrUnsupported
	}
	trayMu.Lock()
	if currentTray != nil {
		trayMu.Unlock()
		return nil, errors.New("tray icon already shown")
	}
	t := &Tray{opts: o, tip: o.Tooltip, done: make(chan struct{})}
	currentTray = t
	trayMu.Unlock()
	wndProcOnce.Do(func() { wndProcCB = syscall.NewCallback(trayWndProc) })

	started := make(chan error, 1)
	go func() {
		runtime.LockOSThread() // never unlocked: window and loop live on this thread
		defer close(t.done)
		if err := t.create(); err != nil {
			trayMu.Lock()
			currentTray = nil
			trayMu.Unlock()
			started <- err
			return
		}
		started <- nil
		var m winMsg
		for {
			r, _, _ := procGetMessageW.Call(uintptr(unsafe.Pointer(&m)), 0, 0, 0)
			if int32(r) <= 0 { // WM_QUIT or error
				break
			}
			procTranslateMessage.Call(uintptr(unsafe.Pointer(&m)))
			procDispatchMessageW.Call(uintptr(unsafe.Pointer(&m)))
		}
		trayMu.Lock()
		currentTray = nil
		trayMu.Unlock()
	}()
	if err := <-started; err != nil {
		return nil, err
	}
	return t, nil
}

func (t *Tray) create() error {
	hinst, _, _ := procGetModuleHandleW.Call(0)
	className, _ := syscall.UTF16PtrFromString("GallerySlimReceiverTray")
	title, _ := syscall.UTF16PtrFromString("GallerySlim Receiver")
	wc := wndClassExW{LpfnWndProc: wndProcCB, HInstance: hinst, LpszClassName: className}
	wc.CbSize = uint32(unsafe.Sizeof(wc))
	procRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc))) // fails harmlessly if already registered
	hwnd, _, err := procCreateWindowExW.Call(0, uintptr(unsafe.Pointer(className)), uintptr(unsafe.Pointer(title)),
		0, 0, 0, 0, 0, 0, 0, hinst, 0)
	runtime.KeepAlive(className)
	runtime.KeepAlive(title)
	if hwnd == 0 {
		return fmt.Errorf("CreateWindowEx failed: %v", err)
	}
	t.hwnd = hwnd
	if msgName, err := syscall.UTF16PtrFromString("TaskbarCreated"); err == nil {
		r, _, _ := procRegisterWindowMessageW.Call(uintptr(unsafe.Pointer(msgName)))
		t.taskbarCreate = uint32(r)
	}
	t.icon = loadTrayIcon()
	if !t.notify(nimAdd) {
		procDestroyWindow.Call(hwnd)
		return errors.New("Shell_NotifyIcon failed")
	}
	return nil
}

func loadTrayIcon() uintptr {
	cx, _, _ := procGetSystemMetrics.Call(smCxSmIcon)
	cy, _, _ := procGetSystemMetrics.Call(smCySmIcon)
	if cx == 0 {
		cx, cy = 16, 16
	}
	data, err := icoEntry(trayIcon, int(cx))
	if err != nil {
		return 0
	}
	h, _, _ := procCreateIconFromResourceEx.Call(uintptr(unsafe.Pointer(&data[0])), uintptr(len(data)), 1,
		0x00030000, cx, cy, 0)
	runtime.KeepAlive(data)
	return h
}

func (t *Tray) notify(op uintptr) bool {
	var nid notifyIconData
	nid.CbSize = uint32(unsafe.Sizeof(nid))
	nid.HWnd = t.hwnd
	nid.UID = trayID
	if op != nimDelete {
		nid.UFlags = nifMessage | nifTip
		nid.UCallbackMessage = wmTrayCB
		if t.icon != 0 {
			nid.UFlags |= nifIcon
			nid.HIcon = t.icon
		}
		t.mu.Lock()
		copy(nid.SzTip[:127], trayTip(t.tip))
		t.mu.Unlock()
	}
	r, _, _ := procShellNotifyIconW.Call(op, uintptr(unsafe.Pointer(&nid)))
	return r != 0
}

// SetTooltip changes the text shown when the pointer rests on the icon.
func (t *Tray) SetTooltip(s string) {
	if t == nil {
		return
	}
	t.mu.Lock()
	changed := t.tip != s
	t.tip = s
	t.mu.Unlock()
	if changed {
		procPostMessageW.Call(t.hwnd, wmTrayTip, 0, 0)
	}
}

// Close removes the icon and ends its thread.
func (t *Tray) Close() {
	if t == nil {
		return
	}
	procPostMessageW.Call(t.hwnd, wmClose, 0, 0)
	<-t.done
}

func (t *Tray) showMenu() {
	menu, _, _ := procCreatePopupMenu.Call()
	if menu == 0 {
		return
	}
	defer procDestroyMenu.Call(menu)
	add := func(flags, id uintptr, text string) {
		p, _ := syscall.UTF16PtrFromString(text)
		procAppendMenuW.Call(menu, flags, id, uintptr(unsafe.Pointer(p)))
		runtime.KeepAlive(p)
	}
	add(mfString, cmdOpen, "Open")
	loginFlags := uintptr(mfString)
	if StartAtLogin() {
		loginFlags |= mfChecked
	}
	add(loginFlags, cmdLogin, "Start with Windows")
	add(mfSeparator, 0, "")
	add(mfString, cmdQuit, "Quit")
	var pt point
	procGetCursorPos.Call(uintptr(unsafe.Pointer(&pt)))
	procSetForegroundWindow.Call(t.hwnd)
	cmd, _, _ := procTrackPopupMenu.Call(menu, tpmReturnCmd|tpmRightButton|tpmNoNotify,
		uintptr(pt.X), uintptr(pt.Y), 0, t.hwnd, 0)
	procPostMessageW.Call(t.hwnd, wmNull, 0, 0) // lets the menu close properly (KB135788)
	t.command(cmd)
}

// command runs a menu choice off the window thread, so a slow action never blocks the loop.
func (t *Tray) command(cmd uintptr) {
	switch cmd {
	case cmdOpen:
		if t.opts.OnOpen != nil {
			go t.opts.OnOpen()
		}
	case cmdLogin:
		go func() {
			on := !StartAtLogin()
			err := SetStartAtLogin(on)
			if t.opts.OnStartAtLogin != nil {
				t.opts.OnStartAtLogin(on, err)
			}
		}()
	case cmdQuit:
		if t.opts.OnQuit != nil {
			go t.opts.OnQuit()
		}
	}
}

func trayWndProc(hwnd, msg, wparam, lparam uintptr) uintptr {
	trayMu.Lock()
	t := currentTray
	trayMu.Unlock()
	if t == nil || t.hwnd != hwnd {
		r, _, _ := procDefWindowProcW.Call(hwnd, msg, wparam, lparam)
		return r
	}
	switch uint32(msg) {
	case wmTrayCB:
		switch uint32(lparam) & 0xFFFF {
		case wmLButtonUp, wmLButtonDbl:
			t.command(cmdOpen)
		case wmRButtonUp, wmContextMenu:
			t.showMenu()
		}
		return 0
	case wmTrayTip:
		t.notify(nimModify)
		return 0
	case wmClose:
		procDestroyWindow.Call(hwnd)
		return 0
	case wmDestroy:
		t.notify(nimDelete)
		if t.icon != 0 {
			procDestroyIcon.Call(t.icon)
		}
		procPostQuitMessage.Call(0)
		return 0
	}
	if t.taskbarCreate != 0 && uint32(msg) == t.taskbarCreate { // Explorer restarted
		t.notify(nimAdd)
		return 0
	}
	r, _, _ := procDefWindowProcW.Call(hwnd, msg, wparam, lparam)
	return r
}

// DetachConsole closes the console window when this program is the only one using it (it
// was started by the login entry or a double-click, not from a terminal). Background mode
// calls it so no console window stays open at login.
func DetachConsole() {
	if !procsAvailable(procGetConsoleProcessList, procFreeConsole) {
		return
	}
	var ids [2]uint32
	n, _, _ := procGetConsoleProcessList.Call(uintptr(unsafe.Pointer(&ids[0])), 2)
	if n == 1 {
		procFreeConsole.Call()
	}
}
