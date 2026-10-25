package desktop

// TrayOptions configures the notification-area icon (Windows only).
type TrayOptions struct {
	Tooltip string // at most 127 characters are shown
	OnOpen  func() // "Open" and a click on the icon
	OnQuit  func() // "Quit"
	// OnStartAtLogin is called after "Start with Windows" was toggled (with the new state
	// or the error).
	OnStartAtLogin func(enabled bool, err error)
}

// trayTip trims a tooltip to the 127 UTF-16 units Windows keeps, without splitting a pair.
func trayTip(s string) []uint16 {
	var out []uint16
	for _, r := range s {
		if r < 0x20 || r == 0x7f {
			r = ' '
		}
		var u []uint16
		if r >= 0x10000 {
			r -= 0x10000
			u = []uint16{uint16(0xD800 + (r >> 10)), uint16(0xDC00 + (r & 0x3FF))}
		} else {
			u = []uint16{uint16(r)}
		}
		if len(out)+len(u) > 127 {
			break
		}
		out = append(out, u...)
	}
	return out
}
