package cases

import (
	"errors"
	"time"
)

type Evidence struct {
	ID          string     `json:"id"`
	Prompt      string     `json:"prompt"`
	RequestedBy int64      `json:"requestedBy"`
	RequestedAt time.Time  `json:"requestedAt"`
	Response    *string    `json:"response,omitempty"`
	RespondedBy *int64     `json:"respondedBy,omitempty"`
	RespondedAt *time.Time `json:"respondedAt,omitempty"`
	Deadline    time.Time  `json:"deadline"`
	ClosedAt    *time.Time `json:"closedAt,omitempty"`
}

type Case struct {
	EvidenceEnabled    bool       `json:"evidenceEnabled"`
	InvestigationStage string     `json:"investigationStage"`
	Evidence           *Evidence  `json:"evidenceRequest,omitempty"`
	ID                 int64      `json:"id"`
	TransferID         int64      `json:"transferId"`
	Reason             string     `json:"reason"`
	Status             string     `json:"status"`
	CreatedAt          time.Time  `json:"createdAt"`
	EscalatedAt        *time.Time `json:"escalatedAt,omitempty"`
	ResolvedAt         *time.Time `json:"resolvedAt,omitempty"`
	ResolvedBy         *int64     `json:"-"`
	Resolution         *string    `json:"resolution,omitempty"`
	AiDraft            any        `json:"aiDraft,omitempty"`
}

var ErrConflict = errors.New("case command conflicts with current state")

// Stage derives the investigation phase independently of escalation.
func (c Case) Stage() string {
	switch {
	case c.Status == "RESOLVED":
		return "CLOSED"
	case c.Evidence != nil && c.Evidence.ClosedAt == nil:
		return "WAITING_FOR_EVIDENCE"
	default:
		return "OPERATOR_REVIEW"
	}
}
