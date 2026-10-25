package gs2

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"math"
)

// MaxFrameSize bounds the frame size a decoder accepts as a parameter (the protocol uses 1 MiB).
const MaxFrameSize = 16 << 20

func newGCM(encKey []byte) (cipher.AEAD, error) {
	if len(encKey) != 32 {
		return nil, errors.New("gs2: encryption key must be 32 bytes")
	}
	block, err := aes.NewCipher(encKey)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

func checkFrameSize(frameSize int) error {
	if frameSize <= 0 || frameSize > MaxFrameSize {
		return fmt.Errorf("gs2: bad frame size %d", frameSize)
	}
	return nil
}

// Decoder reads an encrypted response stream and returns authenticated plaintext frame by frame.
// It never returns bytes that failed authentication, and it reports an error unless the stream
// ends right after a frame flagged LAST.
type Decoder struct {
	r         io.Reader
	aead      cipher.AEAD
	sid       string
	resource  string
	frameSize int

	prefix  [PrefixSize]byte
	nonce   [12]byte
	started bool
	index   uint64
	offset  uint64
	last    bool
	err     error

	ct    []byte
	plain []byte
	aad   []byte
}

// NewDecoder prepares to read a stream whose first frame starts at absolute offset start.
func NewDecoder(r io.Reader, encKey []byte, sid, resource string, start uint64, frameSize int) (*Decoder, error) {
	if err := checkFrameSize(frameSize); err != nil {
		return nil, err
	}
	aead, err := newGCM(encKey)
	if err != nil {
		return nil, err
	}
	return &Decoder{
		r: r, aead: aead, sid: sid, resource: resource, frameSize: frameSize, offset: start,
		ct:    make([]byte, frameSize+TagSize),
		plain: make([]byte, 0, frameSize),
	}, nil
}

// Offset is the absolute offset just past the last authenticated byte returned.
func (d *Decoder) Offset() uint64 { return d.offset }

// Complete reports whether the LAST frame has been received.
func (d *Decoder) Complete() bool { return d.last }

func eofToNoLast(err error) error {
	if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
		return ErrNoLast
	}
	return err
}

// Next returns the next frame's authenticated plaintext and its absolute offset. The slice is
// only valid until the next call. After the LAST frame, Next returns io.EOF once it has checked
// that nothing follows it.
func (d *Decoder) Next() ([]byte, uint64, error) {
	if d.err != nil {
		return nil, d.offset, d.err
	}
	if d.last {
		var one [1]byte
		n, _ := io.ReadFull(d.r, one[:])
		if n > 0 {
			d.err = malformed("data after the last frame")
		} else {
			// The stream is complete and authenticated; a read error here changes nothing.
			d.err = io.EOF
		}
		return nil, d.offset, d.err
	}
	if !d.started {
		if _, err := io.ReadFull(d.r, d.prefix[:]); err != nil {
			d.err = eofToNoLast(err)
			return nil, d.offset, d.err
		}
		copy(d.nonce[:PrefixSize], d.prefix[:])
		d.started = true
	}
	var lenBuf [4]byte
	if _, err := io.ReadFull(d.r, lenBuf[:]); err != nil {
		d.err = eofToNoLast(err)
		return nil, d.offset, d.err
	}
	n := binary.BigEndian.Uint32(lenBuf[:])
	if n < TagSize || uint64(n) > uint64(d.frameSize)+TagSize {
		d.err = malformed("frame length %d out of range", n)
		return nil, d.offset, d.err
	}
	if d.index > math.MaxUint32 {
		d.err = malformed("too many frames")
		return nil, d.offset, d.err
	}
	ct := d.ct[:n]
	if _, err := io.ReadFull(d.r, ct); err != nil {
		d.err = eofToNoLast(err)
		return nil, d.offset, d.err
	}
	plainLen := uint64(n) - TagSize
	if d.offset > math.MaxUint64-plainLen {
		d.err = malformed("offset overflow")
		return nil, d.offset, d.err
	}
	binary.BigEndian.PutUint32(d.nonce[PrefixSize:], uint32(d.index))

	// A short frame can only be the last one. A full frame is normally not last, but can be
	// when the resource length is a multiple of the frame size; the flag is authenticated, so
	// trying both values is safe.
	var plain []byte
	var err error
	last := false
	if plainLen == uint64(d.frameSize) {
		d.aad = AAD(d.aad, d.sid, d.resource, d.offset, false)
		plain, err = d.aead.Open(d.plain[:0], d.nonce[:], ct, d.aad)
		if err != nil {
			last = true
		}
	} else {
		last = true
	}
	if last {
		d.aad = AAD(d.aad, d.sid, d.resource, d.offset, true)
		plain, err = d.aead.Open(d.plain[:0], d.nonce[:], ct, d.aad)
	}
	if err != nil {
		d.err = ErrTampered
		return nil, d.offset, d.err
	}
	off := d.offset
	d.offset += plainLen
	d.index++
	d.last = last
	return plain, off, nil
}

// Encoder writes an encrypted response stream (the phone's side; used by tests and the fake phone).
type Encoder struct {
	w         io.Writer
	aead      cipher.AEAD
	sid       string
	resource  string
	frameSize int
	nonce     [12]byte
	index     uint64
	offset    uint64
	pending   []byte
	aad       []byte
	closed    bool
}

// NewEncoder writes the 8-byte prefix and prepares frames starting at absolute offset start.
func NewEncoder(w io.Writer, encKey, prefix []byte, sid, resource string, start uint64, frameSize int) (*Encoder, error) {
	if err := checkFrameSize(frameSize); err != nil {
		return nil, err
	}
	if len(prefix) != PrefixSize {
		return nil, errors.New("gs2: prefix must be 8 bytes")
	}
	aead, err := newGCM(encKey)
	if err != nil {
		return nil, err
	}
	e := &Encoder{w: w, aead: aead, sid: sid, resource: resource, frameSize: frameSize, offset: start}
	copy(e.nonce[:PrefixSize], prefix)
	if _, err := w.Write(prefix); err != nil {
		return nil, err
	}
	return e, nil
}

// Write buffers plaintext and emits full frames once more data follows them, so that the final
// frame always carries LAST=1.
func (e *Encoder) Write(p []byte) (int, error) {
	if e.closed {
		return 0, errors.New("gs2: write after close")
	}
	total := len(p)
	for len(p) > 0 {
		if len(e.pending) == e.frameSize {
			if err := e.emit(false); err != nil {
				return total - len(p), err
			}
		}
		n := min(len(p), e.frameSize-len(e.pending))
		e.pending = append(e.pending, p[:n]...)
		p = p[n:]
	}
	return total, nil
}

// Close emits the LAST frame (possibly empty).
func (e *Encoder) Close() error {
	if e.closed {
		return nil
	}
	e.closed = true
	return e.emit(true)
}

// FlushNotLast emits whatever is buffered as a frame with LAST=0 (fault injection in tests).
func (e *Encoder) FlushNotLast() error {
	if len(e.pending) == 0 {
		return nil
	}
	return e.emit(false)
}

func (e *Encoder) emit(last bool) error {
	binary.BigEndian.PutUint32(e.nonce[PrefixSize:], uint32(e.index))
	e.aad = AAD(e.aad, e.sid, e.resource, e.offset, last)
	frame := make([]byte, 4, 4+len(e.pending)+TagSize)
	frame = e.aead.Seal(frame, e.nonce[:], e.pending, e.aad)
	binary.BigEndian.PutUint32(frame[:4], uint32(len(frame)-4))
	e.offset += uint64(len(e.pending))
	e.index++
	e.pending = e.pending[:0]
	_, err := e.w.Write(frame)
	return err
}

// UploadLength is the exact byte length of a frame stream carrying size plaintext bytes from
// offset 0: the 8-byte prefix plus, per frame, a 4-byte length and a 16-byte tag (an empty
// resource is one empty frame).
func UploadLength(size int64, frameSize int) int64 {
	frames := (size + int64(frameSize) - 1) / int64(frameSize)
	if frames == 0 {
		frames = 1
	}
	return PrefixSize + frames*(4+TagSize) + size
}

// NewStreamEncoder is NewEncoder with a fresh random prefix (crypto/rand), for streams this
// side sends (§10 uploads). A prefix is never reused: every stream gets a new one.
func NewStreamEncoder(w io.Writer, encKey []byte, sid, resource string, frameSize int) (*Encoder, error) {
	prefix := make([]byte, PrefixSize)
	if _, err := rand.Read(prefix); err != nil {
		return nil, err
	}
	return NewEncoder(w, encKey, prefix, sid, resource, 0, frameSize)
}

// UploadResource is the resource name of an uploaded file in AADs (§10).
func UploadResource(id string) string { return "u:" + id }

// WantsResource is the resource name of the wanted list (§10).
const WantsResource = "wants"
