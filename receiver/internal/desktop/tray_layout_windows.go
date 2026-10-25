//go:build windows

package desktop

import "unsafe"

// Compile-time checks that the Win32 structures have the sizes of the SDK headers
// (NOTIFYICONDATAW 956/976, WNDCLASSEXW 48/80, MSG 28/48 bytes on 32/64-bit): a mismatch
// makes the index below out of range, so the build fails instead of the call.
const ptrSize = unsafe.Sizeof(uintptr(0))

var (
	_ = [1]struct{}{}[unsafe.Sizeof(notifyIconData{})-(936+5*ptrSize)]
	_ = [1]struct{}{}[(936+5*ptrSize)-unsafe.Sizeof(notifyIconData{})]
	_ = [1]struct{}{}[unsafe.Sizeof(wndClassExW{})-(16+8*ptrSize)]
	_ = [1]struct{}{}[(16+8*ptrSize)-unsafe.Sizeof(wndClassExW{})]
	_ = [1]struct{}{}[unsafe.Sizeof(winMsg{})-(8+5*ptrSize)]
	_ = [1]struct{}{}[(8+5*ptrSize)-unsafe.Sizeof(winMsg{})]
)
