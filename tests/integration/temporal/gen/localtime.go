package banking

import (
	"encoding/json"
	"time"
)

// LocalTime accepts the offset local date-time the Java services emit and RFC3339.
type LocalTime time.Time

func (t *LocalTime) UnmarshalJSON(data []byte) error {
	var raw string
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}
	if raw == "" {
		return nil
	}
	for _, layout := range []string{time.RFC3339Nano, "2006-01-02T15:04:05.999999999", "2006-01-02T15:04:05"} {
		parsed, err := time.Parse(layout, raw)
		if err == nil {
			*t = LocalTime(parsed)
			return nil
		}
	}
	return &time.ParseError{Layout: time.RFC3339Nano, Value: raw, Message: "not a local or RFC3339 date-time"}
}

func (t LocalTime) MarshalJSON() ([]byte, error) {
	return json.Marshal(time.Time(t).Format("2006-01-02T15:04:05.999999999"))
}

func (t LocalTime) Time() time.Time { return time.Time(t) }
