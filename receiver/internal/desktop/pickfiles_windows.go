//go:build windows

package desktop

import (
	"errors"
	"runtime"
	"syscall"
	"unsafe"
)

const (
	fosAllowMultiSelect = 0x00000200 // FOS_ALLOWMULTISELECT
	fosFileMustExist    = 0x00001000 // FOS_FILEMUSTEXIST
	vtGetResults        = 27         // IFileOpenDialog
	vtArrayGetCount     = 7          // IShellItemArray: 3 BindToHandler, 4 GetPropertyStore,
	vtArrayGetItemAt    = 8          // 5 GetPropertyDescriptionList, 6 GetAttributes, 7 GetCount, 8 GetItemAt
)

type pickFilesResult struct {
	paths []string
	err   error
}

// PickFiles shows the Windows file dialog with multiple selection (IFileOpenDialog). It
// returns nil and nil if the user cancelled.
func PickFiles(title string) ([]string, error) {
	if title == "" {
		title = defaultFilesTitle
	}
	if !procsAvailable(procCoCreateInstance, procCoTaskMemFree) {
		return nil, ErrUnsupported
	}
	titleP, err := syscall.UTF16PtrFromString(title)
	if err != nil {
		return nil, err
	}
	r := onSTAThread(func(comOK bool) pickFilesResult {
		if !comOK {
			return pickFilesResult{err: ErrUnsupported}
		}
		var owner uintptr
		if procsAvailable(procGetForegroundWindow) {
			owner, _, _ = procGetForegroundWindow.Call()
		}
		var dlg *comObj
		hr, _, _ := procCoCreateInstance.Call(uintptr(unsafe.Pointer(&clsidFileOpenDialog)), 0, clsctxInproc,
			uintptr(unsafe.Pointer(&iidIFileOpenDialog)), uintptr(unsafe.Pointer(&dlg)))
		if failed(hr) || dlg == nil {
			return pickFilesResult{err: hrError("CoCreateInstance(FileOpenDialog)", hr)}
		}
		defer dlg.release()
		var opts uint32
		if hr := dlg.call(vtGetOptions, uintptr(unsafe.Pointer(&opts))); failed(hr) {
			opts = 0
		}
		opts |= fosAllowMultiSelect | fosForceFS | fosFileMustExist | fosPathMustExist | fosNoChangeDir
		if hr := dlg.call(vtSetOptions, uintptr(opts)); failed(hr) {
			return pickFilesResult{err: hrError("IFileDialog.SetOptions", hr)}
		}
		dlg.call(vtSetTitle, uintptr(unsafe.Pointer(titleP)))
		hr = dlg.call(vtShow, owner)
		if failed(hr) && uint32(hr) != hrCancelled && owner != 0 {
			hr = dlg.call(vtShow, 0)
		}
		runtime.KeepAlive(titleP)
		if uint32(hr) == hrCancelled {
			return pickFilesResult{}
		}
		if failed(hr) {
			return pickFilesResult{err: hrError("IFileDialog.Show", hr)}
		}
		var arr *comObj
		if hr := dlg.call(vtGetResults, uintptr(unsafe.Pointer(&arr))); failed(hr) || arr == nil {
			return pickFilesResult{err: hrError("IFileOpenDialog.GetResults", hr)}
		}
		defer arr.release()
		var n uint32
		if hr := arr.call(vtArrayGetCount, uintptr(unsafe.Pointer(&n))); failed(hr) {
			return pickFilesResult{err: hrError("IShellItemArray.GetCount", hr)}
		}
		var paths []string
		for i := uint32(0); i < n && len(paths) < maxPicked; i++ {
			var item *comObj
			if hr := arr.call(vtArrayGetItemAt, uintptr(i), uintptr(unsafe.Pointer(&item))); failed(hr) || item == nil {
				continue
			}
			var name *uint16
			if hr := item.call(vtGetDisplayName, sigdnFileSysPath, uintptr(unsafe.Pointer(&name))); !failed(hr) && name != nil {
				paths = append(paths, utf16PtrToString(name))
			}
			coTaskMemFree(unsafe.Pointer(name))
			item.release()
		}
		if len(paths) == 0 && n > 0 {
			return pickFilesResult{err: errors.New("the chosen files aren't normal files on this computer")}
		}
		return pickFilesResult{paths: paths}
	})
	return r.paths, r.err
}
