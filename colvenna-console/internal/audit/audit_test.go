package audit

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/cloudwatchlogs"
	"github.com/aws/aws-sdk-go-v2/service/cloudwatchlogs/types"
)

type fakeLogs struct {
	mu      sync.Mutex
	exists  bool
	fail    bool
	streams []string
	events  []string
}

func (f *fakeLogs) CreateLogStream(_ context.Context, in *cloudwatchlogs.CreateLogStreamInput, _ ...func(*cloudwatchlogs.Options)) (*cloudwatchlogs.CreateLogStreamOutput, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.streams = append(f.streams, aws.ToString(in.LogStreamName))
	if f.exists {
		return nil, &types.ResourceAlreadyExistsException{}
	}
	return &cloudwatchlogs.CreateLogStreamOutput{}, nil
}

func (f *fakeLogs) PutLogEvents(_ context.Context, in *cloudwatchlogs.PutLogEventsInput, _ ...func(*cloudwatchlogs.Options)) (*cloudwatchlogs.PutLogEventsOutput, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.fail {
		return nil, errors.New("throttled")
	}
	for i, e := range in.LogEvents {
		if i > 0 && aws.ToInt64(e.Timestamp) < aws.ToInt64(in.LogEvents[i-1].Timestamp) {
			return nil, errors.New("events out of order")
		}
		f.events = append(f.events, aws.ToString(e.Message))
	}
	return &cloudwatchlogs.PutLogEventsOutput{}, nil
}

func (f *fakeLogs) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.events)
}

func TestEventsReachThePodLogAndTheStream(t *testing.T) {
	api := &fakeLogs{exists: true}
	var out bytes.Buffer
	s, err := NewStream(context.Background(), api, "/group", "pod-1", &out, 10)
	if err != nil {
		t.Fatal(err)
	}
	s.every = 10 * time.Millisecond
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { s.Run(ctx); close(done) }()
	base := time.Unix(1_800_000_000, 0)
	for i := range 3 {
		// Out of order on purpose: the stream sorts each batch.
		if err := s.Record(Event{Time: base.Add(time.Duration(3-i) * time.Second), Kind: "request", Username: "alice"}); err != nil {
			t.Fatal(err)
		}
	}
	deadline := time.Now().Add(2 * time.Second)
	for api.count() < 3 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	cancel()
	<-done
	if api.count() != 3 {
		t.Fatalf("%d events in the stream", api.count())
	}
	if strings.Count(out.String(), `"username":"alice"`) != 3 {
		t.Fatalf("pod log: %s", out.String())
	}
	var e Event
	if err := json.Unmarshal([]byte(api.events[0]), &e); err != nil || e.Kind != "request" {
		t.Fatalf("%v %+v", err, e)
	}
}

func TestRecordRefusesWhenTheQueueIsFull(t *testing.T) {
	s, err := NewStream(context.Background(), &fakeLogs{}, "/g", "p", &bytes.Buffer{}, 1)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.Record(Event{}); err != nil {
		t.Fatal(err)
	}
	if err := s.Record(Event{}); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("full queue: %v", err)
	}
}

func TestRecordRefusesOnceTheStreamHasFailedForAWhile(t *testing.T) {
	api := &fakeLogs{fail: true}
	s, err := NewStream(context.Background(), api, "/g", "p", &bytes.Buffer{}, 100)
	if err != nil {
		t.Fatal(err)
	}
	clock := time.Unix(1_800_000_000, 0)
	s.now = func() time.Time { return clock }
	batch := []Event{{Kind: "request"}}
	if err := s.put(context.Background(), &batch); err == nil || len(batch) != 1 {
		t.Fatalf("failed put dropped events: %v %d", err, len(batch))
	}
	if err := s.Record(Event{}); err != nil {
		t.Fatalf("refused before the grace period: %v", err)
	}
	clock = clock.Add(time.Minute)
	if err := s.Record(Event{}); !errors.Is(err, ErrUnavailable) {
		t.Fatalf("accepted after a minute of failures: %v", err)
	}
	api.fail = false
	if err := s.put(context.Background(), &batch); err != nil || len(batch) != 0 {
		t.Fatalf("%v %d", err, len(batch))
	}
	if err := s.Record(Event{}); err != nil {
		t.Fatalf("still refused after recovery: %v", err)
	}
}

func TestNewStreamFailsWithoutTheStream(t *testing.T) {
	if _, err := NewStream(context.Background(), &failingCreate{}, "/g", "p", &bytes.Buffer{}, 1); err == nil {
		t.Fatal("started without a stream")
	}
}

type failingCreate struct{ fakeLogs }

func (*failingCreate) CreateLogStream(context.Context, *cloudwatchlogs.CreateLogStreamInput, ...func(*cloudwatchlogs.Options)) (*cloudwatchlogs.CreateLogStreamOutput, error) {
	return nil, errors.New("access denied")
}
