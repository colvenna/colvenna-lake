package pipeline

import (
	"fmt"
	"html"
	"html/template"
	"net/url"
	"sort"
	"strings"
)

// The drawing's geometry.
const (
	columnWidth = 240
	rowHeight   = 76
	boxWidth    = 200
	boxHeight   = 54
	margin      = 24
)

var kindNames = map[string]string{Source: "数据源", Table: "湖表", Job: "分层作业", Dashboard: "看板"}

// KindName is a kind's name in the console.
func KindName(kind string) string { return kindNames[kind] }

// SVG draws the pipeline as columns of boxes, data flowing left to right: a problem in red, what
// it affects in amber, the rest by its state. Each box links to the node's page.
func SVG(p *Pipeline) template.HTML {
	columns := map[int][]*Node{}
	deepest := 0
	for _, n := range p.Nodes {
		columns[n.Depth] = append(columns[n.Depth], n)
		deepest = max(deepest, n.Depth)
	}
	where := map[string][2]int{}
	tallest := 0
	for d := 0; d <= deepest; d++ {
		nodes := columns[d]
		sort.Slice(nodes, func(i, j int) bool { return nodes[i].ID < nodes[j].ID })
		for i, n := range nodes {
			where[n.ID] = [2]int{margin + d*columnWidth, margin + i*rowHeight}
		}
		tallest = max(tallest, len(nodes))
	}
	width := margin*2 + (deepest+1)*columnWidth - (columnWidth - boxWidth)
	height := margin*2 + max(tallest, 1)*rowHeight
	var b strings.Builder
	fmt.Fprintf(&b, `<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" role="img" aria-label="流水线">`,
		width, height, width, height)
	b.WriteString(`<defs><marker id="arrow" viewBox="0 0 10 10" refX="10" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="#9fb3c8"/></marker></defs>`)
	for _, e := range p.Edges {
		from, ok1 := where[e.From]
		to, ok2 := where[e.To]
		if !ok1 || !ok2 {
			continue
		}
		fmt.Fprintf(&b, `<line x1="%d" y1="%d" x2="%d" y2="%d" stroke="#9fb3c8" stroke-width="1.5" marker-end="url(#arrow)"/>`,
			from[0]+boxWidth, from[1]+boxHeight/2, to[0], to[1]+boxHeight/2)
	}
	for _, n := range p.Nodes {
		at := where[n.ID]
		stroke, fill, state := look(n)
		fmt.Fprintf(&b, `<a href="/pipeline/node?id=%s"><rect x="%d" y="%d" width="%d" height="%d" rx="6" fill="%s" stroke="%s" stroke-width="2"/>`,
			html.EscapeString(url.QueryEscape(n.ID)), at[0], at[1], boxWidth, boxHeight, fill, stroke)
		fmt.Fprintf(&b, `<text x="%d" y="%d" font-size="11" fill="#627d98">%s%s</text>`,
			at[0]+10, at[1]+17, html.EscapeString(KindName(n.Kind)), html.EscapeString(state))
		fmt.Fprintf(&b, `<text x="%d" y="%d" font-size="13" fill="#102a43">%s</text></a>`,
			at[0]+10, at[1]+37, html.EscapeString(short(Label(n), 28)))
	}
	b.WriteString(`</svg>`)
	// Built from escaped text alone.
	return template.HTML(b.String())
}

// look is how a node is drawn: its outline, its fill, and the state its box gives after the kind.
func look(n *Node) (stroke, fill, state string) {
	switch {
	case n.Problem:
		return "#d64545", "#ffeeee", " · 出问题"
	case n.Affected:
		return "#de911d", "#fffbea", " · 受影响"
	case n.Phase == "Pending" || n.Phase == "Upgrading":
		return "#9fb3c8", "#ffffff", " · " + n.Phase
	case n.Phase != "":
		return "#3ebd93", "#ffffff", " · " + n.Phase
	}
	return "#3ebd93", "#ffffff", ""
}

// Label is how the console names a node.
func Label(n *Node) string {
	if n.Kind == Table {
		return n.Name
	}
	return n.Namespace + "/" + n.Name
}

func short(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n-1]) + "…"
}
