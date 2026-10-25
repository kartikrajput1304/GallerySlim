package app

import (
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"galleryslim/receiver/internal/gs2"
	"galleryslim/receiver/internal/textsafe"
)

// ConfigFile is the settings file inside the OS config folder. It holds the folder path, the
// computer name, the receiver id and window preferences, never any key (pair keys live in
// pairs.json).
const ConfigFile = "config.json"

// Config is the receiver's saved settings.
type Config struct {
	Folder      string `json:"folder,omitempty"`
	OpenBrowser *bool  `json:"openBrowser,omitempty"` // window preference: open the page automatically
	// RID is this receiver's id (gs2 §9), made once.
	RID string `json:"rid,omitempty"`
	// Name is the computer name shown on the phone ("" = the system's name).
	Name string `json:"name,omitempty"`
}

// WantsBrowser reports whether the page should open automatically (default yes).
func (c Config) WantsBrowser() bool { return c.OpenBrowser == nil || *c.OpenBrowser }

// LoadConfig reads the settings; a missing or unreadable file gives the defaults.
func LoadConfig(dir string) Config {
	var c Config
	if dir == "" {
		return c
	}
	f, err := os.Open(filepath.Join(dir, ConfigFile))
	if err != nil {
		return c
	}
	defer f.Close()
	if err := json.NewDecoder(io.LimitReader(f, 64<<10)).Decode(&c); err != nil {
		return Config{}
	}
	if c.Folder != "" && !filepath.IsAbs(c.Folder) {
		c.Folder = ""
	}
	if !gs2.ValidID(c.RID) {
		c.RID = ""
	}
	c.Name = CleanComputerName(c.Name)
	return c
}

// EnsureRID returns the receiver id from the settings, making and saving one the first time.
// Without a settings folder the id lives only for this run.
func EnsureRID(dir string) string {
	c := LoadConfig(dir)
	if c.RID != "" {
		return c.RID
	}
	c.RID = NewID()
	if dir != "" {
		SaveConfig(dir, c)
	}
	return c.RID
}

// SaveConfig writes the settings atomically (mode 0600).
func SaveConfig(dir string, c Config) error {
	b, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return writePrivate(dir, ConfigFile, b)
}

// UpdateConfig loads, changes and saves the settings.
func UpdateConfig(dir string, change func(*Config)) error {
	if dir == "" {
		return errors.New("no settings folder")
	}
	configMu.Lock()
	defer configMu.Unlock()
	c := LoadConfig(dir)
	change(&c)
	return SaveConfig(dir, c)
}

var configMu sync.Mutex

// CleanComputerName trims a computer name to at most 40 characters without control or
// direction characters.
func CleanComputerName(s string) string {
	return strings.TrimSpace(textsafe.Clean(strings.TrimSpace(s), 40))
}
