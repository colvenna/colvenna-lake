// Package audit records who did what in the console. Every event goes to the pod's log at once
// and to a CloudWatch Logs stream in batches. The console refuses requests it cannot record: when
// the queue is full or the stream has failed for a while, Record returns an error.
package audit

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"sort"
	"sync"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/cloudwatchlogs"
	"github.com/aws/aws-sdk-go-v2/service/cloudwatchlogs/types"
)

// Event is one audited request or action.
type Event struct {
	Time time.Time `json:"time"`
	// Kind is signin (the first request of a sign-in a pod sees), request (a signed-in user's
	// request), denied (refused before any handling), logout, or operation (a controlled
	// operation's: previewed, requested, and its result).
	Kind     string   `json:"kind"`
	Sub      string   `json:"sub,omitempty"`
	Username string   `json:"username,omitempty"`
	Roles    []string `json:"roles,omitempty"`
	// SignedIn is when the user signed in (RFC 3339): the user and it name one session.
	SignedIn string `json:"signedIn,omitempty"`
	Method   string `json:"method"`
	Path     string `json:"path"`
	// Action and Resource are a controlled operation's: what was done, to which namespace/kind/name.
	Action   string `json:"action,omitempty"`
	Resource string `json:"resource,omitempty"`
	Status   int    `json:"status,omitempty"`
	Reason   string `json:"reason,omitempty"`
	SourceIP string `json:"sourceIp,omitempty"`
	Agent    string `json:"userAgent,omitempty"`
	TraceID  string `json:"traceId,omitempty"`
	Pod      string `json:"pod"`
}

// Recorder takes events; an error means the event may not be kept and the request must not go on.
type Recorder interface {
	Record(Event) error
}

// ErrUnavailable is the audit stream refusing more events.
var ErrUnavailable = errors.New("audit unavailable")

// API is the part of the CloudWatch Logs client the stream uses.
type API interface {
	CreateLogStream(context.Context, *cloudwatchlogs.CreateLogStreamInput, ...func(*cloudwatchlogs.Options)) (*cloudwatchlogs.CreateLogStreamOutput, error)
	PutLogEvents(context.Context, *cloudwatchlogs.PutLogEventsInput, ...func(*cloudwatchlogs.Options)) (*cloudwatchlogs.PutLogEventsOutput, error)
}

const (
	batchSize = 100
	// failFor is how long the stream may fail before Record refuses events.
	failFor = 30 * time.Second
)

// Stream writes events to a log stream of its own.
type Stream struct {
	api    API
	group  string
	stream string
	out    io.Writer
	now    func() time.Time
	every  time.Duration

	queue chan Event
	mu    sync.Mutex
	// failing is since when puts have failed, zero while they succeed.
	failing time.Time
	outMu   sync.Mutex
}

// NewStream creates the log stream; the console does not start without it.
func NewStream(ctx context.Context, api API, group, stream string, out io.Writer, capacity int) (*Stream, error) {
	_, err := api.CreateLogStream(ctx, &cloudwatchlogs.CreateLogStreamInput{
		LogGroupName: aws.String(group), LogStreamName: aws.String(stream),
	})
	var exists *types.ResourceAlreadyExistsException
	if err != nil && !errors.As(err, &exists) {
		return nil, err
	}
	return &Stream{
		api: api, group: group, stream: stream, out: out, now: time.Now, every: 2 * time.Second,
		queue: make(chan Event, capacity),
	}, nil
}

// Record writes the event to the pod's log and queues it for the stream.
func (s *Stream) Record(e Event) error {
	line, err := json.Marshal(e)
	if err != nil {
		return err
	}
	s.outMu.Lock()
	_, _ = s.out.Write(append(line, '\n'))
	s.outMu.Unlock()
	s.mu.Lock()
	failing := s.failing
	s.mu.Unlock()
	if !failing.IsZero() && s.now().Sub(failing) > failFor {
		return ErrUnavailable
	}
	select {
	case s.queue <- e:
		return nil
	default:
		return ErrUnavailable
	}
}

// Run sends queued events until ctx ends, then sends what is left. It holds at most one batch:
// while puts fail, the queue fills and Record refuses events.
func (s *Stream) Run(ctx context.Context) {
	ticker := time.NewTicker(s.every)
	defer ticker.Stop()
	var batch []Event
	for {
		var queue <-chan Event
		if len(batch) < batchSize {
			queue = s.queue
		}
		select {
		case e := <-queue:
			batch = append(batch, e)
			if len(batch) < batchSize {
				continue
			}
		case <-ticker.C:
		case <-ctx.Done():
			flush, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			for {
				for len(batch) < batchSize && len(s.queue) > 0 {
					batch = append(batch, <-s.queue)
				}
				if len(batch) == 0 || s.put(flush, &batch) != nil {
					return
				}
			}
		}
		if len(batch) > 0 {
			_ = s.put(ctx, &batch)
		}
	}
}

// put sends up to one batch; what it sent leaves the batch, what failed stays for the next try.
func (s *Stream) put(ctx context.Context, batch *[]Event) error {
	n := min(len(*batch), batchSize)
	part := (*batch)[:n]
	// CloudWatch Logs takes a batch in time order.
	sort.SliceStable(part, func(i, j int) bool { return part[i].Time.Before(part[j].Time) })
	events := make([]types.InputLogEvent, 0, n)
	for _, e := range part {
		line, err := json.Marshal(e)
		if err != nil {
			continue
		}
		events = append(events, types.InputLogEvent{
			Message: aws.String(string(line)), Timestamp: aws.Int64(e.Time.UnixMilli()),
		})
	}
	_, err := s.api.PutLogEvents(ctx, &cloudwatchlogs.PutLogEventsInput{
		LogGroupName: aws.String(s.group), LogStreamName: aws.String(s.stream), LogEvents: events,
	})
	s.mu.Lock()
	defer s.mu.Unlock()
	if err != nil {
		if s.failing.IsZero() {
			s.failing = s.now()
		}
		s.outMu.Lock()
		_, _ = io.WriteString(s.out, `{"kind":"audit-put-failed","error":`+quote(err.Error())+"}\n")
		s.outMu.Unlock()
		return err
	}
	s.failing = time.Time{}
	*batch = (*batch)[n:]
	return nil
}

func quote(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}
