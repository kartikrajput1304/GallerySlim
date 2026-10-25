//go:build !unix && !windows

package desktop

// PickFiles is not available here.
func PickFiles(title string) ([]string, error) { return nil, ErrUnsupported }
