//go:build !windows

package desktop

// Tray is the notification-area icon; other systems have none (the page is the interface).
type Tray struct{}

// StartTray is only available on Windows.
func StartTray(TrayOptions) (*Tray, error) { return nil, ErrUnsupported }

// SetTooltip does nothing here.
func (*Tray) SetTooltip(string) {}

// Close does nothing here.
func (*Tray) Close() {}

// DetachConsole does nothing outside Windows.
func DetachConsole() {}
