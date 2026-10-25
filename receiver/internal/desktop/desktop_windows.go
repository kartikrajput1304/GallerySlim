//go:build windows

package desktop

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"syscall"
	"unsafe"
)

// System DLLs are loaded by absolute path from the System32 folder, so a DLL
// planted next to the receiver can't be picked up instead (all four are also
// KnownDLLs, which Windows maps from System32 anyway).
var (
	modkernel32 = syscall.NewLazyDLL("kernel32.dll") // already loaded by the Go runtime
	sysDir      = systemDirectory()
	modole32    = syscall.NewLazyDLL(sysDir + "ole32.dll")
	modshell32  = syscall.NewLazyDLL(sysDir + "shell32.dll")
	moduser32   = syscall.NewLazyDLL(sysDir + "user32.dll")

	procGetSystemDirectoryW     = modkernel32.NewProc("GetSystemDirectoryW")
	procSetThreadExecutionState = modkernel32.NewProc("SetThreadExecutionState")

	procCoInitializeEx   = modole32.NewProc("CoInitializeEx")
	procCoUninitialize   = modole32.NewProc("CoUninitialize")
	procCoCreateInstance = modole32.NewProc("CoCreateInstance")
	procCoTaskMemFree    = modole32.NewProc("CoTaskMemFree")

	procSHCreateItemFromParsingName = modshell32.NewProc("SHCreateItemFromParsingName")
	procSHBrowseForFolderW          = modshell32.NewProc("SHBrowseForFolderW")
	procSHGetPathFromIDListW        = modshell32.NewProc("SHGetPathFromIDListW")
	procSHGetPathFromIDListEx       = modshell32.NewProc("SHGetPathFromIDListEx")
	procSHGetKnownFolderPath        = modshell32.NewProc("SHGetKnownFolderPath")
	procShellExecuteW               = modshell32.NewProc("ShellExecuteW")

	procGetForegroundWindow = moduser32.NewProc("GetForegroundWindow")
	procSendMessageW        = moduser32.NewProc("SendMessageW")
)

var (
	folderIDPictures    = mustGUID(guidFolderIDPictures)
	clsidFileOpenDialog = mustGUID(guidCLSIDFileOpenDialog)
	iidIFileOpenDialog  = mustGUID(guidIIDIFileOpenDialog)
	iidIShellItem       = mustGUID(guidIIDIShellItem)
)

const (
	sOK              = 0x00000000
	sFalse           = 0x00000001
	hrCancelled      = 0x800704C7 // HRESULT_FROM_WIN32(ERROR_CANCELLED): user closed the dialog
	coinitApartment  = 0x2        // COINIT_APARTMENTTHREADED
	coinitNoOLE1DDE  = 0x4        // COINIT_DISABLE_OLE1DDE
	clsctxInproc     = 0x1        // CLSCTX_INPROC_SERVER
	fosNoChangeDir   = 0x00000008 // FOS_NOCHANGEDIR
	fosPickFolders   = 0x00000020 // FOS_PICKFOLDERS
	fosForceFS       = 0x00000040 // FOS_FORCEFILESYSTEM
	fosPathMustExist = 0x00000800 // FOS_PATHMUSTEXIST
	sigdnFileSysPath = 0x80058000 // SIGDN_FILESYSPATH
	kfFlagDontVerify = 0x00004000 // KF_FLAG_DONT_VERIFY

	bifReturnOnlyFSDirs = 0x0001 // BIF_RETURNONLYFSDIRS
	bifNewDialogStyle   = 0x0040 // BIF_NEWDIALOGSTYLE
	bffmInitialized     = 1      // BFFM_INITIALIZED
	bffmSetSelectionW   = 0x0467 // BFFM_SETSELECTIONW = WM_USER + 103

	swShowNormal     = 1          // SW_SHOWNORMAL
	esContinuous     = 0x80000000 // ES_CONTINUOUS
	esSystemRequired = 0x00000001 // ES_SYSTEM_REQUIRED
	maxPath          = 260        // MAX_PATH
	maxLongPath      = 32768      // longest \\?\ path in UTF-16 units
)

// COM vtable slots. A COM interface pointer points at a pointer to a table of
// function pointers; each interface's methods follow those of the interface it
// derives from, in header declaration order (shobjidl_core.h):
//
//	IUnknown:        0 QueryInterface, 1 AddRef, 2 Release
//	IModalWindow:    3 Show
//	IFileDialog:     4 SetFileTypes, 5 SetFileTypeIndex, 6 GetFileTypeIndex,
//	                 7 Advise, 8 Unadvise, 9 SetOptions, 10 GetOptions,
//	                 11 SetDefaultFolder, 12 SetFolder, 13 GetFolder,
//	                 14 GetCurrentSelection, 15 SetFileName, 16 GetFileName,
//	                 17 SetTitle, 18 SetOkButtonLabel, 19 SetFileNameLabel,
//	                 20 GetResult, 21 AddPlace, 22 SetDefaultExtension,
//	                 23 Close, 24 SetClientGuid, 25 ClearClientData, 26 SetFilter
//	IFileOpenDialog: 27 GetResults, 28 GetSelectedItems
//	IShellItem:      3 BindToHandler, 4 GetParent, 5 GetDisplayName,
//	                 6 GetAttributes, 7 Compare
const (
	vtRelease        = 2
	vtShow           = 3
	vtSetOptions     = 9
	vtGetOptions     = 10
	vtSetFolder      = 12
	vtSetTitle       = 17
	vtGetResult      = 20
	vtGetDisplayName = 5 // IShellItem
)

// comObj is what every COM interface pointer points at.
type comObj struct {
	vtbl *[32]uintptr
}

// comCall calls a raw function pointer. //go:uintptrescapes moves any Go
// memory whose address is passed (converted to uintptr in the call) to the
// heap and keeps it alive for the call, so it is safe even if Windows calls
// back into Go and the goroutine stack moves.
//
//go:uintptrescapes
func comCall(fn uintptr, args ...uintptr) uintptr {
	r, _, _ := syscall.SyscallN(fn, args...)
	return r
}

// call invokes vtable slot i with o as the implicit "this" argument. It is
// //go:uintptrescapes too, so pointers converted at its call sites stay safe.
//
//go:uintptrescapes
func (o *comObj) call(i int, args ...uintptr) uintptr {
	return comCall(o.vtbl[i], append([]uintptr{uintptr(unsafe.Pointer(o))}, args...)...)
}

func (o *comObj) release() {
	if o != nil {
		o.call(vtRelease)
	}
}

func failed(hr uintptr) bool { return int32(uint32(hr)) < 0 }

func hrError(op string, hr uintptr) error {
	return fmt.Errorf("%s failed (HRESULT 0x%08X)", op, uint32(hr))
}

// systemDirectory returns the System32 folder with a trailing backslash, or ""
// (plain DLL names) if Windows won't say.
func systemDirectory() string {
	buf := make([]uint16, maxPath)
	r, _, _ := procGetSystemDirectoryW.Call(uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)))
	n := int(uint32(r)) // UINT: count of characters copied
	if n == 0 || n >= len(buf) {
		return ""
	}
	return syscall.UTF16ToString(buf[:n]) + `\`
}

// utf16PtrToString copies a NUL-terminated UTF-16 string owned by Windows.
func utf16PtrToString(p *uint16) string {
	if p == nil {
		return ""
	}
	n := 0
	for ptr := unsafe.Pointer(p); n < maxLongPath && *(*uint16)(ptr) != 0; n++ {
		ptr = unsafe.Add(ptr, 2)
	}
	return syscall.UTF16ToString(unsafe.Slice(p, n))
}

func coTaskMemFree(p unsafe.Pointer) {
	if p != nil {
		procCoTaskMemFree.Call(uintptr(p))
	}
}

func procsAvailable(procs ...*syscall.LazyProc) bool {
	for _, p := range procs {
		if p.Find() != nil {
			return false
		}
	}
	return true
}

// onSTAThread runs f on a fresh OS thread with COM initialised as a
// single-threaded apartment, as the shell dialogs and ShellExecute require.
// comOK reports whether COM initialisation worked. The goroutine never
// unlocks its thread, so Go discards that thread afterwards instead of
// reusing one that had COM state.
func onSTAThread[T any](f func(comOK bool) T) T {
	ch := make(chan T, 1)
	go func() {
		runtime.LockOSThread()
		comOK := false
		if procsAvailable(procCoInitializeEx, procCoUninitialize) {
			hr, _, _ := procCoInitializeEx.Call(0, coinitApartment|coinitNoOLE1DDE)
			if h := uint32(hr); h == sOK || h == sFalse { // S_FALSE: already initialised; still balanced by CoUninitialize
				comOK = true
				defer procCoUninitialize.Call()
			}
		}
		ch <- f(comOK)
	}()
	return <-ch
}

type pickResult struct {
	path string
	err  error
}

// PickFolder shows the Windows folder dialog: the modern IFileOpenDialog with
// FOS_PICKFOLDERS, or SHBrowseForFolderW if that can't be created. It returns
// "" and nil if the user cancelled.
func PickFolder(title, start string) (string, error) {
	if title == "" {
		title = defaultTitle
	}
	if start != "" {
		if fi, err := os.Stat(start); err == nil && fi.IsDir() && filepath.IsAbs(start) {
			start = filepath.Clean(start)
		} else {
			start = ""
		}
	}
	r := onSTAThread(func(comOK bool) pickResult {
		// Own the dialog by the window in front (normally the browser showing
		// the receiver's page) so it opens on top of it rather than behind.
		var owner uintptr
		if procsAvailable(procGetForegroundWindow) {
			owner, _, _ = procGetForegroundWindow.Call()
		}
		if comOK {
			p, shown, err := pickFolderCOM(owner, title, start)
			if err == nil || shown {
				return pickResult{p, err}
			}
		}
		p, err := pickFolderLegacy(owner, title, start, comOK)
		return pickResult{p, err}
	})
	return r.path, r.err
}

// pickFolderCOM uses IFileOpenDialog. shown reports whether the dialog was
// actually displayed, in which case an error must not lead to a second dialog.
func pickFolderCOM(owner uintptr, title, start string) (path string, shown bool, err error) {
	if !procsAvailable(procCoCreateInstance, procCoTaskMemFree) {
		return "", false, ErrUnsupported
	}
	titleP, err := syscall.UTF16PtrFromString(title)
	if err != nil {
		return "", false, err
	}

	var dlg *comObj
	hr, _, _ := procCoCreateInstance.Call(
		uintptr(unsafe.Pointer(&clsidFileOpenDialog)), 0, clsctxInproc,
		uintptr(unsafe.Pointer(&iidIFileOpenDialog)), uintptr(unsafe.Pointer(&dlg)))
	if failed(hr) || dlg == nil {
		return "", false, hrError("CoCreateInstance(FileOpenDialog)", hr)
	}
	defer dlg.release()

	var opts uint32
	if hr := dlg.call(vtGetOptions, uintptr(unsafe.Pointer(&opts))); failed(hr) {
		opts = 0
	}
	opts |= fosPickFolders | fosForceFS | fosPathMustExist | fosNoChangeDir
	if hr := dlg.call(vtSetOptions, uintptr(opts)); failed(hr) {
		return "", false, hrError("IFileDialog.SetOptions", hr)
	}
	dlg.call(vtSetTitle, uintptr(unsafe.Pointer(titleP)))

	if start != "" && procsAvailable(procSHCreateItemFromParsingName) {
		if startP, err := syscall.UTF16PtrFromString(start); err == nil {
			var folder *comObj
			hr, _, _ := procSHCreateItemFromParsingName.Call(uintptr(unsafe.Pointer(startP)), 0,
				uintptr(unsafe.Pointer(&iidIShellItem)), uintptr(unsafe.Pointer(&folder)))
			if !failed(hr) && folder != nil {
				dlg.call(vtSetFolder, uintptr(unsafe.Pointer(folder)))
				folder.release()
			}
			runtime.KeepAlive(startP)
		}
	}

	hr = dlg.call(vtShow, owner)
	if failed(hr) && uint32(hr) != hrCancelled && owner != 0 {
		// Another program's window may be refused as owner; show it unowned.
		hr = dlg.call(vtShow, 0)
	}
	runtime.KeepAlive(titleP)
	if uint32(hr) == hrCancelled {
		return "", true, nil
	}
	if failed(hr) {
		return "", false, hrError("IFileDialog.Show", hr)
	}

	var item *comObj
	if hr := dlg.call(vtGetResult, uintptr(unsafe.Pointer(&item))); failed(hr) || item == nil {
		return "", true, hrError("IFileDialog.GetResult", hr)
	}
	defer item.release()

	var name *uint16
	if hr := item.call(vtGetDisplayName, sigdnFileSysPath, uintptr(unsafe.Pointer(&name))); failed(hr) || name == nil {
		coTaskMemFree(unsafe.Pointer(name))
		return "", true, errors.New("the chosen folder isn't a normal folder on this computer")
	}
	defer coTaskMemFree(unsafe.Pointer(name))
	return utf16PtrToString(name), true, nil
}

// browseInfo is BROWSEINFOW.
type browseInfo struct {
	Owner       uintptr
	Root        uintptr
	DisplayName *uint16
	Title       *uint16
	Flags       uint32
	Callback    uintptr
	LParam      uintptr
	Image       int32
}

var (
	browseCBOnce sync.Once
	browseCB     uintptr
)

// browseCallback selects the start folder (passed as lpData) once the
// SHBrowseForFolder dialog is ready. syscall callbacks are never freed, so
// one is made for the life of the program.
func browseCallback() uintptr {
	browseCBOnce.Do(func() {
		browseCB = syscall.NewCallback(func(hwnd, msg, lParam, data uintptr) uintptr {
			if uint32(msg) == bffmInitialized && data != 0 { // UINT: upper register bits are undefined
				procSendMessageW.Call(hwnd, bffmSetSelectionW, 1, data)
			}
			return 0
		})
	})
	return browseCB
}

// pickFolderLegacy uses SHBrowseForFolderW.
func pickFolderLegacy(owner uintptr, title, start string, comOK bool) (string, error) {
	if !procsAvailable(procSHBrowseForFolderW, procSHGetPathFromIDListW, procCoTaskMemFree) {
		return "", ErrUnsupported
	}
	titleP, err := syscall.UTF16PtrFromString(title)
	if err != nil {
		return "", err
	}
	display := make([]uint16, maxPath)
	bi := browseInfo{
		Owner:       owner,
		DisplayName: &display[0],
		Title:       titleP,
		Flags:       bifReturnOnlyFSDirs,
	}
	if comOK { // the resizable new-style dialog needs COM (STA) on this thread
		bi.Flags |= bifNewDialogStyle
	}
	var startP *uint16
	if start != "" && procsAvailable(procSendMessageW) {
		if startP, err = syscall.UTF16PtrFromString(start); err == nil {
			bi.Callback = browseCallback()
			bi.LParam = uintptr(unsafe.Pointer(startP))
		}
	}
	pidl, _, _ := procSHBrowseForFolderW.Call(uintptr(unsafe.Pointer(&bi)))
	runtime.KeepAlive(startP)
	runtime.KeepAlive(titleP)
	runtime.KeepAlive(display)
	if pidl == 0 {
		return "", nil // cancelled
	}
	defer procCoTaskMemFree.Call(pidl)

	if procsAvailable(procSHGetPathFromIDListEx) {
		buf := make([]uint16, maxLongPath)
		ok, _, _ := procSHGetPathFromIDListEx.Call(pidl, uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)), 0)
		if uint32(ok) != 0 { // BOOL
			return syscall.UTF16ToString(buf), nil
		}
	}
	buf := make([]uint16, maxPath) // SHGetPathFromIDListW assumes MAX_PATH
	if ok, _, _ := procSHGetPathFromIDListW.Call(pidl, uintptr(unsafe.Pointer(&buf[0]))); uint32(ok) == 0 {
		return "", errors.New("the chosen folder isn't a normal folder on this computer")
	}
	return syscall.UTF16ToString(buf), nil
}

// knownFolderPath wraps SHGetKnownFolderPath.
func knownFolderPath(id *guid) (string, error) {
	if !procsAvailable(procSHGetKnownFolderPath, procCoTaskMemFree) {
		return "", ErrUnsupported
	}
	var p *uint16
	hr, _, _ := procSHGetKnownFolderPath.Call(uintptr(unsafe.Pointer(id)), kfFlagDontVerify, 0,
		uintptr(unsafe.Pointer(&p)))
	defer coTaskMemFree(unsafe.Pointer(p)) // must be freed even on failure
	if failed(hr) {
		return "", hrError("SHGetKnownFolderPath", hr)
	}
	return utf16PtrToString(p), nil
}

// DefaultFolder suggests the real Pictures folder (FOLDERID_Pictures, which
// follows OneDrive folder backup), or %USERPROFILE%\GallerySlim Photos with an
// explanation if Pictures syncs to the cloud.
func DefaultFolder() (path, note string) {
	home, _ := os.UserHomeDir()
	pictures, err := knownFolderPath(&folderIDPictures)
	if err != nil {
		pictures = ""
	}
	return chooseDefault(styleWindows, pictures, home, CloudSynced)
}

// CloudSynced reports whether path lies in a OneDrive folder (by the OneDrive,
// OneDriveConsumer and OneDriveCommercial variables, or by name) or a Google
// Drive, Dropbox or iCloud folder, and names the service.
func CloudSynced(path string) (synced bool, provider string) {
	home, _ := os.UserHomeDir()
	return cloudSyncedResolved(styleWindows, path, home, os.Getenv)
}

// shellOpen runs ShellExecuteW(NULL, "open", target, NULL, NULL, SW_SHOWNORMAL).
func shellOpen(target string) error {
	if !procsAvailable(procShellExecuteW) {
		return ErrUnsupported
	}
	verbP, err := syscall.UTF16PtrFromString("open")
	if err != nil {
		return err
	}
	targetP, err := syscall.UTF16PtrFromString(target)
	if err != nil {
		return err
	}
	return onSTAThread(func(bool) error {
		r, _, _ := procShellExecuteW.Call(0, uintptr(unsafe.Pointer(verbP)), uintptr(unsafe.Pointer(targetP)),
			0, 0, swShowNormal)
		runtime.KeepAlive(verbP)
		runtime.KeepAlive(targetP)
		if r <= 32 { // values up to 32 are SE_ERR_* error codes
			return fmt.Errorf("ShellExecute failed (code %d)", r)
		}
		return nil
	})
}

// OpenURL opens an http or https link in the default browser.
func OpenURL(link string) error {
	u, err := checkURL(link)
	if err != nil {
		return err
	}
	return shellOpen(u)
}

// OpenFolder opens an existing folder in Explorer.
func OpenFolder(path string) error {
	p, err := checkFolder(path)
	if err != nil {
		return err
	}
	return shellOpen(p)
}

// KeepAwake asks Windows not to sleep (ES_CONTINUOUS|ES_SYSTEM_REQUIRED). The
// request belongs to the calling thread, so it is made from a dedicated locked
// thread that stays parked until release, which clears it with ES_CONTINUOUS.
// release is never nil and safe to call more than once.
func KeepAwake() (release func()) {
	if !procsAvailable(procSetThreadExecutionState) {
		return noRelease
	}
	stop := make(chan struct{})
	done := make(chan struct{})
	set := make(chan struct{})
	go func() {
		runtime.LockOSThread() // never unlocked: the thread ends with this goroutine
		defer close(done)
		// The result (the previous state, or 0 on failure) isn't checked: a
		// fresh thread's previous state may itself be 0, so it can't tell
		// failure apart, and parking a thread costs nothing either way.
		procSetThreadExecutionState.Call(esContinuous | esSystemRequired)
		close(set)
		<-stop
		procSetThreadExecutionState.Call(esContinuous)
	}()
	<-set // the request is in place when KeepAwake returns
	return onceRelease(func() {
		close(stop)
		<-done
	})
}
