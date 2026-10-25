package ui

import (
	"crypto/sha256"
	_ "embed"
	"encoding/base64"
	"errors"
	"strings"
	"sync"
)

// The page is one self-contained HTML document: page.css and page.js are inlined into
// page.html at the {{CSS}} and {{JS}} markers, and the CSP allows exactly those two inline
// blocks by their SHA-256 hashes. The only other loads are status.json, the POST actions
// and thumb/<n> pictures, all from this same origin. Nothing is loaded from anywhere else.

//go:embed page.html
var pageHTML string

//go:embed page.css
var pageCSS string

//go:embed page.js
var pageJS string

type pageAsset struct {
	body []byte
	csp  string
}

var (
	pageOnce   sync.Once
	pageCached pageAsset
	pageErr    error
)

func buildPage() (pageAsset, error) {
	pageOnce.Do(func() { pageCached, pageErr = assemblePage(pageHTML, pageCSS, pageJS) })
	return pageCached, pageErr
}

// normalizeNewlines matches what the browser's HTML parser does before hashing inline blocks.
func normalizeNewlines(s string) string {
	return strings.ReplaceAll(strings.ReplaceAll(s, "\r\n", "\n"), "\r", "\n")
}

func assemblePage(html, css, js string) (pageAsset, error) {
	html, css, js = normalizeNewlines(html), normalizeNewlines(css), normalizeNewlines(js)
	if strings.Contains(strings.ToLower(css), "</style") || strings.Contains(strings.ToLower(js), "</script") ||
		strings.Contains(js, "<!--") {
		return pageAsset{}, errors.New("ui: page.css or page.js would end its inline block early")
	}
	before, rest, ok1 := strings.Cut(html, "{{CSS}}")
	middle, after, ok2 := strings.Cut(rest, "{{JS}}")
	if !ok1 || !ok2 || strings.Contains(after, "{{") {
		return pageAsset{}, errors.New("ui: page.html must contain {{CSS}} then {{JS}} once each")
	}
	body := before + css + middle + js + after
	csp := "default-src 'none'; script-src " + hashSource(js) + "; style-src " + hashSource(css) +
		"; connect-src 'self'; img-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
	return pageAsset{body: []byte(body), csp: csp}, nil
}

func hashSource(s string) string {
	sum := sha256.Sum256([]byte(s))
	return "'sha256-" + base64.StdEncoding.EncodeToString(sum[:]) + "'"
}
