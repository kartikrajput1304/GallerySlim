//go:build windows

package desktop

// Windows: Install copies the program to %LOCALAPPDATA%\Programs\GallerySlim Receiver\ and
// adds a Start-menu shortcut (IShellLinkW + IPersistFile through COM, no PowerShell). Start
// with Windows is the value "GallerySlim Receiver" = "<exe>" --background in
// HKCU\Software\Microsoft\Windows\CurrentVersion\Run (advapi32, per user, no admin rights).

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"syscall"
	"unsafe"
)

var (
	modadvapi32          = syscall.NewLazyDLL(sysDir + "advapi32.dll")
	procRegCreateKeyExW  = modadvapi32.NewProc("RegCreateKeyExW")
	procRegSetValueExW   = modadvapi32.NewProc("RegSetValueExW")
	procRegDeleteValueW  = modadvapi32.NewProc("RegDeleteValueW")
	procRegQueryValueExW = modadvapi32.NewProc("RegQueryValueExW")
	procRegCloseKey      = modadvapi32.NewProc("RegCloseKey")

	folderIDPrograms     = mustGUID("{A77F5D77-2E2B-44C3-A6A2-ABA601054A51}") // FOLDERID_Programs
	folderIDLocalAppData = mustGUID("{F1B32785-6FBA-4FCF-9D55-7B8E7F157091}") // FOLDERID_LocalAppData
	clsidShellLink       = mustGUID("{00021401-0000-0000-C000-000000000046}") // CLSID_ShellLink
	iidIShellLinkW       = mustGUID("{000214F9-0000-0000-C000-000000000046}") // IID_IShellLinkW
	iidIPersistFile      = mustGUID("{0000010B-0000-0000-C000-000000000046}") // IID_IPersistFile
)

const (
	hkeyCurrentUser = 0x80000001
	keyQueryValue   = 0x0001
	keySetValue     = 0x0002
	regSZ           = 1
	errorFileNotFnd = 2
	runKeyPath      = `Software\Microsoft\Windows\CurrentVersion\Run`

	// IShellLinkW (shobjidl_core.h): 0-2 IUnknown, 3 GetPath, 4 GetIDList, 5 SetIDList,
	// 6 GetDescription, 7 SetDescription, 8 GetWorkingDirectory, 9 SetWorkingDirectory,
	// 10 GetArguments, 11 SetArguments, 12 GetHotkey, 13 SetHotkey, 14 GetShowCmd,
	// 15 SetShowCmd, 16 GetIconLocation, 17 SetIconLocation, 18 SetRelativePath, 19 Resolve,
	// 20 SetPath. IPersistFile: 3 GetClassID, 4 IsDirty, 5 Load, 6 Save.
	vtQueryInterface      = 0
	vtSLSetDescription    = 7
	vtSLSetWorkingDir     = 9
	vtSLSetIconLocation   = 17
	vtSLSetPath           = 20
	vtPersistFileSave     = 6
	shortcutName          = "GallerySlim Receiver.lnk"
	installFolderName     = "GallerySlim Receiver"
	shortcutDescription   = "Receive photos from the GallerySlim phone app over Wi-Fi"
	errInstalledIsRunning = "the installed receiver is running; quit it (tray icon > Quit) and try again"
)

func installDir() (string, error) {
	base, err := knownFolderPath(&folderIDLocalAppData)
	if err != nil || base == "" {
		base = os.Getenv("LOCALAPPDATA")
	}
	if base == "" || !filepath.IsAbs(base) {
		return "", errors.New("no local application data folder")
	}
	return filepath.Join(base, "Programs", installFolderName), nil
}

func installedExe() (string, error) {
	dir, err := installDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, exeName+".exe"), nil
}

func openRunKey(access uint32) (syscall.Handle, error) {
	if !procsAvailable(procRegCreateKeyExW, procRegCloseKey) {
		return 0, ErrUnsupported
	}
	sub, err := syscall.UTF16PtrFromString(runKeyPath)
	if err != nil {
		return 0, err
	}
	var h syscall.Handle
	r, _, _ := procRegCreateKeyExW.Call(hkeyCurrentUser, uintptr(unsafe.Pointer(sub)), 0, 0, 0,
		uintptr(access), 0, uintptr(unsafe.Pointer(&h)), 0)
	runtime.KeepAlive(sub)
	if r != 0 {
		return 0, fmt.Errorf("opening the Run key failed: %w", syscall.Errno(r))
	}
	return h, nil
}

// loginExe is the program the Run value starts: the installed copy if there is one.
func loginExe() (string, error) {
	if Installed() {
		return installedExe()
	}
	return currentExe()
}

// SetStartAtLogin adds or removes the Run value.
func SetStartAtLogin(enabled bool) error {
	if !procsAvailable(procRegSetValueExW, procRegDeleteValueW) {
		return ErrUnsupported
	}
	h, err := openRunKey(keySetValue)
	if err != nil {
		return err
	}
	defer procRegCloseKey.Call(uintptr(h))
	name, err := syscall.UTF16PtrFromString(runValueName)
	if err != nil {
		return err
	}
	if !enabled {
		r, _, _ := procRegDeleteValueW.Call(uintptr(h), uintptr(unsafe.Pointer(name)))
		runtime.KeepAlive(name)
		if r != 0 && r != errorFileNotFnd {
			return fmt.Errorf("removing the Run value failed: %w", syscall.Errno(r))
		}
		return nil
	}
	exe, err := loginExe()
	if err != nil {
		return err
	}
	cmd, err := windowsRunValue(exe)
	if err != nil {
		return err
	}
	data, err := syscall.UTF16FromString(cmd)
	if err != nil {
		return err
	}
	r, _, _ := procRegSetValueExW.Call(uintptr(h), uintptr(unsafe.Pointer(name)), 0, regSZ,
		uintptr(unsafe.Pointer(&data[0])), uintptr(len(data)*2))
	runtime.KeepAlive(name)
	runtime.KeepAlive(data)
	if r != 0 {
		return fmt.Errorf("writing the Run value failed: %w", syscall.Errno(r))
	}
	return nil
}

// StartAtLogin reports whether the Run value exists.
func StartAtLogin() bool {
	if !procsAvailable(procRegQueryValueExW) {
		return false
	}
	h, err := openRunKey(keyQueryValue)
	if err != nil {
		return false
	}
	defer procRegCloseKey.Call(uintptr(h))
	name, err := syscall.UTF16PtrFromString(runValueName)
	if err != nil {
		return false
	}
	var typ, size uint32
	r, _, _ := procRegQueryValueExW.Call(uintptr(h), uintptr(unsafe.Pointer(name)), 0,
		uintptr(unsafe.Pointer(&typ)), 0, uintptr(unsafe.Pointer(&size)))
	runtime.KeepAlive(name)
	return r == 0 && size > 2
}

// InstallSupported is true on Windows.
func InstallSupported() bool { return true }

// Installed reports whether the installed copy exists.
func Installed() bool {
	exe, err := installedExe()
	if err != nil {
		return false
	}
	fi, err := os.Stat(exe)
	return err == nil && fi.Mode().IsRegular()
}

// InstallSelf copies this program to %LOCALAPPDATA%\Programs\GallerySlim Receiver\ and adds
// a Start-menu shortcut. A Run value, if on, is pointed at the installed copy.
func InstallSelf() (string, error) {
	dst, err := installedExe()
	if err != nil {
		return "", err
	}
	exe, err := currentExe()
	if err != nil {
		return "", err
	}
	if !samePathOS(exe, dst, true) {
		if err := copyFileAtomic(exe, dst, 0o755); err != nil {
			if Installed() {
				return "", errors.New(errInstalledIsRunning)
			}
			return "", err
		}
	}
	programs, err := knownFolderPath(&folderIDPrograms)
	if err != nil || programs == "" {
		if app := os.Getenv("APPDATA"); app != "" {
			programs = filepath.Join(app, "Microsoft", "Windows", "Start Menu", "Programs")
		}
	}
	if programs != "" {
		if err := createShortcut(filepath.Join(programs, shortcutName), dst); err != nil {
			return dst, fmt.Errorf("installed, but the Start menu entry failed: %w", err)
		}
	}
	if StartAtLogin() {
		if err := SetStartAtLogin(true); err != nil {
			return dst, err
		}
	}
	return dst, nil
}

// createShortcut writes a .lnk to target with IShellLinkW and IPersistFile.Save.
func createShortcut(lnk, target string) error {
	if !procsAvailable(procCoCreateInstance) {
		return ErrUnsupported
	}
	lnkP, err := syscall.UTF16PtrFromString(lnk)
	if err != nil {
		return err
	}
	targetP, err := syscall.UTF16PtrFromString(target)
	if err != nil {
		return err
	}
	dirP, err := syscall.UTF16PtrFromString(filepath.Dir(target))
	if err != nil {
		return err
	}
	descP, err := syscall.UTF16PtrFromString(shortcutDescription)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(lnk), 0o755); err != nil {
		return err
	}
	return onSTAThread(func(comOK bool) error {
		if !comOK {
			return errors.New("COM is not available")
		}
		var link *comObj
		hr, _, _ := procCoCreateInstance.Call(uintptr(unsafe.Pointer(&clsidShellLink)), 0, clsctxInproc,
			uintptr(unsafe.Pointer(&iidIShellLinkW)), uintptr(unsafe.Pointer(&link)))
		if failed(hr) || link == nil {
			return hrError("CoCreateInstance(ShellLink)", hr)
		}
		defer link.release()
		if hr := link.call(vtSLSetPath, uintptr(unsafe.Pointer(targetP))); failed(hr) {
			return hrError("IShellLink.SetPath", hr)
		}
		link.call(vtSLSetWorkingDir, uintptr(unsafe.Pointer(dirP)))
		link.call(vtSLSetDescription, uintptr(unsafe.Pointer(descP)))
		link.call(vtSLSetIconLocation, uintptr(unsafe.Pointer(targetP)), 0)
		var pf *comObj
		if hr := link.call(vtQueryInterface, uintptr(unsafe.Pointer(&iidIPersistFile)), uintptr(unsafe.Pointer(&pf))); failed(hr) || pf == nil {
			return hrError("QueryInterface(IPersistFile)", hr)
		}
		defer pf.release()
		if hr := pf.call(vtPersistFileSave, uintptr(unsafe.Pointer(lnkP)), 1); failed(hr) {
			return hrError("IPersistFile.Save", hr)
		}
		runtime.KeepAlive(lnkP)
		runtime.KeepAlive(targetP)
		runtime.KeepAlive(dirP)
		runtime.KeepAlive(descP)
		return nil
	})
}
